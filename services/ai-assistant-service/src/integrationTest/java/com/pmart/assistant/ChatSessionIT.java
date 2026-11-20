package com.pmart.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.assistant.application.AssistantProperties;
import com.pmart.assistant.application.KnowledgeJobs;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * 채팅 세션(ADR-0084, 사용자 2026-11-20 요청 2번): 한 세션에서 여러 번 묻기, 앞 대화 반영(이어지는 질문은 직전 질문을 붙여 검색), 본인 세션만
 * 보기·지우기, 마지막 질문 뒤 보존 기간이 지나면 세션째 지움.
 */
class ChatSessionIT extends AssistantITBase {

  @Autowired KnowledgeJobs jobs;
  @Autowired AssistantProperties props;

  static String key() {
    return UUID.randomUUID().toString();
  }

  Http askIn(String token, @Nullable String sessionId, String question) {
    Map<String, Object> body = new HashMap<>();
    body.put("question", question);
    if (sessionId != null) {
      body.put("sessionId", sessionId);
    }
    return RestClient.create()
        .post()
        .uri("http://localhost:" + port + "/api/v1/assistant/policy-questions")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header("Idempotency-Key", key())
        .header("X-Source-System", "backoffice-web")
        .contentType(MediaType.APPLICATION_JSON)
        .body(json.writeValueAsString(body))
        .exchange((req, res) -> http(res.getStatusCode().value(), res.getBody().readAllBytes()));
  }

  Http call(HttpMethod method, String token, String path) {
    return RestClient.create()
        .method(method)
        .uri("http://localhost:" + port + "/api/v1/assistant" + path)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header("X-Source-System", "backoffice-web")
        .header("Idempotency-Key", key())
        .exchange((req, res) -> http(res.getStatusCode().value(), res.getBody().readAllBytes()));
  }

  Http http(int status, byte[] bytes) {
    return new Http(
        status,
        bytes.length == 0 ? json.createObjectNode() : json.readTree(bytes),
        new HttpHeaders());
  }

  @Test
  void questionsWithoutSessionStartNewSessionAndFollowUpsStayInIt() {
    ingest();
    String me = operator("op-chat");
    Http first = askIn(me, null, "봉투값은 주문당 얼마인가요?");
    assertThat(first.status()).as(first.body().toString()).isEqualTo(200);
    String session = first.text("sessionId");
    assertThat(session).startsWith("SES-");
    assertThat(first.text("question")).isEqualTo("봉투값은 주문당 얼마인가요?");

    // 이어지는 질문: 직전 질문을 붙여 검색한다(앞 대화 반영)
    embedding.queries.clear();
    Http second = askIn(me, session, "그럼 전체 취소하면?");
    assertThat(second.status()).as(second.body().toString()).isEqualTo(200);
    assertThat(second.text("sessionId")).isEqualTo(session);
    assertThat(embedding.queries).anyMatch(q -> q.contains("봉투값") && q.contains("전체 취소"));

    // 주제가 바뀐 완결 질문은 그대로 검색
    embedding.queries.clear();
    askIn(me, session, "무게 10,000g 넘는 주문은 분할 배송하나요?");
    assertThat(embedding.queries).noneMatch(q -> q.contains("봉투값"));

    // 다른 질문은 새 세션
    Http other = askIn(me, null, "고객 분류는 몇 단계인가요?");
    assertThat(other.text("sessionId")).isNotEqualTo(session);

    Http list = call(HttpMethod.GET, me, "/chat-sessions");
    assertThat(list.status()).isEqualTo(200);
    var items = list.body().path("items");
    assertThat(items).hasSize(2);
    assertThat(items.path(0).path("sessionId").asString()).isEqualTo(other.text("sessionId"));
    assertThat(items.path(1).path("questionCount").asInt()).isEqualTo(3);
    assertThat(items.path(1).path("title").asString()).isEqualTo("봉투값은 주문당 얼마인가요?");

    Http view = call(HttpMethod.GET, me, "/chat-sessions/" + session);
    assertThat(view.status()).isEqualTo(200);
    assertThat(view.body().path("messages").findValuesAsString("question"))
        .containsExactly("봉투값은 주문당 얼마인가요?", "그럼 전체 취소하면?", "무게 10,000g 넘는 주문은 분할 배송하나요?");
  }

  @Test
  void onlyOwnerCanUseViewOrDeleteSession() {
    ingest();
    String owner = operator("op-owner");
    String stranger = operator("op-stranger");
    String session = askIn(owner, null, "봉투값은 얼마인가요?").text("sessionId");

    assertThat(askIn(stranger, session, "그럼 환불은?").status()).isEqualTo(404);
    assertThat(call(HttpMethod.GET, stranger, "/chat-sessions/" + session).status()).isEqualTo(404);
    assertThat(call(HttpMethod.DELETE, stranger, "/chat-sessions/" + session).status())
        .isEqualTo(204); // 남의 채팅: 지우지 않고 204(존재를 드러내지 않음)
    assertThat(call(HttpMethod.GET, owner, "/chat-sessions/" + session).status()).isEqualTo(200);
    assertThat(call(HttpMethod.GET, stranger, "/chat-sessions").body().path("items")).isEmpty();

    assertThat(call(HttpMethod.DELETE, owner, "/chat-sessions/" + session).status()).isEqualTo(204);
    assertThat(call(HttpMethod.GET, owner, "/chat-sessions/" + session).status()).isEqualTo(404);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM policy_answer WHERE session_id = ?", Integer.class, session))
        .isZero();
    assertThat(call(HttpMethod.DELETE, owner, "/chat-sessions/" + session).status())
        .isEqualTo(204); // 다시 지워도 204(멱등)
  }

  @Test
  void retentionDeletesSessionsWhoseLastQuestionIsOlderThanRetention() {
    ingest();
    String me = operator("op-retain");
    String old = askIn(me, null, "봉투값은 얼마인가요?").text("sessionId");
    String fresh = askIn(me, null, "고객 분류는 몇 단계인가요?").text("sessionId");
    Instant fifteenDaysAgo = Instant.now().minus(Duration.ofDays(15));
    jdbc.update(
        "UPDATE chat_session SET last_asked_at = ?, created_at = ? WHERE session_id = ?",
        Timestamp.from(fifteenDaysAgo),
        Timestamp.from(fifteenDaysAgo),
        old);

    Duration before = props.getAnswers().getRetention();
    props.getAnswers().setRetention(Duration.ofDays(14));
    try {
      jobs.purgeExpiredAnswers();
    } finally {
      props.getAnswers().setRetention(before);
    }
    assertThat(jdbc.queryForList("SELECT session_id FROM chat_session", String.class))
        .containsExactly(fresh);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM policy_answer WHERE session_id = ?", Integer.class, old))
        .isZero();
  }
}
