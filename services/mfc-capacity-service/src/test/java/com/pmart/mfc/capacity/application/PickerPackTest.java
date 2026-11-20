package com.pmart.mfc.capacity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.mfc.capacity.infrastructure.FulfillmentClient.TaskView;
import org.junit.jupiter.api.Test;

/** 피킹한 사람만 그 출고 PACK(사용자 결정 2026-11-18, mfc-app 요청 R1·R2). */
class PickerPackTest {

  static TaskView pack(String pickedBy) {
    return new TaskView("S1-PACK", "PACK", "READY", 1, null, null, 2, 2, pickedBy);
  }

  @Test
  void onlyPickerTakesPackOfShipmentItPicked() {
    assertThat(AssignmentService.pickerMayTake(pack("w1"), "w1")).isTrue();
    assertThat(AssignmentService.pickerMayTake(pack("w1"), "w2")).isFalse();
  }

  @Test
  void packWithUnknownPickerAndOtherTypesAreOpenToAnyone() {
    assertThat(AssignmentService.pickerMayTake(pack(null), "w2")).isTrue();
    TaskView pick = new TaskView("S1-PICK", "PICK", "READY", 1, null, null, 2, 2, "w1");
    assertThat(AssignmentService.pickerMayTake(pick, "w2")).isTrue();
  }

  static com.pmart.common.messaging.EventEnvelope progressed(java.util.Map<String, Object> p) {
    return com.pmart.common.test.EventEnvelopeFixtures.envelope("TaskProgressed")
        .from("mfc-fulfillment-service")
        .aggregate("Task", "S1-PACK", 2)
        .payload(p)
        .build();
  }

  /** 피킹 끝 PACK 바로 배정(ADR-0078) — PACK 이 WAITING → READY 로 풀린 사건 + 피커가 있을 때만. */
  @Test
  void onlyPackReleasedFromWaitingWithPickerIsAssignedToPicker() {
    var base =
        new java.util.HashMap<String, Object>(
            java.util.Map.of(
                "taskId",
                "S1-PACK",
                "mfcId",
                "MFC-1",
                "type",
                "PACK",
                "state",
                "READY",
                "previousState",
                "WAITING",
                "pickedBy",
                "w1"));
    assertThat(AssignmentService.packReleasedToPicker(progressed(base))).isTrue();
    var noPicker = new java.util.HashMap<>(base);
    noPicker.remove("pickedBy");
    assertThat(AssignmentService.packReleasedToPicker(progressed(noPicker))).isFalse();
    var assigned = new java.util.HashMap<>(base);
    assigned.put("state", "ASSIGNED");
    assertThat(AssignmentService.packReleasedToPicker(progressed(assigned))).isFalse();
    var pick = new java.util.HashMap<>(base);
    pick.put("type", "PICK");
    assertThat(AssignmentService.packReleasedToPicker(progressed(pick))).isFalse();
    var started = new java.util.HashMap<>(base);
    started.put("previousState", "READY");
    assertThat(AssignmentService.packReleasedToPicker(progressed(started))).isFalse();
  }

  @Test
  void ownPackComesBeforeAnyOtherCandidate() {
    for (String type :
        new String[] {"PICK", "PACK", "REPLENISH", "INSPECT", "PUTAWAY", "MOVE", "RETURN"}) {
      assertThat(AssignmentService.OWN_PACK_RANK).isLessThan(AssignmentService.rank(type));
    }
  }
}
