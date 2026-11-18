package com.pmart.center.master.application;

import com.pmart.center.master.domain.Timetable;
import com.pmart.center.master.infrastructure.CenterStore;
import com.pmart.center.master.infrastructure.CenterStore.CenterRow;
import com.pmart.center.master.infrastructure.CenterStore.TimetableRow;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 센터 기준정보·영업 시간표(BO-06). 변경은 approval-service CENTER_MASTER 승인으로만 들어온다 — 승인 행 attributes 가 계약
 * ApplyCenterChangeRequest 모양(changeType·centerId·profile·calendar)이다.
 *
 * <ul>
 *   <li>지원: CREATE, UPDATE_PROFILE, UPDATE_CALENDAR. 휴폐점(TEMPORARY_CLOSE·REOPEN·CLOSE)은 의무 목록과 함께
 *       이후 단계.
 *   <li>승인된 내용이 잘못됐으면(시각 형식·겹침 등) 반영하지 않고 결과에 사유를 남긴다(재처리로 반복 실패시키지 않음).
 *   <li>반영하면 CenterMasterChanged / CenterCalendarChanged(앞으로 7일 영업 구간)를 platform.center.v1 로 낸다.
 *   <li>승인되지 않은 시간표 종류는 CONFIG_REQUIRED — 상시 영업·휴무로 바꾸지 않는다.
 * </ul>
 */
@Service
public class CenterService {

  public static final String TOPIC = "platform.center.v1";
  static final int EVENT_WINDOW_DAYS = 7;

  private static final Logger log = LoggerFactory.getLogger(CenterService.class);

  public record CalendarView(CenterRow center, TimetableRow timetable, Timetable.Day day) {}

  private final CenterStore store;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final ApprovalReceipts receipts;

  public CenterService(
      CenterStore store,
      EventPublisher events,
      EventEnvelopeCodec codec,
      ObjectMapper mapper,
      Clock clock,
      ApprovalReceipts receipts) {
    this.receipts = receipts;
    this.store = store;
    this.events = events;
    this.codec = codec;
    this.mapper = mapper;
    this.clock = clock;
  }

  // ---------- 조회 ----------

  public CenterRow center(String centerId) {
    return store
        .center(centerId, false)
        .orElseThrow(() -> PmartException.notFound("CENTER_NOT_FOUND", "센터가 없습니다: " + centerId));
  }

  public List<CenterRow> centers(
      @Nullable String type, @Nullable String state, @Nullable String zone, int offset, int limit) {
    return store.centers(type, state, zone, offset, limit);
  }

  public CalendarView calendar(String centerId, LocalDate date, String kind) {
    CenterRow c = center(centerId);
    TimetableRow t =
        store
            .timetable(centerId, kind)
            .orElseThrow(
                () -> PmartException.configRequired("center." + centerId + ".timetable." + kind));
    return new CalendarView(c, t, timetable(t).day(date));
  }

  Timetable timetable(TimetableRow t) {
    JsonNode weekly = mapper.readTree(t.weekly());
    Map<String, List<String[]>> raw = new LinkedHashMap<>();
    weekly
        .properties()
        .forEach(
            e -> {
              List<String[]> ranges = new ArrayList<>();
              e.getValue()
                  .forEach(
                      r -> ranges.add(new String[] {r.get(0).asString(), r.get(1).asString()}));
              raw.put(e.getKey(), ranges);
            });
    Set<LocalDate> holidays = new HashSet<>();
    mapper.readTree(t.holidays()).forEach(h -> holidays.add(LocalDate.parse(h.asString())));
    List<Timetable.Restriction> restrictions = new ArrayList<>();
    mapper
        .readTree(t.restrictions())
        .forEach(
            r ->
                restrictions.add(
                    new Timetable.Restriction(
                        LocalDate.parse(r.path("date").asString()),
                        Timetable.Range.of(r.path("start").asString(), r.path("end").asString()),
                        r.hasNonNull("reason") ? r.get("reason").asString() : null)));
    return new Timetable(Timetable.weekly(raw), holidays, restrictions);
  }

  // ---------- 승인 반영 ----------

