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

  /** 서비스 간 전용 경로. TYPE_SERVICE 권한(서비스 토큰) 필요. */
  private List<String> internalPaths = List.of("/internal/**");

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
