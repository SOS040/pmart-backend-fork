package com.pmart.gateway.security;

import com.pmart.common.jwt.IssuerSettings;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 발급자 표 {@code pmart.security.issuers}(ADR-0054) — 서비스(common-security)와 같은 설정 키. 비어 있으면 지금처럼
 * {@code jwk-set-uri} 디코더 하나와 {@code typ} 클레임으로 동작한다.
 */
@ConfigurationProperties(prefix = "pmart.security")
public class GatewayIssuerProperties {

  private List<IssuerSettings> issuers = List.of();

  public List<IssuerSettings> getIssuers() {
    return issuers;
  }

  public void setIssuers(List<IssuerSettings> issuers) {
    this.issuers = issuers;
  }
}
