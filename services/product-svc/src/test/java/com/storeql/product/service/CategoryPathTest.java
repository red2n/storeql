package com.storeql.product.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;

import com.storeql.ids.Ids;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The in-memory category walk a catalogue republish uses instead of a query per level. */
class CategoryPathTest {

  @Test
  @DisplayName("A path runs from the category up to the root, from one parent map")
  void pathRunsToTheRoot() {
    UUID root = Ids.newId();
    UUID mid = Ids.newId();
    UUID leaf = Ids.newId();
    Map<UUID, UUID> parents = new HashMap<>();
    parents.put(root, null);
    parents.put(mid, root);
    parents.put(leaf, mid);
    assertThat(ProductService.pathIn(parents, leaf), contains(leaf, mid, root));
    assertThat(ProductService.pathIn(parents, root), contains(root));
  }

  @Test
  @DisplayName("No category, an unknown parent and a cycle end the walk rather than the request")
  void edgesEndTheWalk() {
    UUID a = Ids.newId();
    UUID b = Ids.newId();
    assertThat(ProductService.pathIn(Map.of(), null), empty());
    // a parent id is on the path as soon as it is named, as in categoryPath; its own parent is
    // unknown
    assertThat(ProductService.pathIn(Map.of(a, b), a), contains(a, b));
    assertThat(ProductService.pathIn(Map.of(a, b, b, a), a), contains(a, b));
    assertThat(List.copyOf(ProductService.pathIn(Map.of(), a)), contains(a));
  }
}
