package com.pmart.common.security;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code pmart.security.*}. */
@ConfigurationProperties(prefix = "pmart.security")
public class SecurityProperties {

  /** 인증 없이 허용할 경로. health/info 는 쿠버네티스 probe 용. prometheus 는 네트워크 정책으로 보호한다는 전제. */
  private List<String> permitPaths =
      List.of("/actuator/health/**", "/actuator/info", "/actuator/prometheus", "/error");

  /**
   * 서비스 간 전용 경로. 서비스 토큰 없이 들어온다(ADR-0026 — private subnet·게이트웨이 차단을 신뢰 경계로). 토큰이 없으면 {@link
   * InternalCallerFilter} 가 X-Source-System 이름의 서비스 주체(TYPE_SERVICE)로 채운다. 사용자 토큰(고객·운영자 등)이 오면
   * 403. 계약의 servers.url 이 /api/v1 이므로 실제 경로는 /api/v1/internal/**.
   */
  private List<String> internalPaths = List.of("/internal/**", "/api/*/internal/**");

  /**
   * 비회원 토큰(typ=GUEST)을 받는 경로. 기본은 없음 — GUEST 토큰은 다른 모든 인증 경로에서 403(회원 전용 API 를 비회원이 부르지 못하게). 예:
   * cart-service 의 /api/v1/carts/**.
   */
  private List<String> guestPaths = List.of();

  /** 행위 시점 검증(auth-service) 클라이언트 설정. */
  private Authz authz = new Authz();

  public static class Authz {
    /** auth-service 기본 URL. 비우면 ActionAuthorizer 를 등록하지 않는다. */
    private String baseUrl = "";

    private Duration connectTimeout = Duration.ofMillis(500);
    private Duration readTimeout = Duration.ofSeconds(2);

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public Duration getConnectTimeout() {
      return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
      this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
      return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
      this.readTimeout = readTimeout;
    }
  }

  public List<String> getPermitPaths() {
    return permitPaths;
  }

  public void setPermitPaths(List<String> permitPaths) {
    this.permitPaths = permitPaths;
  }

  public List<String> getInternalPaths() {
    return internalPaths;
  }

  public List<String> getGuestPaths() {
    return guestPaths;
  }

  public void setGuestPaths(List<String> guestPaths) {
    this.guestPaths = guestPaths;
  }

  public void setInternalPaths(List<String> internalPaths) {
    this.internalPaths = internalPaths;
  }

  public Authz getAuthz() {
    return authz;
  }

  public void setAuthz(Authz authz) {
    this.authz = authz;
  }
}
