package com.pmart.common.messaging;

import com.pmart.common.web.PmartException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 요청 멱등 (ADR-0009 결정 5 "요청" 계층). {@code (X-Source-System, Idempotency-Key)} 로 명령 결과를 기록하고 재요청에 같은
 * 결과를 돌려준다.
 *
 * <ul>
 *   <li>기록과 업무 변경은 같은 로컬 트랜잭션이다(호출자 트랜잭션에 참여하거나 새로 연다). 그래서 "기록은 있는데 변경은 없음"이 생기지 않는다.
 *   <li>같은 키·같은 작업·같은 행위자·같은 본문 → 저장된 결과를 재생({@link Outcome#replayed()}). 업무 처리는 다시 하지 않는다.
 *   <li>같은 키인데 본문·작업·행위자 중 하나라도 다르면 {@code IDEMPOTENCY_CONFLICT}(409). 다른 행위자에게 결과를 보여 주지 않는다.
 *   <li>처리가 예외로 끝나면 기록도 롤백된다. 같은 키로 다시 보내면 다시 처리한다. 업무상 거절을 재생해야 하면 예외 대신 결과(예: {@code
 *       CommandResponse.rejected})로 돌려준다.
 *   <li>같은 키가 동시에 들어오면 뒤 요청은 앞 트랜잭션이 끝날 때까지 기다린 뒤 그 결과를 재생한다(PostgreSQL 유일 키 대기).
 * </ul>
 *
 * <p>본문 비교는 JSON 으로 바꾼 뒤 객체 키를 정렬한 정규형의 SHA-256 이다. 결과는 Jackson 으로 저장·복원하므로 결과 타입은 JSON 왕복이 가능해야
 * 한다.
 */
public class RequestIdempotency {

  /** 요청 식별. {@code actor} 는 JWT subject(없으면 {@link #ANONYMOUS}). */
  public record RequestKey(String sourceSystem, String requestId, String operation, String actor) {
    public RequestKey {
      requireText(sourceSystem, "sourceSystem");
      requireText(requestId, "requestId");
      requireText(operation, "operation");
      requireText(actor, "actor");
    }
  }

  /** 처리 결과와 재생 여부(재생이면 HTTP 201 대신 200 등 호출자가 정한다). */
  public record Outcome<T>(T value, boolean replayed) {}

  public static final String ANONYMOUS = "anonymous";

  private static final String INSERT =
      "INSERT INTO request_record (source_system, request_id, operation, actor, request_hash, result) "
          + "VALUES (?, ?, ?, ?, ?, '{}'::jsonb) ON CONFLICT (source_system, request_id) DO NOTHING";
  private static final String SELECT =
      "SELECT operation, actor, request_hash, result::text AS result FROM request_record "
          + "WHERE source_system = ? AND request_id = ?";
  private static final String UPDATE =
      "UPDATE request_record SET result = ?::jsonb WHERE source_system = ? AND request_id = ?";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final ObjectMapper mapper;

  public RequestIdempotency(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.tx = Objects.requireNonNull(tx);
    this.mapper = Objects.requireNonNull(mapper);
  }

  /**
   * @param request 요청 본문(비교용). 경로 변수처럼 본문 밖의 입력도 결과를 바꾸면 함께 담는다
   * @param resultType 재생 시 복원할 타입
   * @param action 처리. 이 트랜잭션 안에서 실행된다
   */
  public <T> Outcome<T> execute(
      RequestKey key, @Nullable Object request, Class<T> resultType, Supplier<T> action) {
    String hash = hash(request);
    Outcome<T> outcome =
        tx.execute(
            status -> {
              int inserted =
                  jdbc.update(
                      INSERT,
                      key.sourceSystem(),
                      key.requestId(),
                      key.operation(),
                      key.actor(),
                      hash);
              if (inserted == 0) {
                return replay(key, hash, resultType);
              }
              T value = Objects.requireNonNull(action.get(), "멱등 처리 결과는 null 일 수 없습니다");
              jdbc.update(
                  UPDATE, mapper.writeValueAsString(value), key.sourceSystem(), key.requestId());
              return new Outcome<>(value, false);
            });
    return Objects.requireNonNull(outcome);
  }

  private <T> Outcome<T> replay(RequestKey key, String hash, Class<T> resultType) {
    Map<String, Object> row = jdbc.queryForMap(SELECT, key.sourceSystem(), key.requestId());
    if (!key.operation().equals(row.get("operation"))
        || !key.actor().equals(row.get("actor"))
        || !hash.equals(row.get("request_hash"))) {
      // 무엇이 달랐는지는 알려 주지 않는다(다른 행위자의 요청 정보 노출 방지)
      throw PmartException.idempotencyConflict(
          "같은 Idempotency-Key 로 다른 요청이 이미 처리되었습니다. 새 키를 사용하세요.");
    }
    return new Outcome<>(mapper.readValue((String) row.get("result"), resultType), true);
  }

  /** 정규형(객체 키 정렬) JSON 의 SHA-256. */
  String hash(@Nullable Object request) {
    StringBuilder sb = new StringBuilder();
    canonical(request == null ? mapper.nullNode() : mapper.valueToTree(request), sb);
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);
    }
  }

  private void canonical(JsonNode node, StringBuilder sb) {
    if (node.isObject()) {
      List<String> names = new ArrayList<>(node.propertyNames());
      names.sort(null);
      sb.append('{');
      for (int i = 0; i < names.size(); i++) {
        if (i > 0) {
          sb.append(',');
        }
        sb.append(mapper.writeValueAsString(names.get(i))).append(':');
        canonical(node.get(names.get(i)), sb);
      }
      sb.append('}');
    } else if (node.isArray()) {
      sb.append('[');
      int i = 0;
      for (JsonNode child : node) {
        if (i++ > 0) {
          sb.append(',');
        }
        canonical(child, sb);
      }
      sb.append(']');
    } else {
      sb.append(mapper.writeValueAsString(node));
    }
  }

  private static void requireText(@Nullable String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " 가 필요합니다");
    }
  }
}
