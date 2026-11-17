package com.pmart.auth.infrastructure;

import com.pmart.auth.application.ConsentDecision;
import com.pmart.auth.application.CustomerProperties;
import com.pmart.common.web.Downstream;
import com.pmart.common.web.PmartException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * customer-service 약관 동의 내부 경로(CU-1, customer-service.yaml 0.3.0). 동의의 정본은 customer — auth 는 로그인 때
 * 필수 동의가 찼는지 묻고, 동의 단계(AUTH_CONSENT)에서 고객이 고른 값을 넘긴다. 내부 호출이라 토큰 없이(ADR-0026, X-Source-System 은 공통
 * RestClient 설정이 붙인다).
 *
 * <p>미전달·503 은 DEPENDENCY_UNAVAILABLE, 응답 시간 초과는 UNKNOWN_RESULT({@link Downstream}). 4xx 는 customer
 * 의 코드·상태 그대로 {@link PmartException} 으로 바꾼다(해석은 호출자).
 */
@Component
public class CustomerConsentClient {

  private static final String STATUS = "customer-service consents";
  private static final String CHANGE = "customer-service consent-changes";

  /**
   * 현행 버전 기준 동의 상태.
   *
   * @param missingRequired 현행 버전 동의가 없는 필수 항목 {@code TYPE:version}
   * @param undecidedOptional 현행 버전으로 아직 고르지 않은 선택 항목
   */
  public record ConsentCheck(List<String> missingRequired, List<String> undecidedOptional) {
    public boolean satisfied() {
      return missingRequired.isEmpty();
    }
  }

  private final RestClient http;

  public CustomerConsentClient(RestClient.Builder builder, CustomerProperties props) {
    this.http = builder.clone().baseUrl(props.customerServiceBaseUrl()).build();
  }

  /** 계정(가입 중이라 customer 에 행이 없어도 된다)의 동의 상태. */
  public ConsentCheck check(String accountId) {
    try {
      JsonNode n =
          Downstream.call(
              STATUS,
              () ->
                  http.get()
                      .uri("/api/v1/internal/accounts/{id}/consents", accountId)
                      .retrieve()
                      .body(JsonNode.class));
      return parse(n);
    } catch (HttpClientErrorException e) {
      throw passThrough(e);
    }
  }

  /**
   * 로그인 중 받은 동의 반영 → 반영 뒤 상태.
   *
   * @param idempotencyKey customer 쪽 멱등 키(같은 키·같은 본문 재전송은 다시 반영하지 않는다)
   * @param channel SIGNUP(신규 가입)·LOGIN(기존 회원 재동의)
   */
  public ConsentCheck change(
      String accountId, String idempotencyKey, String channel, List<ConsentDecision> decisions) {
    List<Map<String, Object>> changes = new ArrayList<>();
    for (ConsentDecision d : decisions) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("consentType", d.consentType());
      m.put("version", d.version());
      m.put("agreed", d.agreed());
      changes.add(m);
    }
    try {
      JsonNode n =
          Downstream.call(
              CHANGE,
              () ->
                  http.post()
                      .uri("/api/v1/internal/accounts/{id}/consent-changes", accountId)
                      .header("Idempotency-Key", idempotencyKey)
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(Map.of("channel", channel, "changes", changes))
                      .retrieve()
                      .body(JsonNode.class));
      return parse(n);
    } catch (HttpClientErrorException e) {
      throw passThrough(e);
    }
  }

  private static ConsentCheck parse(JsonNode n) {
    if (n == null) {
      throw PmartException.unknownResult("customer-service 동의 상태 응답이 비었습니다.");
    }
    return new ConsentCheck(
        strings(n.path("missingRequired")), strings(n.path("undecidedOptional")));
  }

  private static List<String> strings(JsonNode arr) {
    List<String> out = new ArrayList<>();
    for (JsonNode s : arr) {
      out.add(s.asString());
    }
    return List.copyOf(out);
  }

  /** customer 의 4xx(문제 코드·문구)를 같은 상태·코드로. 본문을 못 읽으면 상태만. */
  private static PmartException passThrough(HttpClientErrorException e) {
    String code = "HTTP_" + e.getStatusCode().value();
    String detail = "customer-service 가 동의 요청을 거절했습니다.";
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
