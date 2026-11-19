package com.pmart.search.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.search.domain.SynonymRules;
import com.pmart.search.domain.SynonymRules.Change;
import com.pmart.search.domain.SynonymRules.Outcome;
import com.pmart.search.domain.SynonymRules.Rule;
import com.pmart.search.infrastructure.ApprovalReceiptSender;
import com.pmart.search.infrastructure.SearchIndex;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 동의어 규칙의 정본(ADR-0073 단계 B). 규칙 색인 {@code search-synonym-rules} 의 {@code state} 문서(버전·규칙·마지막 반영
 * 승인)가 정본이고, ES 동의어 집합은 그 적용본이다. 바꾸기는 승인(ApprovalDecided, 종류 SEARCH_SYNONYM)으로만.
 *
 * <p>DB 가 없어 inbox 대신 승인 ID 로 멱등: ① 반영 기록 {@code approval-<승인 ID>} 이 있으면 같은 회신만 다시 보낸다 ② state 의
 * 마지막 반영 승인이 이 승인이면(규칙은 바꿨는데 기록 전에 멈춤) 기록을 남기고 회신 ③ 아니면 기준 버전을 비교해 반영. 순서가 동의어 집합 → state → 기록 →
 * 회신이라 어디서 멈춰도 다시 받으면 같은 결과가 된다. 소비는 한 인스턴스·한 스레드 전제(LAN 1대) — 그래도 이 안에서는 한 번에 하나.
 */
@Service
public class SynonymRegistry {

  public static final String TYPE = "SEARCH_SYNONYM";
  static final String TARGET_SERVICE = "search-service";
  static final String STATE_ID = "state";
  static final String APPLICATION_PREFIX = "approval-";

  private static final Logger log = LoggerFactory.getLogger(SynonymRegistry.class);

  /** 지금 규칙. version 0 = 아직 반영한 적 없음. */
  public record State(
      long version,
      List<Rule> rules,
      @Nullable String lastApprovalId,
      @Nullable Instant updatedAt) {}

  /** 승인 하나의 반영 기록. */
  public record Application(
      String approvalId,
      @Nullable String changeRequestId,
      @Nullable String approverId,
      String result,
      @Nullable String reason,
      @Nullable Long baseVersion,
      long version,
      Map<String, String> lineReasons,
      Instant at) {}

  private final SearchIndex index;
  private final ApprovalReceiptSender receipts;
  private final ObjectMapper mapper;
  private final Clock clock;

  public SynonymRegistry(
      SearchIndex index, ApprovalReceiptSender receipts, ObjectMapper mapper, Clock clock) {
    this.index = index;
    this.receipts = receipts;
    this.mapper = mapper;
    this.clock = clock;
  }

  public State state() {
    return index
        .get(SearchIndex.SYNONYM_RULES, STATE_ID)
        .map(this::toState)
        .orElse(new State(0, List.of(), null, null));
  }

  /** 최근 반영 기록(최근 순). */
  public List<Application> applications(int size) {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("size", size);
    request.put("query", Map.of("term", Map.of("kind", "application")));
    request.put("sort", List.of(Map.of("at", "desc")));
    List<Application> out = new ArrayList<>();
    for (JsonNode hit :
        index.search(SearchIndex.SYNONYM_RULES, request).path("hits").path("hits")) {
      out.add(toApplication(hit.path("_source")));
    }
    return out;
  }

  /** ApprovalDecided 처리 — SEARCH_SYNONYM·이 서비스 대상·승인된 결정만. 반려는 반영 요청이 아니라 회신하지 않는다. */
  public synchronized void onApprovalDecided(EventEnvelope envelope) {
    if (!"ApprovalDecided".equals(envelope.eventType())) {
      return;
    }
    JsonNode p = mapper.valueToTree(envelope.payload());
    JsonNode target = p.path("targetRef");
    if (!TYPE.equals(p.path("type").asString())
        || !TARGET_SERVICE.equals(target.path("targetService").asString())) {
      return;
    }
    String decision = p.path("decision").asString();
    if (!"APPROVED".equals(decision) && !"PARTIALLY_APPROVED".equals(decision)) {
      return;
    }
    String approvalId = p.path("approvalId").asString();
    Optional<JsonNode> done = index.get(SearchIndex.SYNONYM_RULES, APPLICATION_PREFIX + approvalId);
    if (done.isPresent()) {
      reply(p, toApplication(done.get()));
      return;
    }
    State s = state();
    Long base =
        target.hasNonNull("expectedVersion") ? target.get("expectedVersion").asLong() : null;
    Instant at = clock.instant();
    if (approvalId.equals(s.lastApprovalId())) {
      Application a = record(p, "APPLIED", null, base, s.version(), Map.of(), at);
      reply(p, a);
      return;
    }
    List<Change> changes = new ArrayList<>();
    for (JsonNode line : p.path("lines")) {
      if (!"APPROVED".equals(line.path("decision").asString("APPROVED"))) {
        continue; // 부분 승인에서 빠진 행은 바꾸지 않는다
      }
      JsonNode attrs = line.path("attributes");
      List<String> terms = null;
      if (attrs.path("terms").isArray()) {
        terms = new ArrayList<>();
        for (JsonNode t : attrs.get("terms")) {
          terms.add(t.asString(""));
        }
      }
      changes.add(
          new Change(line.path("lineId").asString(), attrs.path("op").asString(null), terms));
    }
    Outcome o = SynonymRules.apply(s.rules(), s.version(), base, changes);
    Application a;
    if (o.applied()) {
      long version = s.version() + 1;
      index.putSynonymRules(SynonymRules.esRules(o.rules()));
      writeState(new State(version, o.rules(), approvalId, at));
      a = record(p, "APPLIED", null, base, version, Map.of(), at);
      log.info("동의어 승인 {} 반영 → 버전 {} (규칙 {}개)", approvalId, version, o.rules().size());
    } else {
      a = record(p, "REJECTED", o.reason(), base, s.version(), o.lineReasons(), at);
      log.warn("동의어 승인 {} 반영 안 함: {} {}", approvalId, o.reason(), o.lineReasons());
    }
    reply(p, a);
  }