  /** ApprovalDecided(CENTER_MASTER) 반영 — inbox 트랜잭션 안. */
  public void applyApproval(EventEnvelope e) {
    JsonNode p = e.payload();
    if (!"ApprovalDecided".equals(e.eventType())
        || !"CENTER_MASTER".equals(p.path("type").asString())
        || !"center-master-service".equals(p.path("targetRef").path("targetService").asString())) {
      return;
    }
    String approvalId = p.path("approvalId").asString();
    if (store.applied(approvalId)) {
      return;
    }
    Instant now = clock.instant();
    List<String> results = new ArrayList<>();
    String decision = p.path("decision").asString();
    // 반영 회신 행 사유(RC-1, 2026-11-19) — 승인 행 lineId → 거절 사유. 반영한 행은 넣지 않는다
    java.util.Map<String, String> rejected = new java.util.LinkedHashMap<>();
    if (!"APPROVED".equals(decision)) {
      results.add("NOT_APPROVED");
    } else {
      String approver = p.hasNonNull("approverId") ? p.get("approverId").asString() : null;
      for (JsonNode line : p.path("lines")) {
        JsonNode a = line.path("attributes");
        try {
          results.add(apply(a, approvalId, approver, now));
        } catch (IllegalArgumentException invalid) {
          results.add("INVALID:" + invalid.getMessage());
          rejected.put(line.path("lineId").asString(""), "INVALID_ATTRIBUTES");
          log.warn("센터 승인 {} 반영 안 함: {}", approvalId, invalid.getMessage());
        }
      }
    }
    store.recordApplication(approvalId, String.join(",", results), now);
    log.info("센터 승인 {} → {}", approvalId, results);
    if (ApprovalReceipts.replies(decision)) {
      // 일부 승인(PARTIALLY_APPROVED)은 반영하지 않는다(위 — 센터 기준정보는 전체 승인만) → 모든 행 REJECTED
      boolean whole = "APPROVED".equals(decision);
      receipts.publish(
          approvalId,
          p,
          line -> line.path("lineId").asString(""),
          rejected,
          whole,
          whole ? null : "PARTIAL_APPROVAL_NOT_APPLIED",
          p.path("targetRef").path("targetId").asString(null));
    }
  }

  private String apply(JsonNode a, String approvalId, @Nullable String approver, Instant now) {
    String centerId = text(a, "centerId");
    if (centerId == null || centerId.isBlank()) {
      throw new IllegalArgumentException("centerId 없음");
    }
    String changeType = text(a, "changeType");
    return switch (changeType == null ? "" : changeType) {
      case "CREATE", "UPDATE_PROFILE" ->
          profile(centerId, changeType, a.path("profile"), approvalId, approver, now);
      case "UPDATE_CALENDAR" -> calendar(centerId, a.path("calendar"), approvalId, approver, now);
      default -> "UNSUPPORTED:" + changeType;
    };
  }

  private String profile(
      String centerId,
      String changeType,
      JsonNode profile,
      String approvalId,
      @Nullable String approver,
      Instant now) {
    CenterRow current = store.center(centerId, true).orElse(null);
    if ("CREATE".equals(changeType) && current != null) {
      return "ALREADY_EXISTS:" + centerId;
    }
    if ("UPDATE_PROFILE".equals(changeType) && current == null) {
      throw new IllegalArgumentException("없는 센터: " + centerId);
    }
    ObjectNode merged =
        current == null
            ? mapper.createObjectNode()
            : (ObjectNode) mapper.readTree(current.profile());
    List<String> changed = new ArrayList<>();
    profile
        .properties()
        .forEach(
            f -> {
              merged.set(f.getKey(), f.getValue());
              changed.add(f.getKey());
            });
    String type = firstText(profile, "type", current == null ? null : current.type());
    String name = firstText(profile, "name", current == null ? null : current.name());
    if (type == null
        || !(type.equals("MFC") || type.equals("CDC"))
        || name == null
        || name.isBlank()) {
      throw new IllegalArgumentException("type(MFC|CDC)·name 필요");
    }
    JsonNode coords = profile.path("coordinates");
    int version = current == null ? 1 : current.version() + 1;
    store.upsertCenter(
        new CenterRow(
            centerId,
            version,
            type,
            name,
            firstText(profile, "state", current == null ? "ACTIVE" : current.state()),
            firstText(profile, "address", current == null ? null : current.address()),
            coords.hasNonNull("lat")
                ? coords.get("lat").asDouble()
                : current == null ? null : current.lat(),
            coords.hasNonNull("lng")
                ? coords.get("lng").asDouble()
                : current == null ? null : current.lng(),
            profile.has("temperatureZones")
                ? mapper.writeValueAsString(profile.get("temperatureZones"))
                : current == null ? "[]" : current.temperatureZones(),
            mapper.writeValueAsString(merged),
            approvalId,
            approver,
            now,
            now));
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("centerId", centerId);
    payload.put("version", version);
    payload.put("changedFields", changed);
    payload.put("effectiveAt", now.toString());
    payload.put("approvalRef", approvalRef(approvalId, approver));
    publish("CenterMasterChanged", centerId, version, payload);
    return changeType + ":" + centerId + ":v" + version;
  }

