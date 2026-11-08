package com.pmart.mfc.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.mfc.inventory.application.ReservationBlocker.Blocked;
import com.pmart.mfc.inventory.domain.ReservationState;
import com.pmart.mfc.inventory.infrastructure.ReservationStore.ReservationRow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 재확보 순서(사용자 결정 2026-11-08): 결제 완료 → 배송 약속일 이른 순 → 먼저 예약한 순. */
class ReservationBlockerPriorityTest {

  static Blocked b(String id, ReservationState s, LocalDate promised, String createdAt) {
    var r =
        new ReservationRow(
            id,
            "ORD-" + id,
            "MFC",
            s,
            null,
            null,
            1,
            promised,
            Instant.parse(createdAt),
            Instant.parse(createdAt));
    return new Blocked(r, id + ":Q", "L1", "E-1", "LOT", 1, s);
  }

  @Test
  void paidFirstThenEarliestPromiseThenEarliestReservation() {
    var heldEarly =
        b("H1", ReservationState.HELD, LocalDate.parse("2026-11-08"), "2026-11-08T00:00:00Z");
    var paidLate =
        b("C1", ReservationState.CONFIRMED, LocalDate.parse("2026-11-10"), "2026-11-08T00:00:00Z");
    var paidSoonNew =
        b("C2", ReservationState.CONFIRMED, LocalDate.parse("2026-11-08"), "2026-11-08T02:00:00Z");
    var paidSoonOld =
        b("C3", ReservationState.CONFIRMED, LocalDate.parse("2026-11-08"), "2026-11-08T01:00:00Z");
    var ordered =
        ReservationBlocker.priority(List.of(heldEarly, paidLate, paidSoonNew, paidSoonOld));
    assertThat(ordered.stream().map(x -> x.reservation().reservationId()).toList())
        .containsExactly("C3", "C2", "C1", "H1");
  }
}
