package com.storeql.inventory.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.inventory.domain.Domain.MoveType;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The comment on {@code stock_movements.type} in V1 names the movement types the code writes, and
 * those are the constants of {@link MoveType}. A customer return is not one of them: it is a
 * RECEIVE movement with ref_type RETURN (see InventoryRepository.receiveBackOnce), so a type nobody
 * writes does not sit in the vocabulary.
 */
class MoveTypeVocabularyTest {

  private static String v1() throws IOException {
    try (InputStream in =
        MoveTypeVocabularyTest.class.getResourceAsStream("/db/migration/V1__init.sql")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static TreeSet<String> constants() {
    return new TreeSet<>(
        Arrays.stream(MoveType.class.getFields())
            .filter(f -> Modifier.isStatic(f.getModifiers()))
            .map(MoveTypeVocabularyTest::valueOf)
            .toList());
  }

  private static String valueOf(Field f) {
    try {
      return (String) f.get(null);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  @DisplayName("The V1 comment on stock_movements.type lists exactly the types MoveType defines")
  void theCommentListsTheTypesTheCodeWrites() throws IOException {
    Matcher m =
        Pattern.compile("(?m)^\\s*type\\s+TEXT NOT NULL,\\s*--\\s*([A-Z_|]+)").matcher(v1());
    assertThat("the type column has its comment", m.find(), is(true));
    TreeSet<String> listed = new TreeSet<>(List.of(m.group(1).split("\\|")));
    assertThat(listed, is(constants()));
  }

  @Test
  @DisplayName("A lot split is a movement type of its own: out of one batch, into another")
  void aLotSplitIsAMovementTypeOfItsOwn() {
    assertThat(constants().contains("LOT_SPLIT"), is(true));
  }

  @Test
  @DisplayName("A lot merge is a movement type of its own: out of one batch, into another")
  void aLotMergeIsAMovementTypeOfItsOwn() {
    assertThat(constants().contains("LOT_MERGE"), is(true));
  }

  @Test
  @DisplayName("RETURN is not a movement type: a return is a RECEIVE that cites the return")
  void aReturnIsNotAMovementType() {
    assertThat(constants().contains("RETURN"), is(false));
    assertThat(constants().contains("RECEIVE"), is(true));
  }
}
