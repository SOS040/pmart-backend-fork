package com.pmart.payment.infrastructure.toss;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 토스페이먼츠 결제 API(결제 명세 §7.2·§7.3). 승인·조회(paymentKey)·취소만 쓴다. {@code GET
 * /v1/payments/orders/{orderId}} 는 결제위젯 키로 부를 수 없어 쓰지 않는다.
 *
 * <ul>
 *   <li>{@link Result.Ok}: 2xx 와 Payment 객체.
 *   <li>{@link Result.Rejected}: 토스의 명확한 거절(4xx, 처리 중 409 제외).
 *   <li>{@link Result.Unknown}: 타임아웃·연결 오류·5xx·{@code 409 IDEMPOTENT_REQUEST_PROCESSING} — 원거래 조회로만
 *       해소.
 * </ul>
 *
 * Authorization 헤더·시크릿 키는 로그에 남기지 않는다.
 */
public class TossClient {

  private static final Logger log = LoggerFactory.getLogger(TossClient.class);

  /** 토스 Payment 객체에서 쓰는 값(§7.4). */
  public record TossPayment(
      @Nullable String paymentKey,
      @Nullable String orderId,
      @Nullable String status,
      @Nullable Long totalAmount,
      @Nullable Long balanceAmount,
      @Nullable String currency,
      @Nullable String method,
      @Nullable Instant approvedAt,
      @Nullable Boolean isPartialCancelable,
      List<TossCancel> cancels) {}

  public record TossCancel(
      @Nullable String transactionKey,
      long cancelAmount,
      @Nullable Instant canceledAt,
      @Nullable String cancelStatus) {}

  public sealed interface Result {
    record Ok(int httpStatus, TossPayment payment) implements Result {}

    record Rejected(int httpStatus, String code, String message) implements Result {}

    record Unknown(int httpStatus, String reason) implements Result {}
  }

  private final RestClient http;
  private final ObjectMapper mapper;

  public TossClient(TossProperties props, ObjectMapper mapper) {
    this.mapper = mapper;
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            // HTTP/1.1 고정: http:// 에서 JDK 클라이언트의 h2c 업그레이드 시도를 일부 서버가 끊는다(EOF)
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.connectTimeout())
                .build());
    factory.setReadTimeout(props.readTimeout());
    String auth =
        props.secretKey() == null
            ? ""
            : "Basic "
                + Base64.getEncoder()
                    .encodeToString((props.secretKey() + ":").getBytes(StandardCharsets.UTF_8));
    this.http =
        RestClient.builder()
            .baseUrl(props.baseUrl())
            .requestFactory(factory)
            .defaultHeader("Authorization", auth)
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
  }

  public Result confirm(String paymentKey, String orderId, long amount, String idempotencyKey) {
    return call(
        "confirm",
        () ->
            http.post()
                .uri("/v1/payments/confirm")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount))
                .retrieve()
                .toEntity(String.class));
  }

  public Result get(String paymentKey) {
    return call(
        "get",
        () -> http.get().uri("/v1/payments/{k}", paymentKey).retrieve().toEntity(String.class));
  }

  public Result cancel(
      String paymentKey,
      String reason,
      long cancelAmount,
      @Nullable Long refundableAmount,
      String idempotencyKey) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("cancelReason", reason);
    body.put("cancelAmount", cancelAmount);
    if (refundableAmount != null) {
      body.put("refundableAmount", refundableAmount);
    }
    return call(
        "cancel",
        () ->
            http.post()
                .uri("/v1/payments/{k}/cancel", paymentKey)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class));
  }

  private Result call(
      String op, java.util.function.Supplier<org.springframework.http.ResponseEntity<String>> s) {
    org.springframework.http.ResponseEntity<String> r;
    try {
      r = s.get();
    } catch (ResourceAccessException e) {
      Throwable root = e.getMostSpecificCause();
      log.warn(
          "toss {} 결과 불명(연결·타임아웃): {} {}", op, root.getClass().getSimpleName(), root.getMessage());
      return new Result.Unknown(0, "IO:" + root.getClass().getSimpleName());
    }
    int status = r.getStatusCode().value();
    JsonNode body = r.getBody() == null ? mapper.createObjectNode() : mapper.readTree(r.getBody());
    if (r.getStatusCode().is2xxSuccessful()) {
      return new Result.Ok(status, payment(body));
    }
    String code = body.path("code").asString("UNKNOWN");
    String message = body.path("message").asString("");
    if (status >= 500 || (status == 409 && "IDEMPOTENT_REQUEST_PROCESSING".equals(code))) {
      log.warn("toss {} 결과 불명 http={} code={}", op, status, code);
      return new Result.Unknown(status, code);
    }
    log.info("toss {} 거절 http={} code={}", op, status, code);
    return new Result.Rejected(status, code, message);
  }

  TossPayment payment(JsonNode n) {
    List<TossCancel> cancels = new ArrayList<>();
    for (JsonNode c : n.path("cancels")) {
      cancels.add(
          new TossCancel(
              text(c, "transactionKey"),
              c.path("cancelAmount").asLong(0),
              instant(c, "canceledAt"),
              text(c, "cancelStatus")));
    }
    return new TossPayment(
        text(n, "paymentKey"),
        text(n, "orderId"),
        text(n, "status"),
        n.hasNonNull("totalAmount") ? n.get("totalAmount").asLong() : null,
        n.hasNonNull("balanceAmount") ? n.get("balanceAmount").asLong() : null,
        text(n, "currency"),
        text(n, "method"),
        instant(n, "approvedAt"),
        n.hasNonNull("isPartialCancelable") ? n.get("isPartialCancelable").asBoolean() : null,
        cancels);
  }

  private static @Nullable String text(JsonNode n, String f) {
    return n.hasNonNull(f) ? n.get(f).asString() : null;
  }

  private static @Nullable Instant instant(JsonNode n, String f) {
    return n.hasNonNull(f) ? OffsetDateTime.parse(n.get(f).asString()).toInstant() : null;
  }
}
