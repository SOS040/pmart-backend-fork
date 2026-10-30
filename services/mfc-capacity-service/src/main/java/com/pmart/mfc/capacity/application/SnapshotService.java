package com.pmart.mfc.capacity.application;

import com.pmart.mfc.capacity.domain.CapacityRules;
import com.pmart.mfc.capacity.domain.CapacityRules.HourWindow;
import com.pmart.mfc.capacity.infrastructure.HoldStore;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * 여력 스냅샷. [시험 정책] 단계별(피킹·패킹·보관·인력) 환산이 아직 없어 수용 단위는 정책값 하나이고, 그것을 WORKFORCE 단계 1개로 보여 준다. 운영 제한은 아직
 * 구현 전이라 활성 제한은 항상 없다.
 */
@Service
public class SnapshotService {

  public record Snapshot(
      String mfcId,
      HourWindow window,
      int version,
      Instant asOf,
      int capacityUnits,
      int heldUnits,
      int confirmedUnits,
      int availableUnits) {}

  private final HoldStore store;
  private final CapacityProperties props;
  private final Clock clock;

  public SnapshotService(HoldStore store, CapacityProperties props, Clock clock) {
    this.store = store;
    this.props = props;
    this.clock = clock;
  }

  public Snapshot snapshot(String mfcId, String windowText) {
    HourWindow window = CapacityRules.window(windowText);
    int capacity = CapacityRules.requireCapacity(props.unitsPerHour());
    Instant now = clock.instant();
    HoldStore.Occupancy occ = store.occupancy(mfcId, window, now);
    return new Snapshot(
        mfcId,
        window,
        occ.version(),
        now,
        capacity,
        occ.held(),
        occ.confirmed(),
        CapacityRules.available(capacity, occ.held(), occ.confirmed()));
  }
}
