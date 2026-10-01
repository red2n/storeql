package com.storeql.events;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Test support: which event types a source tree builds through {@link EventPayload#base} (and so
 * always with an {@code eventId}). A type a service publishes any other way is not in the result,
 * which is what a "every catalogued event carries an eventId" test wants to catch.
 *
 * <p>Sees only a string literal type, {@code EventPayload.base("OrderPlaced", ...)}, also over a
 * line break, and the optional-tenant variant.
 */
public final class EventSourceScan {

  private static final Pattern BUILT =
      Pattern.compile("EventPayload\\s*\\.\\s*base(?:OptionalTenant)?\\(\\s*\"([A-Za-z0-9]+)\"");

  private EventSourceScan() {}

  /** Event types built with the shared base under every {@code src/main} below {@code root}. */
  public static Set<String> typesBuiltWithBase(Path root) {
    Set<String> types = new TreeSet<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path p : (Iterable<Path>) files.filter(EventSourceScan::isMainJava)::iterator) {
        Matcher m = BUILT.matcher(Files.readString(p));
        while (m.find()) {
          types.add(m.group(1));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return types;
  }

  private static boolean isMainJava(Path p) {
    String s = p.toString().replace('\\', '/');
    return s.endsWith(".java") && s.contains("/src/main/") && !s.contains("/target/");
  }
}
