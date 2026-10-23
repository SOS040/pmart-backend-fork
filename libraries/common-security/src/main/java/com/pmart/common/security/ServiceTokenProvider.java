package com.pmart.common.security;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 이 서비스의 신원 토큰(auth-service {@code POST /api/v1/internal/service-tokens}, client_credentials,
 * ADR-0011). 만료 {@code refresh-skew} 전까지 캐시하고, 그 뒤 첫 호출이 새로 받는다(동시 호출은 한 번만 요청).
 *
 * <p>발급 실패는 {@link ServiceTokenUnavailableException} — 토큰 없이 내부 호출을 보내지 않는다. 토큰·secret 은 로그에 남기지
 * 않는다.
 */
public class ServiceTokenProvider {

  /** 서비스 토큰을 얻지 못함. 호출 측은 상대 서비스 업무 실패로 단정하지 않는다(의존성 장애). */
  public static class ServiceTokenUnavailableException extends RuntimeException {
    public ServiceTokenUnavailableException(String message, @Nullable Throwable cause) {
      super(message, cause);
    }
  }

  private record Cached(String value, Instant refreshAt) {}

  private static final Logger log = LoggerFactory.getLogger(ServiceTokenProvider.class);

  private final RestClient client;
  private final String tokenUri;
  private final String basicCredentials;
  private final Duration refreshSkew;
  private final Clock clock;
  private final Object lock = new Object();
  private volatile @Nullable Cached cached;

  public ServiceTokenProvider(
      RestClient client,
      String tokenUri,
      String clientId,
      String clientSecret,
      Duration refreshSkew,
      Clock clock) {
    this.client = client;
    this.tokenUri = tokenUri;
    // RFC 6749 2.3.1: form-urlencode 후 Basic
    String raw =
        URLEncoder.encode(clientId, StandardCharsets.UTF_8)
            + ":"
            + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
    this.basicCredentials =
        java.util.Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    this.refreshSkew = refreshSkew;
    this.clock = clock;
  }

  public String token() {
    Cached c = cached;
    if (c != null && clock.instant().isBefore(c.refreshAt())) {
      return c.value();
    }
    synchronized (lock) {
      c = cached;
      if (c != null && clock.instant().isBefore(c.refreshAt())) {
        return c.value();
      }
      Cached fresh = fetch();
      cached = fresh;
      return fresh.value();
    }
  }

  private Cached fetch() {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");
    Map<?, ?> body;
    try {
      body =
          client
              .post()
              .uri(tokenUri)
              .header("Authorization", "Basic " + basicCredentials)
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .accept(MediaType.APPLICATION_JSON)
              .body(form)
              .retrieve()
              .body(Map.class);
    } catch (RestClientException e) {
      log.warn("서비스 토큰 발급 실패: {}", e.getClass().getSimpleName());
      throw new ServiceTokenUnavailableException("서비스 토큰을 발급받지 못했다", e);
    }
    Object token = body == null ? null : body.get("access_token");
    Object expiresIn = body == null ? null : body.get("expires_in");
    if (!(token instanceof String value) || !(expiresIn instanceof Number seconds)) {
      throw new ServiceTokenUnavailableException("서비스 토큰 응답 형식 오류", null);
    }
    Duration ttl = Duration.ofSeconds(seconds.longValue());
    // 짧은 수명이면 skew 대신 수명의 절반에서 갱신
    Duration refreshAfter =
        ttl.compareTo(refreshSkew.multipliedBy(2)) > 0 ? ttl.minus(refreshSkew) : ttl.dividedBy(2);
    return new Cached(value, clock.instant().plus(refreshAfter));
  }
}
