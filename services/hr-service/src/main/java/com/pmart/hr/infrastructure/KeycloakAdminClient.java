package com.pmart.hr.infrastructure;

import com.pmart.hr.application.HrProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Keycloak Admin API — 서비스 계정 클라이언트 {@code hr-provisioner}(ADR-0056 결정 16). {@code
 * client_credentials} 로 받은 관리용 토큰을 만료 전까지 재사용하고, 401 이면 한 번만 새로 받아 다시 부른다.
 *
 * <p><b>사용자 경로만</b> 부른다(만들기·찾기·활성/비활성·세션 로그아웃·비밀번호 설정 메일). 역할·그룹 매핑 API 는 부르지 않는다(U-11 (B) — 전환
 * 기간에는 Keycloak 역할이 실제 권한이 될 수 있어서). 비밀번호를 정하거나 읽지 않는다. 토큰·비밀은 로그에 남기지 않는다.
 */
public class KeycloakAdminClient {

  public static final String ATTR_OPERATOR_ID = "pmart_operator_id";

  /** Keycloak 사용자(필요한 값만). */
  public record User(String id, String username, boolean enabled, @Nullable String operatorId) {}

  /** Keycloak 에 닿지 않거나 5xx — 다시 시도할 수 있다. */
  public static class Unavailable extends RuntimeException {
    public Unavailable(String message, @Nullable Throwable cause) {
      super(message, cause);
    }
  }

  /** Keycloak 이 거절(4xx) — 코드는 진행 상태의 오류 코드로 남긴다. */
  public static class Rejected extends RuntimeException {
    private final String code;

    public Rejected(String code, String message) {
      super(message);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }

  private final HrProperties.Keycloak kc;
  private final RestClient http;
  private final Clock clock;
  private volatile @Nullable String token;
  private volatile Instant tokenExpiresAt = Instant.EPOCH;

