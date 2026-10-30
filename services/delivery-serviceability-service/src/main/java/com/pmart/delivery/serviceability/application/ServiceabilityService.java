package com.pmart.delivery.serviceability.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.serviceability.domain.ZoneMatcher;
import com.pmart.delivery.serviceability.infrastructure.MappingStore;
import com.pmart.delivery.serviceability.infrastructure.MappingStore.CheckRow;
import com.pmart.delivery.serviceability.infrastructure.MappingStore.VersionRow;
import com.pmart.delivery.serviceability.infrastructure.MappingStore.ZoneRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 권역 매핑 버전(초안 → SERVICEABILITY 승인 → 발효)과 주소 판정(BO-07, API-SERVICEAREA-001).
 *
 * <ul>
 *   <li>초안은 운영자만. 내용(권역·발효 시각·시험 주소)을 해시로 잠가 승인 요청 본문에 싣고, 승인 때 같은 해시만 발효한다.
 *   <li>시험 주소가 있으면 발효 직전에 기대 판정과 비교하고, 다르면 발효하지 않는다(TEST_ADDRESS_MISMATCH).
 *   <li>판정은 좌표로만 한다(주소 정규화·지오코딩 없음). 좌표가 없으면 NEEDS_INPUT(geo). 발효 버전이 없으면 CONFIG_REQUIRED.
 *   <li>발효해도 기존 주문의 MFC·버전은 바꾸지 않는다(주문이 당시 버전을 고정).
 * </ul>
 */
@Service
public class ServiceabilityService {

  public static final String TARGET_PREFIX = "serviceabilityVersion:";
  public static final String TOPIC = "delivery.serviceability.v1";

  private static final Logger log = LoggerFactory.getLogger(ServiceabilityService.class);

  /** 판정 입력. */
  public record CheckInput(
      @Nullable Double lat,
      @Nullable Double lng,
      @Nullable Integer mappingVersion,
      @Nullable String purpose) {}

  private final MappingStore store;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final ObjectMapper mapper;
  private final TransactionTemplate tx;
  private final Clock clock;

  public ServiceabilityService(
      MappingStore store,
      RequestIdempotency idempotency,
      EventPublisher events,
      EventEnvelopeCodec codec,
      ObjectMapper mapper,
      TransactionTemplate tx,
      Clock clock) {
    this.store = store;
    this.idempotency = idempotency;
    this.events = events;
    this.codec = codec;
    this.mapper = mapper;
    this.tx = tx;
    this.clock = clock;
  }

  // ---------- 매핑 버전 ----------

