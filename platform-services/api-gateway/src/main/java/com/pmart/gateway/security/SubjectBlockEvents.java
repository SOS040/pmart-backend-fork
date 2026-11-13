package com.pmart.gateway.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code SubjectBlocked} 사건(토픽 {@code platform.subject-block.v1}, 계약 {@code
 * contracts/events/SubjectBlocked.v1.json}) → Redis 차단 목록. Spring Cloud Stream 함수 {@code
 * subjectBlocks}.
 *
 * <p>게이트웨이는 DB 가 없어 common-messaging(inbox·dead_letter 표)을 쓰지 않는다. 대신 쓰기가 멱등이다 — 같은 사건을 다시 받거나 순서가
 * 섞여도 "더 늦은 차단 시각만 남김"이라 결과가 같다. 남길 기간 = 차단 시각 + 가장 긴 access token 수명 − 지금. 그래서 토픽을 처음부터 다시
 * 읽어도(Redis 를 잃었을 때) 이미 지난 차단은 다시 생기지 않는다.
 *
 * <p>Redis 에 쓰지 못하면 예외 → 바인더가 재시도하고 그래도 안 되면 {@code platform.subject-block.v1.DLT}. 계약에 어긋난 사건은
 * {@link IllegalArgumentException}(재시도 없이 DLT).
 */
public class SubjectBlockEvents implements Consumer<Message<byte[]>> {

  private static final Logger log = LoggerFactory.getLogger(SubjectBlockEvents.class);
  static final String EVENT_TYPE = "SubjectBlocked";

  private final SubjectBlocklist blocklist;
  private final Duration maxTokenLifetime;
  private final Duration writeTimeout;
  private final Clock clock;
  private final JsonMapper mapper = JsonMapper.builder().build();

  public SubjectBlockEvents(
      SubjectBlocklist blocklist, Duration maxTokenLifetime, Duration writeTimeout, Clock clock) {
    this.blocklist = blocklist;
    this.maxTokenLifetime = maxTokenLifetime;
    this.writeTimeout = writeTimeout;
    this.clock = clock;
  }

  @Override
  public void accept(Message<byte[]> message) {
    JsonNode envelope;
    try {
      envelope = mapper.readTree(new String(message.getPayload(), StandardCharsets.UTF_8));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("SubjectBlocked 봉투를 읽지 못했습니다", e);
    }
    if (!EVENT_TYPE.equals(envelope.path("eventType").asString(""))) {
      return; // 이 토픽의 다른 사건(지금은 없음)은 다루지 않는다
    }
    JsonNode p = envelope.path("payload");
    String issuer = required(p, "issuer");
    String subject = required(p, "subject");
    Instant blockedBefore;
    try {
      blockedBefore = Instant.parse(required(p, "blockedBefore"));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("SubjectBlocked.blockedBefore 형식이 잘못되었습니다", e);
    }
    Duration ttl = Duration.between(clock.instant(), blockedBefore.plus(maxTokenLifetime));
    // sessionId 가 있으면 그 세션의 토큰만(로그아웃·refresh 재사용), 없으면 그 주체의 모든 토큰
    String sessionId = p.path("sessionId").asString("");
    Boolean written =
        (sessionId.isBlank()
                ? blocklist.record(issuer, subject, blockedBefore, ttl)
                : blocklist.recordSession(issuer, sessionId, blockedBefore, ttl))
            .block(writeTimeout);
    // 주체 ID 는 로그에 남기지 않는다 — 사건 ID 로 찾는다
    log.info(
        "subject block event eventId={} scope={} userType={} reason={} issuer={} written={} ttlSeconds={}",
        envelope.path("eventId").asString(""),
        sessionId.isBlank() ? "SUBJECT" : "SESSION",
        p.path("userType").asString(""),
        p.path("reason").asString(""),
        issuer,
        written,
        Math.max(0, ttl.toSeconds()));
  }

  private static String required(JsonNode payload, String field) {
    String v = payload.path(field).asString("");
    if (v.isBlank()) {
      throw new IllegalArgumentException("SubjectBlocked." + field + " 가 없습니다");
    }
    return v;
  }
}
