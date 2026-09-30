package com.storeql.product.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CategoryTreeTest {

  private final UUID a = Ids.newId();
  private final UUID b = Ids.newId();
  private final UUID c = Ids.newId();
  private final UUID other = Ids.newId();

  /** A is the root, B under A, C under B. */
  private Map<UUID, UUID> chain() {
    Map<UUID, UUID> parents = new HashMap<>();
    parents.put(a, null);
    parents.put(b, a);
    parents.put(c, b);
    parents.put(other, null);
    return parents;
  }

  @Test
  void aCategoryUnderItselfIsACycle() {
    assertTrue(CategoryTree.createsCycle(chain(), a, a));
  }

  @Test
  void aCategoryUnderItsChildOrGrandchildIsACycle() {
    assertTrue(CategoryTree.createsCycle(chain(), a, b));
    assertTrue(CategoryTree.createsCycle(chain(), a, c));
    assertTrue(CategoryTree.createsCycle(chain(), b, c));
  }

  @Test
  void anyOtherMoveIsNot() {
    assertFalse(CategoryTree.createsCycle(chain(), c, a));
    assertFalse(CategoryTree.createsCycle(chain(), b, other));
    assertFalse(CategoryTree.createsCycle(chain(), a, other));
  }

  @Test
  void becomingARootIsNeverACycle() {
    assertFalse(CategoryTree.createsCycle(chain(), a, null));
    assertFalse(CategoryTree.createsCycle(chain(), c, null));
  }

  @Test
  void aLoopAlreadyInTheDataDoesNotSpinTheWalk() {
    Map<UUID, UUID> loop = chain();
    loop.put(other, c);
    loop.put(c, other); // c and other already loop; neither is a's ancestor
    assertFalse(CategoryTree.createsCycle(loop, a, c));
  }
}
