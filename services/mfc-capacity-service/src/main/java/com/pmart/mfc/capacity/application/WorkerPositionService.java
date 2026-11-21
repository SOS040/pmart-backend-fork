package com.pmart.mfc.capacity.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.capacity.infrastructure.LocationClient;
import com.pmart.mfc.capacity.infrastructure.LocationClient.Position;
import com.pmart.mfc.capacity.infrastructure.WorkerLastLocationClient;
import com.pmart.mfc.capacity.infrastructure.WorkerLastLocationClient.Item;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * 센터 도면 위 작업자 위치(0.34.0, 2026-11-21 mfc-backoffice 요청). 실내 측위가 아니라 작업자가 마지막으로 스캔한 칸의 서는 지점이다:
 * fulfillment·inbound 의 작업자별 마지막 칸 중 늦은 것 → mfc-location 칸 도면 좌표. 좌표가 없으면 unplaced. 그 센터
 * MFC_SUPERVISOR 또는 OPERATOR_ADMIN.
 */
@Service
public class WorkerPositionService {

  static final int DEFAULT_SINCE_MINUTES = 480;

  public record Placed(
      String workerId, Position position, String locationCode, String activityType, Instant at) {}

  public record Unplaced(
      String workerId, String locationCode, String activityType, Instant at, String reason) {}

  public record Positions(
      String mfcId,
      Instant asOf,
      Instant since,
      List<Placed> items,
      List<Unplaced> unplaced,
      List<String> unavailableSources) {}

  private final WorkerLastLocationClient lastLocations;
  private final LocationClient locations;
  private final Clock clock;

  public WorkerPositionService(
      WorkerLastLocationClient lastLocations, LocationClient locations, Clock clock) {
    this.lastLocations = lastLocations;
    this.locations = locations;
    this.clock = clock;
  }

  public Positions list(String mfcId, @Nullable Integer sinceMinutes) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자만 볼 수 있습니다."));
    if (!(p.hasRole("OPERATOR_ADMIN")
        || (p.canAccessCenter(mfcId) && p.hasRole(AssignmentService.SUPERVISOR_ROLE)))) {
      throw new PmartException(
          ErrorCode.FORBIDDEN,
          "작업자 위치는 그 센터 " + AssignmentService.SUPERVISOR_ROLE + "·OPERATOR_ADMIN 만 볼 수 있습니다.");
    }
    int minutes = sinceMinutes == null ? DEFAULT_SINCE_MINUTES : sinceMinutes;
    if (minutes < 1 || minutes > 1440) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "sinceMinutes 는 1~1440 입니다.");
    }
    Instant now = clock.instant();
    Instant since = now.minus(Duration.ofMinutes(minutes));
    var c = lastLocations.collect(mfcId, since);
    Collection<Item> latest = latestPerWorker(c.items());
    List<String> unavailable = new ArrayList<>(c.unavailable());
    Map<String, Position> pos = Map.of();
    boolean locationDown = false;
    if (!latest.isEmpty()) {
      try {
        pos = locations.positions(mfcId, latest.stream().map(Item::locationCode).toList());
      } catch (RuntimeException e) {
        locationDown = true; // 좌표를 못 읽음 — 0명으로 보이지 않게 unplaced 로 둔다
        unavailable.add("mfc-location-service");
      }
    }
    List<Placed> placed = new ArrayList<>();
    List<Unplaced> unplaced = new ArrayList<>();
    List<Item> ordered =
        latest.stream()
            .sorted(Comparator.comparing(Item::at).reversed().thenComparing(Item::workerId))
            .toList();
    for (Item i : ordered) {
      Position xy = pos.get(i.locationCode());
      if (xy != null) {
        placed.add(new Placed(i.workerId(), xy, i.locationCode(), i.activityType(), i.at()));
      } else {
        unplaced.add(
            new Unplaced(
                i.workerId(),
                i.locationCode(),
                i.activityType(),
                i.at(),
                locationDown ? "LOCATION_UNAVAILABLE" : "NO_POSITION"));
      }
    }
    return new Positions(mfcId, now, since, placed, unplaced, unavailable);
  }

  /** 작업자마다 가장 늦은 한 건(같은 시각이면 먼저 온 원천). */
  static Collection<Item> latestPerWorker(List<Item> items) {
    Map<String, Item> by = new HashMap<>();
    for (Item i : items) {
      by.merge(i.workerId(), i, (a, b) -> b.at().isAfter(a.at()) ? b : a);
    }
    return by.values();
  }
}
