package com.pmart.common.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 행위 시점 권한 검증 — auth-service {@code POST /internal/authorizations}
 * (contracts/openapi/auth-service.yaml). BackOffice 3.2: 로그인·조회·제출·승인·실행·재처리·내보내기는 별도 행위이며 서버가 매
 * 행위마다 역할·센터 범위·대행 기간·대상 소유권을 검증한다.
 *
 * <p>호출은 {@code /internal/} 이므로 서비스 신원 토큰으로 나간다({@link ServiceCallHeadersCustomizer}). 행위자는 현재 사용자
 * 토큰의 주체를 본문 actor 로 보낸다. {@code pmart.security.authz.base-url} 은 {@code /api/v1} 까지 포함한다(예:
 * http://auth-service:8501/api/v1).
 *
 * <p><b>fail-closed</b>: auth-service 응답 불명(타임아웃·5xx)이면 DENY(DEPENDENCY_UNAVAILABLE). 권한을 추정하지 않는다.
 */
public class ActionAuthorizer {

  /**
   * @param ownerPrincipalId 대상 소유권 검증용(없으면 null)
   * @param requesterPrincipalId 자기승인 분리 검증용 — 승인 대상의 발의자(없으면 null)
   */
  public record Request(
      String action,
      String targetType,
      String targetId,
      @Nullable String centerId,
      @Nullable String ownerPrincipalId,
      @Nullable String requesterPrincipalId) {
    public Request(String action, String targetType, String targetId, @Nullable String centerId) {
      this(action, targetType, targetId, centerId, null, null);
    }
  }

  public record Decision(boolean allowed, String reasonCode, @Nullable String decisionId) {
    public static Decision deny(String reason) {
      return new Decision(false, reason, null);
    }
  }

  public static class AuthorizationDeniedException extends RuntimeException {
    private final Decision decision;

    public AuthorizationDeniedException(Decision decision) {
      super("권한 없음: " + decision.reasonCode());
      this.decision = decision;
    }

    public Decision decision() {
      return decision;
    }
  }

  private static final Logger log = LoggerFactory.getLogger(ActionAuthorizer.class);

  private final RestClient client;

  public ActionAuthorizer(RestClient client) {
    this.client = client;
  }

  public Decision decide(Request request) {
    PmartPrincipal principal = PmartPrincipal.current().orElse(null);
    if (principal == null) {
      return Decision.deny("UNAUTHENTICATED");
    }
    // contracts/openapi/auth-service.yaml AuthorizationRequest
    Map<String, Object> target = new LinkedHashMap<>();
    target.put("type", request.targetType());
    target.put("id", request.targetId());
    if (request.ownerPrincipalId() != null) {
      target.put("ownerPrincipalId", request.ownerPrincipalId());
    }
    if (request.requesterPrincipalId() != null) {
      target.put("requesterPrincipalId", request.requesterPrincipalId());
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("actor", Map.of("principalId", principal.subject()));
    body.put("action", request.action());
    body.put("target", target);
    body.put("centerScope", request.centerId() == null ? List.of() : List.of(request.centerId()));
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> res =
          client
              .post()
              .uri("/internal/authorizations")
              // 계약상 필수 헤더. 판정은 상태를 바꾸지 않으므로 호출마다 새 키.
              .header("Idempotency-Key", UUID.randomUUID().toString())
              .contentType(MediaType.APPLICATION_JSON)
              .body(body)
              .retrieve()
              .body(Map.class);
      if (res == null) {
        return Decision.deny("DEPENDENCY_UNAVAILABLE");
      }
      boolean allowed = "ALLOW".equals(String.valueOf(res.get("decision")));
      String reason = allowed ? "ALLOWED" : firstReason(res.get("reasons"));
      String id = res.get("decisionId") == null ? null : String.valueOf(res.get("decisionId"));
      return new Decision(allowed, reason, id);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().is4xxClientError()) {
        return Decision.deny("DENIED_" + e.getStatusCode().value());
      }
      log.warn("auth-service {} → fail-closed", e.getStatusCode());
      return Decision.deny("DEPENDENCY_UNAVAILABLE");
    } catch (ResourceAccessException e) {
      log.warn("auth-service 접근 불가 → fail-closed: {}", e.getMessage());
      return Decision.deny("DEPENDENCY_UNAVAILABLE");
    } catch (ServiceTokenProvider.ServiceTokenUnavailableException e) {
      log.warn("서비스 토큰 없음 → fail-closed");
      return Decision.deny("DEPENDENCY_UNAVAILABLE");
    }
  }

  private static String firstReason(@Nullable Object reasons) {
    if (reasons instanceof List<?> list && !list.isEmpty() && list.getFirst() != null) {
      return String.valueOf(list.getFirst());
    }
    return "DENIED";
  }

  /** 허용이 아니면 예외. 컨트롤러/애플리케이션 서비스에서 변경 명령 직전에 호출한다. */
  public Decision require(Request request) {
    Decision d = decide(request);
    if (!d.allowed()) {
      throw new AuthorizationDeniedException(d);
    }
    return d;
  }
}
