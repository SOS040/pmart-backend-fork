package com.pmart.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ServiceTokenProviderTest {

  private static final String TOKEN_URI = "http://auth/api/v1/internal/service-tokens";

  /** 테스트에서 시각을 옮기는 Clock. */
  static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-10-23T00:00:00Z");

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private static String tokenJson(String token, int expiresIn) {
    return "{\"access_token\":\"%s\",\"token_type\":\"Bearer\",\"expires_in\":%d}"
        .formatted(token, expiresIn);
  }

  @Test
  void requestsWithClientCredentialsAndCachesUntilSkewBeforeExpiry() {
    MutableClock clock = new MutableClock();
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    String basic =
        Base64.getEncoder()
            .encodeToString("order-service:s3cr%3Dt".getBytes(StandardCharsets.UTF_8));
    server
        .expect(once(), requestTo(TOKEN_URI))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Basic " + basic))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
        .andExpect(content().formDataContains(java.util.Map.of("grant_type", "client_credentials")))
        .andRespond(withSuccess(tokenJson("t-1", 300), MediaType.APPLICATION_JSON));
    server
        .expect(once(), requestTo(TOKEN_URI))
        .andRespond(withSuccess(tokenJson("t-2", 300), MediaType.APPLICATION_JSON));
    ServiceTokenProvider provider =
        new ServiceTokenProvider(
            builder.build(), TOKEN_URI, "order-service", "s3cr=t", Duration.ofSeconds(30), clock);

    assertThat(provider.token()).isEqualTo("t-1");
    clock.now = clock.now.plusSeconds(269);
    assertThat(provider.token()).as("만료 30초 전까지는 캐시").isEqualTo("t-1");
    clock.now = clock.now.plusSeconds(1);
    assertThat(provider.token()).as("만료 30초 전부터 새로 발급").isEqualTo("t-2");
    server.verify();
  }

  @Test
  void failureIsReportedNotSwallowed() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo(TOKEN_URI)).andRespond(withServerError());
    ServiceTokenProvider provider =
        new ServiceTokenProvider(
            builder.build(),
            TOKEN_URI,
            "order-service",
            "x",
            Duration.ofSeconds(30),
            Clock.systemUTC());

    assertThatThrownBy(provider::token)
        .isInstanceOf(ServiceTokenProvider.ServiceTokenUnavailableException.class);
  }

  @Test
  void internalCallsCarryServiceTokenOtherCallsPropagateUserToken() {
    Jwt user =
        Jwt.withTokenValue("user-token")
            .header("alg", "none")
            .subject("acc-1")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(user, List.of(), "acc-1"));

    ServiceTokenProvider provider =
        new ServiceTokenProvider(
            RestClient.create(), TOKEN_URI, "x", "x", Duration.ofSeconds(30), Clock.systemUTC()) {
          @Override
          public String token() {
            return "service-token";
          }
        };
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("serviceTokenProvider", provider);
    RestClient.Builder builder = RestClient.builder();
    new ServiceCallHeadersCustomizer(
            "order-service", beans.getBeanProvider(ServiceTokenProvider.class))
        .customize(builder);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("http://inventory/api/v1/internal/reservations"))
        .andExpect(header("Authorization", "Bearer service-token"))
        .andExpect(header("X-Source-System", "order-service"))
        .andRespond(withSuccess());
    server
        .expect(requestTo("http://inventory/api/v1/stocks"))
        .andExpect(header("Authorization", "Bearer user-token"))
        .andRespond(withSuccess());
    server
        .expect(requestTo("http://inventory/api/v1/internal/explicit"))
        .andExpect(header("Authorization", "Bearer explicit"))
        .andRespond(withSuccess());
    RestClient client = builder.build();

    client
        .post()
        .uri("http://inventory/api/v1/internal/reservations")
        .retrieve()
        .toBodilessEntity();
    client.get().uri("http://inventory/api/v1/stocks").retrieve().toBodilessEntity();
    client
        .get()
        .uri("http://inventory/api/v1/internal/explicit")
        .header("Authorization", "Bearer explicit")
        .retrieve()
        .toBodilessEntity();
    server.verify();
  }
}
