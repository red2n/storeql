package com.storeql.product.domain;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The shape rule of the category tree: a category is never its own ancestor.
 *
 * <p>Pure, so the rule is tested without a database; the repository feeds it the tenant's parent
 * links on the same transaction that writes the new parent.
 */
public final class CategoryTree {

  private CategoryTree() {}

  /**
   * Whether making {@code newParentId} the parent of {@code categoryId} would make the category its
   * own ancestor, itself included.
   *
   * <p>Walks up from the new parent. Reaching the category means it would sit beneath itself. A
   * loop already in the data (which the walk must not spin on) is a stop, not an answer: it does
   * not involve the category being moved unless the walk meets it first.
   *
   * @param parentOf every category's parent by id (a root maps to {@code null} or is absent)
   * @param categoryId the category being given a parent
   * @param newParentId the parent it is to take, or {@code null} for a root
   * @return {@code true} when the move would create a cycle
   */
  public static boolean createsCycle(Map<UUID, UUID> parentOf, UUID categoryId, UUID newParentId) {
    Set<UUID> seen = new HashSet<>();
    UUID current = newParentId;
    while (current != null) {
      if (current.equals(categoryId)) return true;
      if (!seen.add(current)) return false;
      current = parentOf.get(current);
    }
    return false;
  }
}
