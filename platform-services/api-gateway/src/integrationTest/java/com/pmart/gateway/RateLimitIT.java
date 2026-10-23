package com.pmart.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

/**
 * 처리율 제한: 실제 Redis 위의 토큰 버킷. 버킷 크기를 2 로 줄여 세 번째 요청이 429 Problem 인지, 버킷이 IP·subject 단위로 분리되는지, 예외
 * 경로는 제한하지 않는지 확인한다. 클라이언트 IP 는 {@code X-Forwarded-For}(trusted-proxies=1) 로 구분한다.
 */
// MOCK: mockJwt() 가 동작하려면 WebTestClient 가 컨텍스트에 바인딩돼야 한다(GatewayRoutingSecurityTest 와 같음).
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "spring.data.redis.url=",
      "management.tracing.enabled=false",
      "pmart.gateway.rate-limit.anonymous.replenish-rate=1",
      "pmart.gateway.rate-limit.anonymous.burst-capacity=2",
      "pmart.gateway.rate-limit.authenticated.replenish-rate=1",
      "pmart.gateway.rate-limit.authenticated.burst-capacity=2"
    })
@AutoConfigureWebTestClient
@Testcontainers
@EnableWireMock(
    @ConfigureWireMock(
        name = "upstream",
        baseUrlProperties = {
          "pmart.gateway.upstream.storefront-service",
          "pmart.gateway.upstream.order-service",
          "pmart.gateway.upstream.payment-service"
        }))
class RateLimitIT {

  private static final AtomicInteger IP_SEQ = new AtomicInteger();

  @Container
  @ServiceConnection(name = "redis")
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @Autowired WebTestClient client;

  @InjectWireMock("upstream")
  WireMockServer upstream;

  @BeforeEach
  void stubUpstream() {
    upstream.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("{}")));
  }

  @Test
  void anonymousRequestsBeyondBurstGetTooManyRequestsProblem() {
    String ip = uniqueIp();
    anonymousHome(ip)
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("X-RateLimit-Remaining", "1");
    anonymousHome(ip).expectStatus().isOk();
    anonymousHome(ip)
        .expectStatus()
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        .expectHeader()
        .contentType("application/problem+json")
        .expectHeader()
        .valueEquals("Retry-After", "1")
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("RATE_LIMITED")
        .jsonPath("$.status")
        .isEqualTo(429)
        .jsonPath("$.retryable")
        .isEqualTo(true);

    // 다른 IP 는 별도 버킷
    anonymousHome(uniqueIp()).expectStatus().isOk();
  }

  @Test
  void authenticatedRequestsAreLimitedPerSubject() {
    String subject = "cust-" + UUID.randomUUID();
    for (int i = 0; i < 2; i++) {
      ordersAs(subject).expectStatus().isOk();
    }
    ordersAs(subject).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    ordersAs("cust-" + UUID.randomUUID()).expectStatus().isOk();
  }

  @Test
  void exemptPathsAreNotLimited() {
    String ip = uniqueIp();
    for (int i = 0; i < 5; i++) {
      client
          .post()
          .uri("/api/v1/webhooks/toss")
          .header("X-Forwarded-For", ip)
          .bodyValue("{}")
          .exchange()
          .expectStatus()
          .isOk();
    }
  }

  private WebTestClient.ResponseSpec anonymousHome(String ip) {
    return client.get().uri("/api/v1/storefront/home").header("X-Forwarded-For", ip).exchange();
  }

  private WebTestClient.ResponseSpec ordersAs(String subject) {
    return client
        .mutateWith(mockJwt().jwt(j -> j.subject(subject)))
        .get()
        .uri("/api/v1/orders/ord-1")
        .exchange();
  }

  private static String uniqueIp() {
    int n = IP_SEQ.incrementAndGet();
    return "203.0." + (n / 250) + "." + (n % 250 + 1);
  }
}