  public KeycloakAdminClient(HrProperties.Keycloak kc, Clock clock) {
    this.kc = kc;
    this.clock = clock;
    JdkClientHttpRequestFactory f =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Objects.requireNonNull(kc.timeout()))
                .build());
    f.setReadTimeout(Objects.requireNonNull(kc.timeout()));
    // 앱 공용 RestClient.Builder(사용자 토큰 전파)를 쓰지 않는다 — Keycloak 에는 이 서비스의 클라이언트 자격만
    this.http = RestClient.builder().requestFactory(f).build();
  }

  public Optional<User> findByUsername(String username) {
    return call(
        t -> {
          List<?> list =
              http.get()
                  .uri(admin("/users?exact=true&briefRepresentation=false&username={u}"), username)
                  .headers(h -> h.setBearerAuth(t))
                  .retrieve()
                  .body(List.class);
          return list == null || list.isEmpty()
              ? Optional.empty()
              : Optional.of(user((Map<?, ?>) list.getFirst()));
        });
  }

  public User get(String userId) {
    return call(t -> user(rep(t, userId)));
  }

  /**
   * 사용자 만들기 — 이미 있으면(409) 그 사용자를 찾아 운영자 ID 가 같을 때만 그대로 쓴다(재시도 멱등). 다른 운영자 ID 면 거절
   * IAM_USERNAME_TAKEN. 첫 로그인 때 비밀번호를 정하게(UPDATE_PASSWORD) 한다.
   */
  public String createUser(String username, String email, String firstName, String operatorId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("username", username);
    body.put("email", email);
    body.put("emailVerified", true); // 사내 도메인 사서함 — 우리가 만든 주소
    body.put("firstName", firstName);
    body.put("enabled", true);
    body.put("attributes", Map.of(ATTR_OPERATOR_ID, List.of(operatorId)));
    body.put("requiredActions", List.of("UPDATE_PASSWORD"));
    try {
      ResponseEntity<Void> r =
          call(
              t ->
                  http.post()
                      .uri(admin("/users"))
                      .headers(h -> h.setBearerAuth(t))
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(body)
                      .retrieve()
                      .toBodilessEntity());
      URI location = r.getHeaders().getLocation();
      if (location == null) {
        throw new Unavailable("Keycloak 사용자 만들기 응답에 Location 이 없다", null);
      }
      String path = location.getPath();
      return path.substring(path.lastIndexOf('/') + 1);
    } catch (Rejected e) {
      if (!"KEYCLOAK_409".equals(e.code())) {
        throw e;
      }
      User existing =
          findByUsername(username)
              .orElseThrow(() -> new Rejected("IAM_USERNAME_TAKEN", "같은 이메일·아이디의 Keycloak 사용자"));
      if (!operatorId.equals(existing.operatorId())) {
        throw new Rejected("IAM_USERNAME_TAKEN", "같은 아이디의 다른 Keycloak 사용자");
      }
      return existing.id();
    }
  }

  /** 비밀번호 설정 메일(Keycloak 내장 — SMTP 는 사내 메일 서버). 메일 본문에 비밀번호는 없다. */
  public void sendPasswordSetupMail(String userId) {
    call(
        t ->
            http.put()
                .uri(
                    admin("/users/{id}/execute-actions-email?lifespan={s}"),
                    userId,
                    Objects.requireNonNull(kc.inviteLifespan()).toSeconds())
                .headers(h -> h.setBearerAuth(t))
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of("UPDATE_PASSWORD"))
                .retrieve()
                .toBodilessEntity());
  }

  /** 활성/비활성. 사용자 표현 전체를 읽어 enabled 만 바꿔 다시 쓴다(속성이 지워지지 않게). */
  public void setEnabled(String userId, boolean enabled) {
    call(
        t -> {
          Map<String, Object> rep = new LinkedHashMap<>(castMap(rep(t, userId)));
          if (Boolean.valueOf(enabled).equals(rep.get("enabled"))) {
            return null;
          }
          rep.put("enabled", enabled);
          return http.put()
              .uri(admin("/users/{id}"), userId)
              .headers(h -> h.setBearerAuth(t))
              .contentType(MediaType.APPLICATION_JSON)
              .body(rep)
              .retrieve()
              .toBodilessEntity();
        });
  }

  /** 그 사용자의 Keycloak 세션을 모두 끝낸다(SSO 브라우저 포함). */
  public void logoutSessions(String userId) {
    call(
        t ->
            http.post()
                .uri(admin("/users/{id}/logout"), userId)
                .headers(h -> h.setBearerAuth(t))
                .retrieve()
                .toBodilessEntity());
  }

  // ------------------------------------------------------------------ 공통

  private Map<?, ?> rep(String token, String userId) {
    Map<?, ?> m =
        http.get()
            .uri(admin("/users/{id}"), userId)
            .headers(h -> h.setBearerAuth(token))
            .retrieve()
            .body(Map.class);
    if (m == null) {
      throw new Rejected("KEYCLOAK_404", "사용자 없음");
    }
    return m;
  }

  /** 관리용 토큰으로 부르고, 401 이면 토큰을 새로 받아 한 번만 다시 부른다. */
  private <T> T call(Function<String, T> fn) {
    for (int attempt = 0; ; attempt++) {
      String t = token(attempt > 0);
      try {
        return fn.apply(t);
      } catch (RestClientResponseException e) {
        if (e.getStatusCode().value() == 401 && attempt == 0) {
          continue;
        }
        if (e.getStatusCode().is4xxClientError()) {
          throw new Rejected(
              "KEYCLOAK_" + e.getStatusCode().value(), "Keycloak 거절 " + e.getStatusCode().value());
        }
        throw new Unavailable("Keycloak " + e.getStatusCode().value(), e);
      } catch (RestClientException e) {
        throw new Unavailable("Keycloak 에 닿지 않음", e);
      }
    }
  }

  private synchronized String token(boolean renew) {
    Instant now = clock.instant();
    String t = token;
    if (!renew && t != null && now.isBefore(tokenExpiresAt)) {
      return t;
    }
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");
    Map<?, ?> body;
    try {
      body =
          http.post()
              .uri(
                  base() + "/realms/{realm}/protocol/openid-connect/token",
                  Objects.requireNonNull(kc.realm()))
              .headers(
                  h ->
                      h.setBasicAuth(
                          Objects.requireNonNull(kc.clientId()),
                          Objects.requireNonNull(kc.clientSecret())))
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .body(Map.class);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().is4xxClientError()) {
        throw new Rejected(
            "IAM_CLIENT_REJECTED", "hr-provisioner 자격 거절 " + e.getStatusCode().value());
      }
      throw new Unavailable("Keycloak 토큰 " + e.getStatusCode().value(), e);
    } catch (RestClientException e) {
      throw new Unavailable("Keycloak 에 닿지 않음", e);
    }
    Object access = body == null ? null : body.get("access_token");
    Object expires = body == null ? null : body.get("expires_in");
    if (!(access instanceof String s)) {
      throw new Unavailable("Keycloak 토큰 응답에 access_token 이 없다", null);
    }
    long ttl = expires instanceof Number n ? n.longValue() : 60;
    token = s;
    // 만료 30초 전에는 새로 받는다
    tokenExpiresAt = now.plusSeconds(Math.max(5, ttl - 30));
    return s;
  }

  private String admin(String path) {
    return base() + "/admin/realms/" + kc.realm() + path;
  }

  private String base() {
    return Objects.requireNonNull(kc.adminBaseUrl()).replaceAll("/+$", "");
  }

  private static User user(Map<?, ?> m) {
    String op = null;
    if (m.get("attributes") instanceof Map<?, ?> attrs
        && attrs.get(ATTR_OPERATOR_ID) instanceof List<?> v
        && !v.isEmpty()) {
      op = String.valueOf(v.getFirst());
    }
    return new User(
        String.valueOf(m.get("id")),
        String.valueOf(m.get("username")),
        Boolean.TRUE.equals(m.get("enabled")),
        op);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Map<?, ?> m) {
    return (Map<String, Object>) m;
  }

  /** 시험용: 이 상태 코드의 거절인가. */
  public static boolean isStatus(Rejected e, HttpStatus status) {
    return ("KEYCLOAK_" + status.value()).equals(e.code());
  }
}