  /** 초안 등록 → 버전 번호. 같은 키 재전송은 같은 버전. */
  public int createDraft(String requestId, String sourceSystem, JsonNode draft) {
    PmartPrincipal op =
        PmartPrincipal.current()
            .filter(p -> p.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자만 할 수 있습니다."));
    validateZones(draft.path("zones"));
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "createServiceabilityVersion", op.subject()),
            draft,
            Integer.class,
            () -> {
              Instant now = clock.instant();
              int version = store.nextVersion();
              Instant effectiveFrom = instant(draft, "effectiveFrom");
              store.insertVersion(
                  version,
                  hash(draft),
                  effectiveFrom,
                  text(draft, "changeSummary"),
                  mapper.writeValueAsString(
                      draft.path("testAddresses").isArray()
                          ? draft.get("testAddresses")
                          : List.of()),
                  op.subject(),
                  now);
              for (JsonNode z : draft.path("zones")) {
                store.insertZone(
                    new ZoneRow(
                        version,
                        z.path("zoneId").asString(),
                        z.path("mfcId").asString(),
                        text(z, "name"),
                        z.hasNonNull("priority") ? z.get("priority").asInt() : null,
                        mapper.writeValueAsString(z.get("geometry"))));
              }
              log.info("권역 매핑 버전 {} 초안 by {}", version, op.subject());
              return version;
            })
        .value();
  }

  /** approval-service ChangeRequestBody 모양(제출 준비). */
  public Map<String, Object> approvalRequest(int version) {
    VersionRow v = version(version);
    List<ZoneRow> zones = store.zones(version);
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("draftHash", v.draftHash());
    attrs.put("zoneCount", zones.size());
    attrs.put("mfcIds", zones.stream().map(ZoneRow::mfcId).distinct().toList());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "SERVICEABILITY");
    body.put("targetService", "delivery-serviceability-service");
    body.put("targetRef", TARGET_PREFIX + version);
    body.put("allowedActions", List.of("ACTIVATE_MAPPING_VERSION"));
    body.put(
        "summary",
        "권역 매핑 버전 "
            + version
            + " — 권역 "
            + zones.size()
            + "개"
            + (v.changeSummary() == null ? "" : " · " + v.changeSummary()));
    body.put("lines", List.of(Map.of("lineKey", TARGET_PREFIX + version, "attributes", attrs)));
    if (v.effectiveFrom() != null) {
      body.put("requestedEffectiveFrom", v.effectiveFrom().toString());
    }
    return body;
  }

  public VersionRow version(int version) {
    return store
        .version(version, false)
        .orElseThrow(() -> PmartException.notFound("MAPPING_VERSION_NOT_FOUND", "매핑 버전이 없습니다."));
  }

  public VersionRow current() {
    return store
        .active()
        .orElseThrow(
            () -> PmartException.notFound("MAPPING_VERSION_NOT_ACTIVE", "발효된 매핑 버전이 없습니다."));
  }

  public List<ZoneRow> zones(int version) {
    return store.zones(version);
  }

  /** ApprovalDecided(SERVICEABILITY) 반영 — inbox 트랜잭션 안. */
  public void applyApproval(EventEnvelope e) {
    if (!"ApprovalDecided".equals(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    String targetId = p.path("targetRef").path("targetId").asString("");
    if (!"SERVICEABILITY".equals(p.path("type").asString())
        || !"delivery-serviceability-service"
            .equals(p.path("targetRef").path("targetService").asString())
        || !targetId.startsWith(TARGET_PREFIX)) {
      return;
    }
    String approvalId = p.path("approvalId").asString();
    if (store.approvalApplied(approvalId)) {
      return;
    }
    Instant now = clock.instant();
    int version = Integer.parseInt(targetId.substring(TARGET_PREFIX.length()));
    VersionRow v = store.version(version, true).orElse(null);
    String result;
    if (v == null || !"DRAFT".equals(v.state())) {
      result = "IGNORED";
    } else if (!"APPROVED".equals(p.path("decision").asString())) {
      store.reject(version, approvalId, now);
      result = "REJECTED";
    } else if (!v.draftHash()
        .equals(p.path("lines").path(0).path("attributes").path("draftHash").asString(""))) {
      store.reject(version, approvalId, now);
      result = "APPROVAL_SCOPE_MISMATCH";
    } else {
      List<ZoneMatcher.Zone> zones = toZones(store.zones(version));
      int verified = verifyTestAddresses(v, zones);
      if (verified < 0) {
        store.reject(version, approvalId, now);
        result = "TEST_ADDRESS_MISMATCH";
      } else {
        store.activate(version, approvalId, verified, now);
        publishApplied(version, approvalId, now);
        result = "ACTIVATED";
      }
    }
    store.recordApplication(approvalId, targetId, result, now);
    log.info("권역 매핑 버전 {} 승인 {} → {}", version, approvalId, result);
  }

  // ---------- 판정 ----------

  /** 판정 → checkId. 같은 키 재전송은 같은 판정. */
  public String check(String requestId, String sourceSystem, CheckInput in, Object body) {
    String actor =
        PmartPrincipal.current().map(PmartPrincipal::subject).orElse(RequestIdempotency.ANONYMOUS);
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "createServiceabilityCheck", actor),
            body,
            String.class,
            () -> evaluate(in))
        .value();
  }

  public CheckRow result(String checkId) {
    return store
        .check(checkId)
        .orElseThrow(() -> PmartException.notFound("CHECK_NOT_FOUND", "판정 결과가 없습니다."));
  }

  private String evaluate(CheckInput in) {
    Instant now = clock.instant();
    String checkId = "SC-" + UUID.randomUUID();
    if (in.lat() == null || in.lng() == null) {
      store.insertCheck(
          new CheckRow(
              checkId, "NEEDS_INPUT", null, null, null, "[]", "[\"geo\"]", null, null, now),
          in.purpose());
      return checkId;
    }
    VersionRow v =
        in.mappingVersion() != null
            ? version(in.mappingVersion())
            : store
                .active()
                .orElseThrow(
                    () ->
                        PmartException.configRequired("serviceability.mapping-version(발효 버전 없음)"));
    ZoneMatcher.Match m = ZoneMatcher.match(toZones(store.zones(v.version())), in.lat(), in.lng());
    List<Map<String, String>> overlapping = new ArrayList<>();
    m.overlapping().forEach(z -> overlapping.add(Map.of("zoneId", z.zoneId(), "mfcId", z.mfcId())));
    store.insertCheck(
        new CheckRow(
            checkId,
            m.result(),
            m.zone() == null ? null : m.zone().mfcId(),
            m.zone() == null ? null : m.zone().zoneId(),
            v.version(),
            mapper.writeValueAsString(overlapping),
            "[]",
            in.lat(),
            in.lng(),
            now),
        in.purpose());
    return checkId;
  }

  // ---------- 내부 ----------

  /** 시험 주소 검증: 모두 맞으면 건수, 하나라도 다르면 -1. */
  private int verifyTestAddresses(VersionRow v, List<ZoneMatcher.Zone> zones) {
    JsonNode tests = mapper.readTree(v.testAddresses());
    int n = 0;
    for (JsonNode t : tests) {
      ZoneMatcher.Match m =
          ZoneMatcher.match(zones, t.path("lat").asDouble(), t.path("lng").asDouble());
      String expected = t.path("expectedResult").asString();
      String expectedMfc = text(t, "expectedMfcId");
      boolean ok =
          expected.equals(m.result())
              && (expectedMfc == null
                  || (m.zone() != null && expectedMfc.equals(m.zone().mfcId())));
      if (!ok) {
        log.warn("시험 주소 {} 기대 {} / 실제 {}", text(t, "label"), expected, m.result());
        return -1;
      }
      n++;
    }
    return n;
  }

  private void validateZones(JsonNode zones) {
    if (!zones.isArray() || zones.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "권역이 하나 이상 있어야 합니다.");
    }
    Set<String> ids = new HashSet<>();
    for (JsonNode z : zones) {
      String id = z.path("zoneId").asString("");
      if (!ids.add(id)) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "권역 ID 가 중복되었습니다: " + id);
      }
      ZoneMatcher.validate(polygons(z.path("geometry")))
          .ifPresent(
              problem -> {
                throw new PmartException(
                    ErrorCode.VALIDATION_FAILED, "권역 " + id + " 경계 오류: " + problem);
              });
    }
  }

  private List<ZoneMatcher.Zone> toZones(List<ZoneRow> rows) {
    List<ZoneMatcher.Zone> out = new ArrayList<>();
    for (ZoneRow r : rows) {
      out.add(
          new ZoneMatcher.Zone(
              r.zoneId(), r.mfcId(), r.priority(), polygons(mapper.readTree(r.geometry()))));
    }
    return out;
  }

  /** GeoJSON Polygon/MultiPolygon → 다각형 목록. */
  static List<List<List<double[]>>> polygons(JsonNode geometry) {
    List<List<List<double[]>>> out = new ArrayList<>();
    String type = geometry.path("type").asString("");
    JsonNode coords = geometry.path("coordinates");
    if ("Polygon".equals(type)) {
      out.add(rings(coords));
    } else if ("MultiPolygon".equals(type)) {
      for (JsonNode poly : coords) {
        out.add(rings(poly));
      }
    }
    return out;
  }

  private static List<List<double[]>> rings(JsonNode poly) {
    List<List<double[]>> rings = new ArrayList<>();
    for (JsonNode ring : poly) {
      List<double[]> points = new ArrayList<>();
      for (JsonNode pt : ring) {
        points.add(new double[] {pt.path(0).asDouble(), pt.path(1).asDouble()});
      }
      rings.add(points);
    }
    return rings;
  }

  private void publishApplied(int version, String approvalId, Instant at) {
    List<Map<String, Object>> changed = new ArrayList<>();
    for (ZoneRow z : store.zones(version)) {
      changed.add(Map.of("zoneId", z.zoneId(), "mfcId", z.mfcId(), "change", "ACTIVE"));
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("mappingVersion", version);
    payload.put("effectiveAt", at.toString());
    payload.put("changedZones", changed);
    payload.put("approvalRef", Map.of("approvalId", approvalId));
    EventEnvelope e =
        codec.create(
            events.sourceSystem(),
            "ServiceabilityVersionApplied",
            1,
            "ServiceabilityMapping",
            "mapping",
            version,
            new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, events.sourceSystem()),
            EventEnvelope.EventSource.REAL,
            payload);
    events.publish(e, TOPIC);
  }

  private String hash(JsonNode draft) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(mapper.writeValueAsString(draft).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static @Nullable String text(JsonNode n, String f) {
    return n.hasNonNull(f) ? n.get(f).asString() : null;
  }

  private static @Nullable Instant instant(JsonNode n, String f) {
    return n.hasNonNull(f) ? OffsetDateTime.parse(n.get(f).asString()).toInstant() : null;
  }
}
