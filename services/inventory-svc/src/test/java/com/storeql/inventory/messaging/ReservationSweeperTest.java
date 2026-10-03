package com.storeql.inventory.messaging;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.inventory.repo.InventoryRepository.ReservationRef;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReservationSweeperTest {

  private static ReservationRef ref() {
    return new ReservationRef(Ids.newId(), Ids.newId());
  }

  @Test
  void oneFailingHoldDoesNotStopTheRest() {
    ReservationRef bad = ref();
    ReservationRef good = ref();
    List<UUID> released = new ArrayList<>();
    int n =
        ReservationSweeper.sweep(
            limit -> List.of(bad, good),
            (tenant, id) -> {
              if (id.equals(bad.id())) throw new IllegalStateException("boom");
              released.add(id);
            },
            10,
            1_000_000_000L);
    assertThat(n, is(1));
    assertThat(released, contains(good.id()));
  }

  @Test
  void pagesWhilePagesAreFullThenStops() {
    List<List<ReservationRef>> pages =
        new ArrayList<>(
            List.of(List.of(ref(), ref()), List.of(ref(), ref()), List.of(ref()), List.of(ref())));
    List<UUID> released = new ArrayList<>();
    int n =
        ReservationSweeper.sweep(
            limit -> pages.isEmpty() ? List.of() : pages.remove(0),
            (tenant, id) -> released.add(id),
            2,
            1_000_000_000L);
    assertThat(n, is(5));
    assertThat(pages.size(), is(1)); // the short page ended the tick
  }

  @Test
  void aPageThatReleasesNothingEndsTheTick() {
    int[] asked = {0};
    int n =
        ReservationSweeper.sweep(
            limit -> {
              asked[0]++;
              return List.of(ref(), ref());
            },
            (tenant, id) -> {
              throw new IllegalStateException("never");
            },
            2,
            1_000_000_000L);
    assertThat(n, is(0));
    assertThat(asked[0], is(1));
  }
}
