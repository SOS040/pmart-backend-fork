package com.pmart.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.assistant.application.AssistantProperties;
import com.pmart.assistant.application.KnowledgeJobs;
import com.pmart.assistant.infrastructure.AnswerStore;
import com.pmart.assistant.infrastructure.SnapshotStore;
import com.pmart.common.test.TestJwt;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** 질의 흐름: 정상·분류·NO_GROUNDING·422·503·멱등·lease·권한 재검사·검색 범위·이력 질문·원문 미저장·보존. */
class PolicyQuestionFlowIT extends AssistantITBase {

  @Autowired AnswerStore answers;
  @Autowired SnapshotStore snapshots;
  @Autowired KnowledgeJobs jobs;
  @Autowired AssistantProperties props;

  static String key() {
    return UUID.randomUUID().toString();
  }

  static List<String> paths(Http r) {
    List<String> out = new ArrayList<>();
    r.body().path("citations").forEach(c -> out.add(c.path("path").asString()));
    return out;
  }

  // ---- 정상 ----

  @Test
  void answersWithCitationsPinnedToActiveSnapshot() {
    ingest();
    String snapshot = activeSnapshot();
    Http r = ask(operator("op-1"), key(), "봉투값은 주문당 얼마인가요?");
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.text("state")).isEqualTo("ANSWERED");
    assertThat(r.text("answerMode")).isEqualTo("BASELINE_EXTRACTIVE");
    assertThat(r.text("retrievalMode")).isEqualTo("BASELINE_HASH");
    assertThat(r.text("indexSnapshotId")).isEqualTo(snapshot);
    assertThat(r.body().path("model").path("embedding").asString())
        .isEqualTo("local-hash-ngram@1/384");
    JsonNode first = r.body().path("citations").get(0);
    assertThat(first.path("path").asString()).isEqualTo("docs/business/policy.md");
    assertThat(first.path("section").asString()).isEqualTo("1. 봉투");
    assertThat(first.path("authority").asString()).isEqualTo("CONFIRMED");
    assertThat(first.path("documentVersion").asString()).isEqualTo("1.0");
    assertThat(first.path("asOf").path("snapshotId").asString()).isEqualTo(snapshot);
    assertThat(first.path("asOf").path("sourceDirty").asBoolean())
        .isTrue(); // 임시 저장소는 git 아님 → 재현 보장 없음
    assertThat(first.path("excerpt").asString()).contains("전체 취소 시 봉투값을 전액 환불한다.");
    assertThat(r.text("answer")).contains("[확정]").contains("봉투값은 주문당 100원 1회다.");
    // 질문 원문 저장(store-question=true — 2026-11-19 사용자 "응 해줘", 운영자 본인 질문 목록용)
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM policy_answer WHERE question IS NOT NULL", Integer.class))
        .isPositive();
  }

  @Test
  void realtimeAndActionQuestionsNeedNoIndex() {
    Http rt = ask(operator("op-1"), key(), "ORD-123 지금 어디까지 왔어?");
    assertThat(rt.status()).isEqualTo(200);
    assertThat(rt.text("state")).isEqualTo("REALTIME_DATA_REQUIRED");
    assertThat(rt.text("retrievalMode")).isEqualTo("NONE");
    Http act = ask(operator("op-1"), key(), "CLM-9 환불해");
    assertThat(act.text("state")).isEqualTo("OUT_OF_SCOPE");
    assertThat(act.body().path("citations").size()).isZero();
  }

  @Test
  void unrelatedQuestionIsNoGrounding() {
    ingest();
    Http r = ask(operator("op-1"), key(), "우주 정거장 도킹 허가 절차");
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.text("state")).isEqualTo("NO_GROUNDING");
    assertThat(r.text("answerMode")).isEqualTo("NONE");
  }

  // ---- 422 / 503 (완료 결과 없음, 같은 키 재시도) ----

  @Test
  void noActiveIndexIs422ThenRetrySucceeds() {
    String k = key();
    Http r = ask(operator("op-1"), k, "봉투값은 얼마인가요?");
    assertThat(r.status()).isEqualTo(422);
    assertThat(r.text("code")).isEqualTo("CONFIG_REQUIRED");
    assertThat(answers.findAllBySubject("op-1"))
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.state()).isEqualTo(AnswerStore.State.FAILED_RETRYABLE);
              assertThat(a.lastErrorCode()).isEqualTo("CONFIG_REQUIRED");
            });
    Http lookup = findByRequest(operator("op-1"), k, "backoffice-web");
    assertThat(lookup.status()).isEqualTo(409);
    assertThat(lookup.body().path("retryable").asBoolean()).isTrue();
    ingest();
    Http retry = ask(operator("op-1"), k, "봉투값은 얼마인가요?");
    assertThat(retry.status()).isEqualTo(200);
    assertThat(answers.findAllBySubject("op-1").getFirst().attempt()).isEqualTo(2);
  }

  @Test
  void dependencyFailureIs503WithoutStoredResultThenRetrySucceeds() {
    ingest();
    embedding.failing.set(true);
    String k = key();
    Http r = ask(operator("op-1"), k, "봉투값은 얼마인가요?");
    assertThat(r.status()).isEqualTo(503);
    assertThat(r.text("code")).isEqualTo("DEPENDENCY_UNAVAILABLE");
    assertThat(r.body().path("retryable").asBoolean()).isTrue();
    var row = answers.findAllBySubject("op-1").getFirst();
    assertThat(row.state()).isEqualTo(AnswerStore.State.FAILED_RETRYABLE);
    assertThat(row.answer()).isNull();
    embedding.failing.set(false);
    Http retry = ask(operator("op-1"), k, "봉투값은 얼마인가요?");
    assertThat(retry.status()).isEqualTo(200);
    assertThat(retry.text("answerId")).isEqualTo(row.answerId());
  }

  // ---- 멱등 ----

  @Test
  void sameKeyReplaysAndDifferentQuestionOrContextConflicts() {
    ingest();
    String k = key();
    Http first =
        ask(operator("op-1"), k, "봉투값은 얼마인가요?", Map.of("screen", "orders"), "backoffice-web");
    Http again =
        ask(
            operator("op-1"),
            k,
            "  봉투값은   얼마인가요? ",
            Map.of("screen", "orders", "ignored", "x"),
            "backoffice-web");
    assertThat(again.status()).isEqualTo(200);
    assertThat(again.text("answerId")).isEqualTo(first.text("answerId"));
    assertThat(ask(operator("op-1"), k, "분할 배송 기준은?").status()).isEqualTo(409);
    Http ctx =
        ask(operator("op-1"), k, "봉투값은 얼마인가요?", Map.of("screen", "claims"), "backoffice-web");
    assertThat(ctx.status()).isEqualTo(409);
    assertThat(ctx.text("code")).isEqualTo("IDEMPOTENCY_CONFLICT");
  }

  @Test
  void lostResponseIsRecoveredByRequestIdOnlyWithinScope() {
    ingest();
    String k = key();
    Http first = ask(operator("op-1"), k, "봉투값은 얼마인가요?");
    Http found = findByRequest(operator("op-1"), k, "backoffice-web");
    assertThat(found.status()).isEqualTo(200);
    assertThat(found.text("answerId")).isEqualTo(first.text("answerId"));
    assertThat(findByRequest(operator("op-2"), k, "backoffice-web").status()).isEqualTo(404);
    assertThat(getAnswer(operator("op-2"), first.text("answerId")).status()).isEqualTo(404);
    // 다른 출처: 토큰 src 와 다르면 403, src 없는 토큰은 backoffice-web 만
    assertThat(findByRequest(operator("op-1"), k, "ops-console").status()).isEqualTo(403);
    assertThat(findByRequest(operatorWithoutSrc("op-1"), k, "backoffice-web").status())
        .isEqualTo(200);
    assertThat(findByRequest(operatorWithoutSrc("op-1"), k, "ops-console").status()).isEqualTo(403);
    assertThat(ask(operatorWithoutSrc("op-1"), key(), "봉투값?", null, "ops-console").status())
        .isEqualTo(403);
    // 다른 출처 토큰(src=ops-console)은 같은 키라도 다른 범위 → 404
    String opsToken = TestJwt.token("op-1", "OPERATOR", List.of("OPS"), List.of(), "ops-console");
    assertThat(findByRequest(opsToken, k, "ops-console").status()).isEqualTo(404);
  }

  @Test
  void customersAndRidersAreForbidden() {
    assertThat(ask(TestJwt.customer("c-1"), key(), "봉투값?", null, "customer-app").status())
        .isEqualTo(403);
    assertThat(ask(TestJwt.rider("r-1"), key(), "봉투값?", null, "rider-app").status()).isEqualTo(403);
    assertThat(getAnswer(TestJwt.customer("c-1"), "ANS-x").status()).isEqualTo(403);
  }

  // ---- lease ----

  @Test
  void processingKeyReturns202ThenExpiredLeaseIs409OnGetAndReclaimedByPost() {
    ingest();
    String k = key();
    String hash = com.pmart.assistant.domain.RequestHashing.requestHash("봉투값은 얼마인가요?", null, null);
    Instant now = Instant.now();
    answers.insertClaim(
        "ANS-held", "backoffice-web", "op-1", k, hash, now.plusSeconds(60), null, now);

    Http concurrent = ask(operator("op-1"), k, "봉투값은 얼마인가요?");
    assertThat(concurrent.status()).isEqualTo(202);
    assertThat(concurrent.text("state")).isEqualTo("PROCESSING");
    assertThat(concurrent.headers().getFirst("Retry-After")).isEqualTo("2");
    assertThat(findByRequest(operator("op-1"), k, "backoffice-web").status()).isEqualTo(202);
    assertThat(getAnswer(operator("op-1"), "ANS-held").status()).isEqualTo(202);

    jdbc.update(
        "UPDATE policy_answer SET lease_until = ? WHERE answer_id = 'ANS-held'",
        Timestamp.from(now.minusSeconds(1)));
    Http expired = findByRequest(operator("op-1"), k, "backoffice-web");
    assertThat(expired.status()).isEqualTo(409);
    assertThat(expired.text("code")).isEqualTo("INVALID_STATE");
    assertThat(expired.body().path("retryable").asBoolean()).isTrue();

    Http reclaimed = ask(operator("op-1"), k, "봉투값은 얼마인가요?");
    assertThat(reclaimed.status()).isEqualTo(200);
    assertThat(reclaimed.text("answerId")).isEqualTo("ANS-held");
    assertThat(answers.findById("ANS-held").orElseThrow().attempt()).isEqualTo(2);
  }

  @Test
  void concurrentSameKeyRequestsProduceOneAnswer() throws Exception {
    ingest();
    String k = key();
    var a = CompletableFuture.supplyAsync(() -> ask(operator("op-1"), k, "분할 배송 기준은 몇 g 인가요?"));
    var b = CompletableFuture.supplyAsync(() -> ask(operator("op-1"), k, "분할 배송 기준은 몇 g 인가요?"));
    Http ra = a.get(30, TimeUnit.SECONDS);
    Http rb = b.get(30, TimeUnit.SECONDS);
    assertThat(List.of(ra.status(), rb.status())).allMatch(s -> s == 200 || s == 202).contains(200);
    assertThat(ra.text("answerId")).isEqualTo(rb.text("answerId"));
    assertThat(answers.findAllBySubject("op-1")).hasSize(1);
  }

  @Test
  void workerThatLostItsLeaseCannotOverwrite() {
    ingest();
    Instant now = Instant.now();
    answers.insertClaim(
        "ANS-race",
        "backoffice-web",
        "op-1",
        key(),
        "h".repeat(64),
        now.minusSeconds(1),
        null,
        now.minusSeconds(130));
    // 다른 작업자가 만료 lease 를 재선점 → attempt 2
    assertThat(answers.reclaim("ANS-race", 1, now.plusSeconds(120), now)).isTrue();
    // 늦은 작업자(attempt 1)의 스냅숏 고정·완료·실패는 모두 0건
    assertThat(answers.pinActiveSnapshot("ANS-race", 1, snapshots))
        .isInstanceOf(AnswerStore.PinResult.LeaseLost.class);
    assertThat(
            answers.complete(
                "ANS-race",
                1,
                new AnswerStore.Completion(
                    "ANSWERED", "stale", "[]", null, "NONE", "NONE", null, null),
                now))
        .isFalse();
    assertThat(answers.fail("ANS-race", 1, "DEPENDENCY_UNAVAILABLE")).isFalse();
    var row = answers.findById("ANS-race").orElseThrow();
    assertThat(row.state()).isEqualTo(AnswerStore.State.PROCESSING);
    assertThat(row.attempt()).isEqualTo(2);
    assertThat(row.snapshotId()).isNull();
    assertThat(row.answer()).isNull();
    // 현재 작업자(attempt 2)는 고정된다
    assertThat(answers.pinActiveSnapshot("ANS-race", 2, snapshots))
        .isInstanceOf(AnswerStore.PinResult.Pinned.class);
    // 늦은 작업자가 저장 상태로 응답할 때: 유효 lease PROCESSING → 202
    assertThat(getAnswer(operator("op-1"), "ANS-race").status()).isEqualTo(202);
  }

  // ---- 권한 재검사(모든 저장 답변 반환 경로) ----

  @Test
  void currentPermissionIsRecheckedOnEveryStoredAnswerPath() {
    ingest();
    String k = key();
    Http first = ask(operator("op-1"), k, "봉투값은 얼마인가요?");
    String answerId = first.text("answerId");
    // 근거 문서의 접근 범위가 운영자 밖으로 바뀐 상황(권한 상실)
    jdbc.update(
        "UPDATE policy_answer SET citations = regexp_replace(citations::text, '\"accessScope\":\\s*\"operator\"', '\"accessScope\": \"hq-only\"', 'g')::jsonb WHERE answer_id = ?",
        answerId);
    assertThat(
            jdbc.queryForObject(
                "SELECT citations::text FROM policy_answer WHERE answer_id = ?",
                String.class,
                answerId))
        .contains("hq-only")
        .doesNotContain("\"operator\"");
    assertThat(getAnswer(operator("op-1"), answerId).status()).isEqualTo(404);
    assertThat(findByRequest(operator("op-1"), k, "backoffice-web").status()).isEqualTo(404);
    assertThat(ask(operator("op-1"), k, "봉투값은 얼마인가요?").status()).isEqualTo(404); // POST 멱등 재응답
    // 운영자 아님 → 403(같은 subject 라도)
    assertThat(
            getAnswer(
                    TestJwt.token("op-1", "CUSTOMER", List.of(), List.of(), "backoffice-web"),
                    answerId)
                .status())
        .isEqualTo(403);
  }

  @Test
  void completedAnswerSurvivesSnapshotPruneWithPermissionRecheck() {
    ingest();
    Http first = ask(operator("op-1"), key(), "봉투값은 얼마인가요?");
    String oldSnapshot = first.text("indexSnapshotId");
    ingest(); // 새 스냅숏 활성, 이전 것은 RETIRED
    jdbc.update(
        "UPDATE index_snapshot SET retired_at = ? WHERE snapshot_id = ?",
        Timestamp.from(Instant.now().minus(Duration.ofDays(1))),
        oldSnapshot);
    assertThat(jobs.pruneSnapshots()).isEqualTo(1);
    assertThat(snapshots.find(oldSnapshot)).isEmpty();
    Http again = getAnswer(operator("op-1"), first.text("answerId"));
    assertThat(again.status()).isEqualTo(200);
    assertThat(again.body().path("citations").get(0).path("asOf").path("snapshotId").asString())
        .isEqualTo(oldSnapshot);
  }

  // ---- 검색 범위·생애 ----

  @Test
  void outOfScopeDocumentsAreNeverRetrievedOrCited() {
    ingest();
    Http r = ask(operator("op-1"), key(), "특별 정산 비율은 매출의 몇 퍼센트인가요?");
    assertThat(r.status()).isEqualTo(200);
    assertThat(paths(r)).doesNotContain("docs/secret/hq.md");
    assertThat(r.text("answer")).doesNotContain("7퍼센트");
  }

  @Test
  void supersessionProposalCompanionAlsoRespectsAccessScope() {
    ingest();
    Http r = ask(operator("op-1"), key(), "고객 분류 카테고리는 몇 단계로 구성하나요?");
    assertThat(r.text("state")).isEqualTo("ANSWERED");
    JsonNode cat = null;
    for (JsonNode c : r.body().path("citations")) {
      if (c.path("excerpt").asString().contains("고객 분류는 1·2단계")) {
        cat = c;
      }
    }
    assertThat(cat).isNotNull();
    assertThat(cat.path("authority").asString()).isEqualTo("CONFIRMED");
    assertThat(cat.path("lifecycleStatus").asString()).isEqualTo("SUPERSESSION_PROPOSED");
    assertThat(cat.path("proposedSupersededBy").get(0).asString())
        .isEqualTo("docs/adr/0003-category.md");
    assertThat(paths(r)).doesNotContain("docs/adr/0003-category.md"); // 제안 문서는 hq-only — 동반 인용 안 됨
  }

  @Test
  void currentQuestionsSkipSupersededHistoryQuestionsIncludeOnlySuperseded() {
    ingest();
    Http current = ask(operator("op-1"), key(), "서비스 토큰 발급은 누가 하나요?");
    for (JsonNode c : current.body().path("citations")) {
      assertThat(c.path("lifecycleStatus").asString())
          .isNotIn("SUPERSEDED", "EXCLUDED_FROM_PHASE1");
    }
    Http history = ask(operator("op-1"), key(), "예전에는 서비스 토큰 발급을 누가 했어?");
    List<String> lifecycles = new ArrayList<>();
    history
        .body()
        .path("citations")
        .forEach(c -> lifecycles.add(c.path("lifecycleStatus").asString()));
    assertThat(lifecycles).contains("SUPERSEDED").doesNotContain("EXCLUDED_FROM_PHASE1");
    assertThat(history.text("answer")).contains("[대체됨 → docs/adr/0002-no-service-token.md]");
  }

  @Test
  void phase1ExcludedMaterialIsExcludedEvenForHistoryQuestions() {
    ingest();
    for (String q : List.of("쿠폰 중복 사용은 몇 개까지 허용하나요?", "예전에는 쿠폰 중복 사용을 몇 개까지 허용했어?")) {
      Http r = ask(operator("op-1"), key(), q);
      assertThat(r.text("answer")).as(q).doesNotContain("장바구니 쿠폰 하나만");
    }
  }

  @Test
  void implementationEvidenceIsReportedWithRecordDate() {
    ingest();
    Http r = ask(operator("op-1"), key(), "결제 확정 뒤 주문 확정 이벤트를 발행하나요?");
    JsonNode impl = null;
    for (JsonNode c : r.body().path("citations")) {
      if (!c.path("implementationEvidence").isNull()
          && !c.path("implementationEvidence").isMissingNode()) {
        impl = c;
      }
    }
    assertThat(impl).isNotNull();
    assertThat(impl.path("implementationEvidence").path("recordDate").asString())
        .isEqualTo("2026-10-31");
    assertThat(impl.path("authority").asString()).isEqualTo("CONFIRMED");
    assertThat(r.text("answer")).contains("2026-10-31 기준 구현 기록 — 현재 상태는 이 기록만으로 확인할 수 없음");
  }

  // ---- 내 질문 목록(BR-46) ----

  @Test
  void questionHistoryIsOwnScopeNewestFirstPagedAndPermissionRechecked() {
    ingest();
    Http a = ask(operator("op-h"), key(), "봉투값은 얼마인가요?");
    ask(operator("op-h"), key(), "ORD-123 지금 어디까지 왔어?");
    Http c = ask(operator("op-h"), key(), "분할 배송 기준은 몇 g 인가요?");
    ask(operator("op-other"), key(), "봉투값은 얼마인가요?");

    Http first = history(operator("op-h"), "backoffice-web", "?size=2");
    assertThat(first.status()).isEqualTo(200);
    assertThat(first.body().path("items")).hasSize(2);
    assertThat(first.body().path("items").get(0).path("answerId").asString())
        .isEqualTo(c.text("answerId"));
    assertThat(first.body().path("items").get(1).path("state").asString())
        .isEqualTo("REALTIME_DATA_REQUIRED");
    // 질문 원문 저장(설정 store-question=true — 2026-11-19 사용자 "응 해줘", 백오피스 질문 목록)
    assertThat(first.body().path("items").get(0).path("question").asString()).isNotBlank();
    assertThat(first.body().path("items").get(0).path("answerPreview").asString()).isNotBlank();
    String cursor = first.body().path("page").path("nextCursor").asString();
    Http second = history(operator("op-h"), "backoffice-web", "?size=2&cursor=" + cursor);
    assertThat(second.body().path("items")).hasSize(1);
    assertThat(second.body().path("items").get(0).path("answerId").asString())
        .isEqualTo(a.text("answerId"));
    assertThat(second.body().path("page").path("nextCursor").isNull()).isTrue();

    // 출처가 다르면 403, 다른 운영자 답변은 안 보인다
    assertThat(history(operator("op-h"), "ops-console", "").status()).isEqualTo(403);
    assertThat(history(operator("op-other"), "backoffice-web", "").body().path("items")).hasSize(1);
    // 권한이 사라진 완료 답변은 목록에서도 빠진다
    jdbc.update(
        "UPDATE policy_answer SET citations = regexp_replace(citations::text, '\"accessScope\":\\s*\"operator\"', '\"accessScope\": \"hq-only\"', 'g')::jsonb WHERE answer_id = ?",
        a.text("answerId"));
    assertThat(history(operator("op-h"), "backoffice-web", "").body().path("items")).hasSize(2);
  }

  /**
   * BR-46 페이지 회귀(사례 A·B·C·D, 2026-11-05): 최근 답변 A 의 권한이 사라졌을 때 size 2 로 넘기면 [B, C] → [D] 로 끝까지 보이고,
   * 마지막 쪽에만 nextCursor 가 없다. 고치기 전에는 1쪽 뒤 nextCursor 가 없어 D 가 빠졌다.
   */
  @Test
  void questionHistoryKeepsPagingWhenTheNewestAnswerIsHidden() {
    ingest();
    Http d = ask(operator("op-p"), key(), "분할 배송 기준은 몇 g 인가요?");
    Http c = ask(operator("op-p"), key(), "봉투값은 얼마인가요?");
    Http b = ask(operator("op-p"), key(), "ORD-123 지금 어디까지 왔어?");
    Http a = ask(operator("op-p"), key(), "봉투값은 주문당 얼마인가요?");
    jdbc.update(
        "UPDATE policy_answer SET citations = regexp_replace(citations::text, '\"accessScope\":\\s*\"operator\"', '\"accessScope\": \"hq-only\"', 'g')::jsonb WHERE answer_id = ?",
        a.text("answerId"));

    Http first = history(operator("op-p"), "backoffice-web", "?size=2");
    assertThat(first.body().path("items")).hasSize(2);
    assertThat(first.body().path("items").get(0).path("answerId").asString())
        .isEqualTo(b.text("answerId"));
    assertThat(first.body().path("items").get(1).path("answerId").asString())
        .isEqualTo(c.text("answerId"));
    String cursor = first.body().path("page").path("nextCursor").asString(null);
    assertThat(cursor).as("D 가 남았으므로 커서가 있다").isNotNull();

    Http second = history(operator("op-p"), "backoffice-web", "?size=2&cursor=" + cursor);
    assertThat(second.body().path("items")).hasSize(1);
    assertThat(second.body().path("items").get(0).path("answerId").asString())
        .isEqualTo(d.text("answerId"));
    assertThat(second.body().path("page").path("nextCursor").isNull()).isTrue();
  }

  // ---- 보존 ----

  @Test
  void retentionPurgesOnlyFinishedRowsAndOnlyWhenConfigured() {
    ingest();
    ask(operator("op-1"), key(), "봉투값은 얼마인가요?");
    Instant now = Instant.now();
    answers.insertClaim(
        "ANS-proc",
        "backoffice-web",
        "op-1",
        key(),
        "h".repeat(64),
        now.plusSeconds(60),
        null,
        now);
    jdbc.update(
        "UPDATE policy_answer SET created_at = ?", Timestamp.from(now.minus(Duration.ofDays(40))));
    // 보존 기준은 세션의 마지막 질문 시각(ADR-0084) — 처리 중 답변이 있는 세션은 지우지 않는다
    jdbc.update(
        "UPDATE chat_session SET last_asked_at = ?",
        Timestamp.from(now.minus(Duration.ofDays(40))));
    // 보존 기간은 시험 사본 설정(14d)에 기대지 않고 이 시험에서 직접 정한다 — 끝나면 원래 값으로 돌린다
    Duration configured = props.getAnswers().getRetention();
    try {
      props.getAnswers().setRetention(null);
      jobs.purgeExpiredAnswers(); // 기간 미설정 → 지우지 않는다
      assertThat(answers.findAllBySubject("op-1")).hasSize(2);
      props.getAnswers().setRetention(Duration.ofDays(30));
      jobs.purgeExpiredAnswers();
    } finally {
      props.getAnswers().setRetention(configured);
    }
    assertThat(answers.findAllBySubject("op-1"))
        .extracting(AnswerStore.AnswerRow::answerId)
        .containsExactly("ANS-proc");
  }
}
