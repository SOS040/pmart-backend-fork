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
   * 서비스 간 전용 경로. TYPE_SERVICE 권한(서비스 토큰) 필요. 계약의 servers.url 이 /api/v1 이므로 실제 경로는
   * /api/v1/internal/**.
   */
  private List<String> internalPaths = List.of("/internal/**", "/api/*/internal/**");

  /**
   * 비회원 토큰(typ=GUEST)을 받는 경로. 기본은 없음 — GUEST 토큰은 다른 모든 인증 경로에서 403(회원 전용 API 를 비회원이 부르지 못하게). 예:
   * cart-service 의 /api/v1/carts/**.
   */
  private List<String> guestPaths = List.of();

  /** 행위 시점 검증(auth-service) 클라이언트 설정. */
  private Authz authz = new Authz();

  /** 이 서비스의 신원 토큰(client_credentials) 설정. token-uri 가 비어 있으면 서비스 토큰을 쓰지 않는다. */
  private ServiceToken serviceToken = new ServiceToken();

  public static class ServiceToken {
    /** auth-service 발급 주소. 예: http://auth-service:8501/api/v1/internal/service-tokens */
    private String tokenUri = "";

    /** 비우면 spring.application.name. */
    private String clientId = "";

    /** Secret Manager 주입(PMART_SERVICE_CLIENT_SECRET). 저장소·로그 금지. */
    private String clientSecret = "";

    /** 만료 이만큼 전에 새로 받는다. */
    private Duration refreshSkew = Duration.ofSeconds(30);

    private Duration connectTimeout = Duration.ofMillis(500);
    private Duration readTimeout = Duration.ofSeconds(2);

    public String getTokenUri() {
      return tokenUri;
    }

    public void setTokenUri(String tokenUri) {
      this.tokenUri = tokenUri;
    }

    public String getClientId() {
      return clientId;
    }

    public void setClientId(String clientId) {
      this.clientId = clientId;
    }

    public String getClientSecret() {
      return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
      this.clientSecret = clientSecret;
    }

    public Duration getRefreshSkew() {
      return refreshSkew;
    }

    public void setRefreshSkew(Duration refreshSkew) {
      this.refreshSkew = refreshSkew;
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

  public ServiceToken getServiceToken() {
    return serviceToken;
  }

  public void setServiceToken(ServiceToken serviceToken) {
    this.serviceToken = serviceToken;
  }

  public Authz getAuthz() {
    return authz;
  }

  public void setAuthz(Authz authz) {
    this.authz = authz;
  }
}
