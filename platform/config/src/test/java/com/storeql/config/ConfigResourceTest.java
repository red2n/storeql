package com.storeql.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.web.ApiException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The config service's guard on the names it builds file paths from. A service name or profile is
 * joined into a file name under the config repo, so anything outside {@code [A-Za-z0-9_-]{1,64}} is
 * the way out of the repo directory and into every {@code *.properties} on the disk. The guard runs
 * before any file is looked for, which is what these tests hold it to: a refused name reads
 * nothing, even where a file of that name exists just outside the repo.
 */
class ConfigResourceTest {

  @TempDir Path temp;

  private ConfigResource resource;
  private Path repo;

  @BeforeEach
  void repoWithOneServiceAndAFileOutsideIt() throws IOException {
    repo = Files.createDirectory(temp.resolve("repo"));
    write(repo.resolve("iam-svc.properties"), "storeql.shared=base\nstoreql.base=1\n");
    write(repo.resolve("iam-svc-default.properties"), "storeql.shared=overlay\n");
    // A file one level above the repo: what a traversal would reach if the guard let it through.
    write(temp.resolve("outside.properties"), "storeql.leaked=yes\n");
    write(temp.resolve("outside-x.properties"), "storeql.leaked=yes\n");
    resource = new ConfigResource();
    resource.repoDir = repo.toString();
  }

  private static void write(Path file, String content) throws IOException {
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  private static ApiException refused(Runnable call) {
    return assertThrows(ApiException.class, call::run);
  }

  @Test
  @DisplayName("A name outside the safe charset is refused and no file is read")
  void aNameOutsideTheSafeCharsetIsRefused() {
    List<String> unsafe =
        List.of(
            "prod.local",
            "..",
            "../outside",
            "..\\outside",
            "a/b",
            "a b",
            "iam-svc.properties",
            "pröd",
            "%2e%2e",
            "a".repeat(65),
            "");
    for (String bad : unsafe) {
      ApiException inService = refused(() -> resource.get(bad, "default"));
      assertEquals(400, inService.status(), "service [" + bad + "]");
      assertEquals("CONFIG_INVALID_NAME", inService.code(), "service [" + bad + "]");

      ApiException inProfile = refused(() -> resource.get("iam-svc", bad));
      assertEquals(400, inProfile.status(), "profile [" + bad + "]");
      assertEquals("CONFIG_INVALID_NAME", inProfile.code(), "profile [" + bad + "]");
    }
  }

  @Test
  @DisplayName("A missing name is refused the same way, not as a missing file")
  void aMissingNameIsRefused() {
    ApiException noService = refused(() -> resource.get(null, "default"));
    assertEquals(400, noService.status());
    assertEquals("CONFIG_INVALID_NAME", noService.code());
    ApiException noProfile = refused(() -> resource.get("iam-svc", null));
    assertEquals(400, noProfile.status());
    assertEquals("CONFIG_INVALID_NAME", noProfile.code());
  }

  @Test
  @DisplayName("A refused name leaks nothing of what a traversal would have reached")
  void aRefusedNameLeaksNothing() {
    // outside.properties exists one level above the repo. Were the name joined into a path, this
    // would be served; refused, the answer is the refusal and no data.
    ApiException traversal = refused(() -> resource.get("../outside", "default"));
    assertEquals("CONFIG_INVALID_NAME", traversal.code());
    assertFalse(
        String.valueOf(traversal.getMessage()).contains("leaked"),
        "the refusal does not echo file contents");
    ApiException viaProfile = refused(() -> resource.get("..", "outside"));
    assertEquals("CONFIG_INVALID_NAME", viaProfile.code());
  }

  @Test
  @DisplayName("The guard does not over-refuse: the longest name and every allowed character pass")
  void theGuardDoesNotOverRefuse() throws IOException {
    String longest = "a".repeat(64);
    write(repo.resolve(longest + ".properties"), "storeql.long=yes\n");
    write(repo.resolve("Svc_9-x.properties"), "storeql.mixed=yes\n");
    assertEquals("yes", resource.get(longest, "default").data().get("storeql.long"));
    assertEquals("yes", resource.get("Svc_9-x", "default").data().get("storeql.mixed"));
  }

  @Test
  @DisplayName("A service with no configuration is not found, and the profile overlays the base")
  void unknownIsNotFoundAndTheOverlayWins() {
    ApiException none = refused(() -> resource.get("nobody-svc", "default"));
    assertEquals(404, none.status());
    assertEquals("CONFIG_NOT_FOUND", none.code());

    Map<String, String> merged = resource.get("iam-svc", "default").data();
    assertEquals("overlay", merged.get("storeql.shared"));
    assertEquals("1", merged.get("storeql.base"));
  }
}
