package com.pmart.customer.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.customer.application.WithdrawalProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * 고객 탈퇴(2026-11-20 ADR-0077)가 부르는 다른 서비스 내부 API. 내부 호출이라 토큰 없이(ADR-0026).
 *
 * <ul>
 *   <li>재인증 — auth {@code POST /internal/customers/{id}/reauthentications}. 401·403 은 같은 코드로 고객에게,
 *       그 밖 실패는 503.
 *   <li>의무 확인 — order·claim {@code GET /internal/accounts/{id}/withdrawal-eligibility}. 실패는 {@link
 *       Eligibility#unknown} (거래 없음으로 추정하지 않음).
 * </ul>
 *
 * 토큰·개인정보는 로그에 남기지 않는다.
 */
@Component
public class WithdrawalClients {

  private static final Logger log = LoggerFactory.getLogger(WithdrawalClients.class);

  /** 미결 의무 한 줄(kind·ref). */
  public record Obligation(String kind, String ref) {}

  /** 한 서비스의 확인 결과. known=false 면 확인 실패(UNKNOWN). */
  public record Eligibility(boolean known, boolean eligible, List<Obligation> obligations) {
    static Eligibility unknown() {
      return new Eligibility(false, false, List.of());
    }
  }

  private final RestClient http;
  private final WithdrawalProperties props;

  public WithdrawalClients(RestClient.Builder builder, WithdrawalProperties props) {
    this.http = builder.clone().build();
    this.props = props;
  }

  /**
   * 재인증 확인. 맞으면 그대로 끝, 아니면 PmartException(401 PROVIDER_REJECTED·403 REAUTHENTICATION_MISMATCH·503).
   */
  public void reauthenticate(String accountId, String provider, String providerToken) {
    String base = props.authBaseUrl();
    if (base == null || base.isBlank()) {
      throw PmartException.configRequired("pmart.customer.withdrawal.auth-base-url");
    }
    try {
      Downstream.call(
          "auth-service reauthentications",
          () ->
              http.post()
                  .uri(base + "/api/v1/internal/customers/{id}/reauthentications", accountId)
                  .header("Idempotency-Key", UUID.randomUUID().toString())
                  .contentType(MediaType.APPLICATION_JSON)
                  .body(Map.of("provider", provider, "providerToken", providerToken))
                  .retrieve()
                  .toBodilessEntity());
    } catch (HttpClientErrorException e) {
      int status = e.getStatusCode().value();
      if (status == 401) {
        throw new PmartException(
            "PROVIDER_REJECTED",
            HttpStatus.UNAUTHORIZED,
            "다시 로그인한 정보를 확인하지 못했습니다.",
            false,
            List.of());
      }
      if (status == 403) {
        throw new PmartException(
            "REAUTHENTICATION_MISMATCH",
            HttpStatus.FORBIDDEN,
            "이 계정에 연결된 소셜 계정으로 다시 로그인해 주세요.",
            false,
            List.of());
      }
      log.warn("재인증 확인 실패 HTTP {}", status);
      throw new PmartException(
          ErrorCode.DEPENDENCY_UNAVAILABLE, "재인증을 확인하지 못했습니다. 잠시 뒤 다시 시도해 주세요.");
    }
  }

  public Eligibility order(String accountId) {
    return eligibility("order-service", props.orderBaseUrl(), accountId);
  }

  public Eligibility claim(String accountId) {
    return eligibility("claim-service", props.claimBaseUrl(), accountId);
  }

  private Eligibility eligibility(String target, @Nullable String base, String accountId) {
    if (base == null || base.isBlank()) {
      log.warn("탈퇴 의무 확인 주소 없음({}) — UNKNOWN", target);
      return Eligibility.unknown();
    }
    try {
      JsonNode n =
          Downstream.call(
              target + " withdrawal-eligibility",
              () ->
                  http.get()
                      .uri(
                          base + "/api/v1/internal/accounts/{id}/withdrawal-eligibility", accountId)
                      .retrieve()
                      .body(JsonNode.class));
      if (n == null || !n.has("eligible")) {
        return Eligibility.unknown();
      }
      List<Obligation> list = new ArrayList<>();
      for (JsonNode o : n.path("openObligations")) {
        list.add(new Obligation(o.path("kind").asString(""), o.path("ref").asString("")));
      }
      return new Eligibility(true, n.path("eligible").asBoolean(false) && list.isEmpty(), list);
    } catch (RuntimeException e) {
      log.warn("탈퇴 의무 확인 실패({}): {}", target, e.getClass().getSimpleName());
      return Eligibility.unknown();
    }
  }
}
