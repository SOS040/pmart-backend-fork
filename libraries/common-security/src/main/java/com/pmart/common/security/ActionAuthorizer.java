package com.pmart.common.security;

import java.util.List;
import java.util.Map;
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
 * <p><b>fail-closed</b>: auth-service 응답 불명(타임아웃·5xx)이면 DENY(DEPENDENCY_UNAVAILABLE). 권한을 추정하지 않는다.
 */
public class ActionAuthorizer {

  public record Request(
      String action,
      String targetType,
      String targetId,
      @Nullable String centerId,
      Map<String, Object> context) {
    public Request(String action, String targetType, String targetId, @Nullable String centerId) {
      this(action, targetType, targetId, centerId, Map.of());
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
    Map<String, Object> body =
        Map.of(
            "actor",
            Map.of("id", principal.subject(), "type", principal.type().name()),
            "action",
            request.action(),
            "target",
            Map.of("type", request.targetType(), "id", request.targetId()),
            "centerScope",
            request.centerId() == null ? List.of() : List.of(request.centerId()),
            "context",
            request.context());
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> res =
          client
              .post()
              .uri("/internal/authorizations")
              .contentType(MediaType.APPLICATION_JSON)
              .body(body)
              .retrieve()
              .body(Map.class);
      if (res == null) {
        return Decision.deny("DEPENDENCY_UNAVAILABLE");
      }
      boolean allowed = "ALLOW".equals(String.valueOf(res.get("decision")));
      String reason =
          res.get("reasonCode") == null
              ? (allowed ? "ALLOWED" : "DENIED")
              : String.valueOf(res.get("reasonCode"));
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
    }
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