  private String calendar(
      String centerId, JsonNode cal, String approvalId, @Nullable String approver, Instant now) {
    if (store.center(centerId, true).isEmpty()) {
      throw new IllegalArgumentException("없는 센터: " + centerId);
    }
    String kind = text(cal, "kind");
    if (kind == null) {
      throw new IllegalArgumentException("calendar.kind 필요");
    }
    Map<String, List<String[]>> weeklyRaw = new LinkedHashMap<>();
    for (JsonNode w : cal.path("weekly")) {
      List<String[]> ranges = new ArrayList<>();
      w.path("ranges")
          .forEach(
              r -> ranges.add(new String[] {r.path("start").asString(), r.path("end").asString()}));
      weeklyRaw.put(w.path("dayOfWeek").asString(), ranges);
    }
    Map<DayOfWeek, List<Timetable.Range>> weekly = Timetable.weekly(weeklyRaw); // 검증
    Map<String, Object> weeklyJson = new LinkedHashMap<>();
    weekly.forEach(
        (d, ranges) ->
            weeklyJson.put(
                Timetable.dayName(d),
                ranges.stream().map(r -> List.of(r.start(), r.end())).toList()));
    List<String> holidays = new ArrayList<>();
    cal.path("holidays").forEach(h -> holidays.add(LocalDate.parse(h.asString()).toString()));
    List<Map<String, Object>> restrictions = new ArrayList<>();
    for (JsonNode r : cal.path("temporaryRestrictions")) {
      Timetable.Range range =
          Timetable.Range.of(
              r.path("range").path("start").asString(), r.path("range").path("end").asString());
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("date", LocalDate.parse(r.path("date").asString()).toString());
      m.put("start", range.start());
      m.put("end", range.end());
      m.put("reason", text(r, "reason"));
      restrictions.add(m);
    }
    int version = store.timetable(centerId, kind).map(t -> t.version() + 1).orElse(1);
    TimetableRow row =
        new TimetableRow(
            centerId,
            kind,
            version,
            mapper.writeValueAsString(weeklyJson),
            mapper.writeValueAsString(holidays),
            mapper.writeValueAsString(restrictions),
            approvalId,
            approver,
            now);
    store.upsertTimetable(row);
    List<Map<String, Object>> windows = new ArrayList<>();
    LocalDate today = ZonedDateTime.ofInstant(now, Timetable.SEOUL).toLocalDate();
    for (ZonedDateTime[] w : timetable(row).windows(today, EVENT_WINDOW_DAYS)) {
      windows.add(
          Map.of(
              "start", w[0].toInstant().toString(),
              "end", w[1].toInstant().toString(),
              "meaning", "WORK"));
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("centerId", centerId);
    payload.put("calendarVersion", version);
    payload.put("kind", kind);
    payload.put("windows", windows);
    payload.put("effectiveAt", now.toString());
    payload.put("approvalRef", approvalRef(approvalId, approver));
    publish("CenterCalendarChanged", centerId, version, payload);
    return "UPDATE_CALENDAR:" + centerId + ":" + kind + ":v" + version;
  }

  private void publish(String type, String centerId, int version, Map<String, Object> payload) {
    events.publish(
        codec.create(
            events.sourceSystem(),
            type,
            1,
            "Center",
            centerId,
            version,
            null,
            EventEnvelope.EventSource.REAL,
            payload),
        TOPIC);
  }

  private static Map<String, Object> approvalRef(String approvalId, @Nullable String approver) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("approvalId", approvalId);
    if (approver != null) {
      m.put("approverId", approver);
    }
    return m;
  }

  private static @Nullable String text(JsonNode n, String f) {
    return n.hasNonNull(f) ? n.get(f).asString() : null;
  }

  private static @Nullable String firstText(JsonNode n, String f, @Nullable String fallback) {
    String v = text(n, f);
    return v == null ? fallback : v;
  }
}