  private void writeState(State s) {
    List<Map<String, Object>> rules = new ArrayList<>();
    for (Rule r : s.rules()) {
      rules.add(Map.of("id", r.id(), "terms", r.terms()));
    }
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("kind", "state");
    doc.put("version", s.version());
    doc.put("rules", rules);
    doc.put("approvalId", s.lastApprovalId());
    doc.put("at", s.updatedAt() == null ? null : s.updatedAt().toString());
    index.put(SearchIndex.SYNONYM_RULES, STATE_ID, doc);
  }

  private Application record(
      JsonNode p,
      String result,
      @Nullable String reason,
      @Nullable Long base,
      long version,
      Map<String, String> lineReasons,
      Instant at) {
    Application a =
        new Application(
            p.path("approvalId").asString(),
            p.path("changeRequestId").asString(null),
            p.path("approverId").asString(null),
            result,
            reason,
            base,
            version,
            lineReasons,
            at);
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("kind", "application");
    doc.put("approvalId", a.approvalId());
    doc.put("changeRequestId", a.changeRequestId());
    doc.put("approverId", a.approverId());
    doc.put("result", result);
    doc.put("reason", reason);
    doc.put("baseVersion", base);
    doc.put("version", version);
    doc.put("changes", mapper.convertValue(p.path("lines"), List.class));
    doc.put("lineReasons", lineReasons);
    doc.put("at", at.toString());
    index.put(SearchIndex.SYNONYM_RULES, APPLICATION_PREFIX + a.approvalId(), doc);
    return a;
  }

  /** 회신 — 행마다 결과(전부 반영이면 모두 APPLIED, 아니면 모두 REJECTED·행 사유 또는 전체 사유). */
  private void reply(JsonNode p, Application a) {
    boolean applied = "APPLIED".equals(a.result());
    List<Map<String, Object>> lines = new ArrayList<>();
    int i = 0;
    for (JsonNode line : p.path("lines")) {
      String key = line.hasNonNull("lineId") ? line.get("lineId").asString() : "line-" + i;
      i++;
      Map<String, Object> rl = new LinkedHashMap<>();
      rl.put("lineKey", key);
      rl.put("result", applied ? "APPLIED" : "REJECTED");
      rl.put("reasonCode", applied ? null : a.lineReasons().getOrDefault(key, a.reason()));
      lines.add(rl);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("approvalId", a.approvalId());
    payload.put("receiverService", receipts.sourceSystem());
    payload.put("receiptSeq", 1);
    payload.put("result", a.result());
    payload.put("domainRef", SearchIndex.SYNONYM_SET + "@v" + a.version());
    payload.put("reason", applied ? null : a.reason());
    payload.put("lines", lines);
    receipts.send(a.approvalId(), payload);
  }

  private State toState(JsonNode d) {
    List<Rule> rules = new ArrayList<>();
    for (JsonNode r : d.path("rules")) {
      List<String> terms = new ArrayList<>();
      r.path("terms").forEach(t -> terms.add(t.asString()));
      rules.add(new Rule(r.path("id").asString(), terms));
    }
    String at = d.path("at").asString(null);
    return new State(
        d.path("version").asLong(0),
        rules,
        d.path("approvalId").asString(null),
        at == null ? null : Instant.parse(at));
  }

  private Application toApplication(JsonNode d) {
    Map<String, String> reasons = new LinkedHashMap<>();
    d.path("lineReasons")
        .properties()
        .forEach(e -> reasons.put(e.getKey(), e.getValue().asString()));
    return new Application(
        d.path("approvalId").asString(),
        d.path("changeRequestId").asString(null),
        d.path("approverId").asString(null),
        d.path("result").asString(),
        d.path("reason").asString(null),
        d.hasNonNull("baseVersion") ? d.get("baseVersion").asLong() : null,
        d.path("version").asLong(0),
        reasons,
        Instant.parse(d.path("at").asString()));
  }
}
