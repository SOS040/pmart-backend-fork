package com.pmart.auth.infrastructure;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keycloak 관리 API(읽기만) — 직원 차단 중계(ADR-0054 ⑤ 결정 M). 중계 클라이언트({@code client_credentials}, 서비스 계정 역할
 * realm-management {@code view-events}·{@code view-users})로 토큰을 받아 관리 기록과 사용자를 읽는다. 쓰기 권한은 없다.
 *
 * <p>토큰·비밀값은 로그에 남기지 않는다. 사용자 정보는 {@code enabled} 와 운영자 ID 속성만 꺼낸다.
 */
public class KeycloakAdminClient {

  /** 관리 기록 한 건. {@code resourcePath} 예: {@code users/<id>}, {@code users/<id>/logout}. */
  public record AdminEvent(String key, Instant time, String operationType, String resourcePath) {}

  /** 사용자 상태. {@code operatorId} = 속성 {@code pmart_operator_id}(없으면 null). */
  public record StaffUser(boolean enabled, @Nullable String operatorId) {}

  static final int PAGE = 100;

  private final RestClient http;
  private final String realm;
  private final String clientId;
  private final String clientSecret;
  private final String operatorIdAttribute;
  private final Clock clock;
  private final JsonMapper mapper = JsonMapper.builder().build();
  private @Nullable String token;
  private Instant tokenUntil = Instant.EPOCH;

  public KeycloakAdminClient(
      String baseUrl,
      String realm,
      String clientId,
      String clientSecret,
      String operatorIdAttribute,
      Duration timeout,
      Clock clock) {
    JdkClientHttpRequestFactory f =
        new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
    f.setReadTimeout(timeout);
    // Boot 의 RestClient.Builder 를 쓰지 않는다 — LAN 의 Eureka 부하 분산 가로채기가 붙지 않게(Keycloak 은 등록 대상 아님)
    this.http = RestClient.builder().baseUrl(trimSlash(baseUrl)).requestFactory(f).build();
    this.realm = realm;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.operatorIdAttribute = operatorIdAttribute;
    this.clock = clock;
  }

  /**
   * {@code since} 이후(같은 날짜부터 받아 시각으로 거름)의 사용자 관리 기록, 오래된 것부터. Keycloak 은 {@code dateFrom} 을 날짜로 받고
   * 최신순으로 준다.
   */
  public List<AdminEvent> userEventsSince(Instant since) {
    String from = LocalDate.ofInstant(since, ZoneOffset.UTC).minusDays(1).toString();
    List<AdminEvent> out = new ArrayList<>();
    for (int first = 0; ; first += PAGE) {
      int start = first;
      String body =
          get(
              u ->
                  u.path("/admin/realms/{realm}/admin-events")
                      .queryParam("resourceTypes", "USER")
                      .queryParam("dateFrom", from)
                      .queryParam("first", start)
                      .queryParam("max", PAGE)
                      .build(realm));
      JsonNode arr = mapper.readTree(body);
      for (JsonNode e : arr) {
        Instant t = Instant.ofEpochMilli(e.path("time").asLong());
        if (!t.isBefore(since)) {
          String path = e.path("resourcePath").asString("");
          String op = e.path("operationType").asString("");
          String id = e.path("id").asString("");
          // id 가 없는 판에서도 같은 기록이면 같은 키가 되게
          String key = id.isBlank() ? t.toEpochMilli() + "|" + op + "|" + path : id;
          out.add(new AdminEvent(key, t, op, path));
        }
      }
      if (arr.size() < PAGE) {
        break;
      }
    }
    out.sort((a, b) -> a.time().compareTo(b.time()));
    return out;
  }

  /** 사용자. 지워졌으면 빈 값. */
  public Optional<StaffUser> user(String keycloakUserId) {
    try {
      String body =
          get(u -> u.path("/admin/realms/{realm}/users/{id}").build(realm, keycloakUserId));
      JsonNode n = mapper.readTree(body);
      JsonNode v = n.path("attributes").path(operatorIdAttribute);
      String opId = v.isArray() && !v.isEmpty() ? v.get(0).asString("") : null;
      return Optional.of(
          new StaffUser(
              n.path("enabled").asBoolean(false), opId == null || opId.isBlank() ? null : opId));
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    }
  }

  private String get(
      java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI> uri) {
    String t = token();
    try {
      return http.get().uri(uri).headers(h -> h.setBearerAuth(t)).retrieve().body(String.class);
    } catch (HttpClientErrorException.Unauthorized e) {
      tokenUntil = Instant.EPOCH; // 토큰이 먼저 끝났다 — 다음 호출에서 다시 받는다
      throw e;
    }
  }

  private synchronized String token() {
    if (token != null && clock.instant().isBefore(tokenUntil)) {
      return token;
    }
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", clientId);
    form.add("client_secret", clientSecret);
    String body =
        http.post()
            .uri("/realms/{realm}/protocol/openid-connect/token", realm)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .onStatus(
                HttpStatusCode::isError,
                (q, r) -> {
                  throw new IllegalStateException(
                      "Keycloak 중계 클라이언트 토큰을 받지 못했습니다: " + r.getStatusCode().value());
                })
            .body(String.class);
    JsonNode n = mapper.readTree(body);
    token = n.path("access_token").asString();
    long ttl = n.path("expires_in").asLong(60);
    tokenUntil = clock.instant().plusSeconds(Math.max(5, ttl - 30));
    return token;
  }

  private static String trimSlash(String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }
}
