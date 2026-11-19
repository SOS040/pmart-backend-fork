package com.pmart.auth.infrastructure;

import com.pmart.auth.application.CustomerProperties;
import com.pmart.common.web.Downstream;
import com.pmart.common.web.PmartException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * customer-service 가입·로그인 단계 휴대폰 확인 내부 경로(2026-11-20 ADR-0074, customer-service.yaml 0.10.0). 번호
 * 정본은 customer — auth 는 토큰 없는 로그인 단계에서 대신 부른다. 내부 호출이라 토큰 없이(ADR-0026). 번호는 로그에 남기지 않는다.
 *
 * <p>미전달·503 은 DEPENDENCY_UNAVAILABLE, 응답 시간 초과는 UNKNOWN_RESULT({@link Downstream}). 4xx 는 customer
 * 의 코드·상태 그대로.
 */
@Component
public class CustomerIdentityClient {

  private static final String IDENTITY = "customer-service identity";
  private static final String START = "customer-service identity-verifications";
  private static final String CONFIRM = "customer-service identity-confirmations";

  /** 시작 결과(보낼 문자). */
  public record Started(
      String verificationId,
      String sendTo,
      String text,
      Instant expiresAt,
      String phoneMasked,
      @Nullable Integer maxAttempts) {}

  /** 확인 결과. outcome 은 문자가 확인된 뒤에만(VERIFIED·IDENTITY_ALREADY_REGISTERED·IDENTITY_REJOIN_BLOCKED). */
  public record Confirmed(
      String verificationId,
      String state,
      @Nullable String failureReason,
      int remainingAttempts,
      Instant expiresAt,
      @Nullable String outcome,
      @Nullable String existingAccountId,
      @Nullable String existingEmailMasked,
      @Nullable Instant rejoinAvailableAt) {}

  private final RestClient http;

  public CustomerIdentityClient(RestClient.Builder builder, CustomerProperties props) {
    this.http = builder.clone().baseUrl(props.customerServiceBaseUrl()).build();
  }

  /** 계정의 확인된 번호가 있는가(가입 전 계정은 false). */
  public boolean verified(String accountId) {
    try {
      JsonNode n =
          Downstream.call(
              IDENTITY,
              () ->
                  http.get()
                      .uri("/api/v1/internal/accounts/{id}/identity", accountId)
                      .retrieve()
                      .body(JsonNode.class));
      return n != null && n.path("verified").asBoolean(false);
    } catch (HttpClientErrorException e) {
      throw passThrough(e);
    }
  }

  public Started start(String accountId, String phone, String idempotencyKey) {
    try {
      JsonNode n =
          Downstream.call(
              START,
              () ->
                  http.post()
                      .uri("/api/v1/internal/accounts/{id}/identity-verifications", accountId)
                      .header("Idempotency-Key", idempotencyKey)
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(Map.of("phone", phone))
                      .retrieve()
                      .body(JsonNode.class));
      if (n == null) {
        throw PmartException.unknownResult("customer-service 휴대폰 확인 시작 응답이 비었습니다.");
      }
      return new Started(
          n.path("verificationId").asString(),
          n.path("sendTo").asString(),
          n.path("text").asString(),
          Instant.parse(n.path("expiresAt").asString()),
          n.path("phoneMasked").asString(""),
          n.hasNonNull("maxAttempts") ? n.get("maxAttempts").asInt() : null);
    } catch (HttpClientErrorException e) {
      throw passThrough(e);
    }
  }

  public Confirmed confirm(String accountId, String verificationId) {
    try {
      JsonNode n =
          Downstream.call(
              CONFIRM,
              () ->
                  http.post()
                      .uri(
                          "/api/v1/internal/accounts/{id}/identity-verifications/{vid}/confirmations",
                          accountId,
                          verificationId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(Map.of())
                      .retrieve()
                      .body(JsonNode.class));
      if (n == null) {
        throw PmartException.unknownResult("customer-service 휴대폰 확인 응답이 비었습니다.");
      }
      return new Confirmed(
          n.path("verificationId").asString(),
          n.path("state").asString(),
          text(n, "failureReason"),
          n.path("remainingAttempts").asInt(0),
          Instant.parse(n.path("expiresAt").asString()),
          text(n, "outcome"),
          text(n, "existingAccountId"),
          text(n, "existingEmailMasked"),
          n.hasNonNull("rejoinAvailableAt")
              ? Instant.parse(n.get("rejoinAvailableAt").asString())
              : null);
    } catch (HttpClientErrorException e) {
      throw passThrough(e);
    }
  }

  private static @Nullable String text(JsonNode n, String field) {
    return n.hasNonNull(field) ? n.get(field).asString() : null;
  }

  /** customer 의 4xx(문제 코드·문구)를 같은 상태·코드로. */
  private static PmartException passThrough(HttpClientErrorException e) {
    String code = "HTTP_" + e.getStatusCode().value();
    String detail = "customer-service 가 휴대폰 확인 요청을 거절했습니다.";
    try {
      JsonNode problem = e.getResponseBodyAs(JsonNode.class);
      if (problem != null && problem.hasNonNull("code")) {
        code = problem.get("code").asString();
      }
      if (problem != null && problem.hasNonNull("detail")) {
        detail = problem.get("detail").asString();
      }
    } catch (RuntimeException ignored) {
      // 본문 해석 실패는 상태 코드로
    }
    HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
    return new PmartException(
        code,
        status == null ? HttpStatus.BAD_REQUEST : status,
        detail,
        false,
        List.<PmartException.Detail>of());
  }
}
