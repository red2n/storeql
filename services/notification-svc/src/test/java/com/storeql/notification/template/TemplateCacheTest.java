package com.storeql.notification.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Parsing the same text twice parses once; a bad text is never remembered. */
class TemplateCacheTest {

  @Test
  void theSameSourceIsParsedOnce() {
    String source = "Hello {{name}} from cache test " + System.nanoTime();
    assertSame(Template.parse(source), Template.parse(source));
    assertEquals("Hello Ada from cache", render("Hello {{name}} from cache", "Ada"));
  }

  @Test
  void aTemplateThatDoesNotParseIsStillRefusedEveryTime() {
    String bad = "Hello {{Not A Name}} " + System.nanoTime();
    assertThrows(Template.Invalid.class, () -> Template.parse(bad));
    assertThrows(Template.Invalid.class, () -> Template.parse(bad));
  }

  private static String render(String source, String name) {
    return Template.parse(source).render(n -> "name".equals(n) ? name : null);
  }
}
