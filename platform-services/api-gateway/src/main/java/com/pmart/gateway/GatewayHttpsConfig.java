package com.pmart.gateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.HttpHandler;

/**
 * {@code pmart.gateway.https.port} 가 0 이 아니면 HTTPS 입구를 하나 더 연다(ADR-0057). 설정
 * 값은 @ConfigurationPropertiesScan 이 올린다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${pmart.gateway.https.port:0}' != '0'")
class GatewayHttpsConfig {

  @Bean
  GatewayHttpsServer gatewayHttpsServer(
      GatewayHttpsServer.Properties properties, SslBundles bundles, HttpHandler httpHandler) {
    return new GatewayHttpsServer(properties, bundles, httpHandler);
  }
}
