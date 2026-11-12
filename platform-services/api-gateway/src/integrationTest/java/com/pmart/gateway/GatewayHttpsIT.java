package com.pmart.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * HTTPS 입구(ADR-0057, {@code pmart.gateway.https}) — 8080 HTTP 옆에 TLS 포트를 하나 더 열고 같은 보안·라우팅 규칙을 쓰는지.
 * 인증서는 시험 전용 자체 서명(src/integrationTest/resources/tls).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "spring.data.redis.url=redis://localhost:1",
      "pmart.gateway.rate-limit.enabled=false",
      "management.tracing.export.enabled=false",
      "pmart.gateway.https.port=-1", // 무작위 포트
      "pmart.gateway.https.bundle=it-tls",
      "spring.ssl.bundle.pem.it-tls.keystore.certificate=classpath:tls/it-cert.pem",
      "spring.ssl.bundle.pem.it-tls.keystore.private-key=classpath:tls/it-key.pem"
    })
class GatewayHttpsIT {

  @LocalServerPort int httpPort;
  @Autowired GatewayHttpsServer https;

  static HttpClient trusting() throws Exception {
    KeyStore ts = KeyStore.getInstance(KeyStore.getDefaultType());
    ts.load(null, null);
    try (InputStream in = GatewayHttpsIT.class.getResourceAsStream("/tls/it-cert.pem")) {
      ts.setCertificateEntry("it", CertificateFactory.getInstance("X.509").generateCertificate(in));
    }
    TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(ts);
    SSLContext ctx = SSLContext.getInstance("TLS");
    ctx.init(null, tmf.getTrustManagers(), null);
    return HttpClient.newBuilder().sslContext(ctx).build();
  }

  static HttpResponse<String> get(HttpClient c, String url) throws Exception {
    return c.send(
        HttpRequest.newBuilder(URI.create(url)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void httpsPortServesTheSameGateway() throws Exception {
    assertThat(https.port()).isPositive().isNotEqualTo(httpPort);
    HttpClient c = trusting();
    HttpResponse<String> health = get(c, "https://localhost:" + https.port() + "/actuator/health");
    assertThat(health.statusCode()).isEqualTo(200);
    assertThat(health.body()).contains("\"status\":\"UP\"");
    // 같은 보안 체인 — 토큰 없는 보호 경로는 401 Problem
    HttpResponse<String> protectedPath =
        get(c, "https://localhost:" + https.port() + "/api/v1/carts/me");
    assertThat(protectedPath.statusCode()).isEqualTo(401);
    assertThat(protectedPath.body()).contains("UNAUTHENTICATED");
    // HTTP 입구도 그대로
    assertThat(
            get(HttpClient.newHttpClient(), "http://localhost:" + httpPort + "/actuator/health")
                .statusCode())
        .isEqualTo(200);
  }

  @Test
  void untrustedClientIsRejected() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                get(
                    HttpClient.newHttpClient(),
                    "https://localhost:" + https.port() + "/actuator/health"))
        .isInstanceOf(SSLHandshakeException.class);
  }
}
