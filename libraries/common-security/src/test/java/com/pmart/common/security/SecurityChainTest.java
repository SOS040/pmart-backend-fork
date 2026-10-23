package com.pmart.common.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 기본 SecurityFilterChain: permit / internal(TYPE_SERVICE) / authenticated, 401·403 problem+json.
 */
@SpringBootTest(
    classes = SecurityChainTest.App.class,
    properties = {"spring.application.name=sec-test", "spring.main.banner-mode=off"})
@AutoConfigureMockMvc
class SecurityChainTest {

  @SpringBootApplication
  static class App {
    /** 실제 JWKS 대신 테스트용 디코더(jwt() post-processor 가 Jwt 를 직접 넣으므로 호출되지 않음). */
    @Bean
    JwtDecoder jwtDecoder() {
      return token -> {
        throw new IllegalStateException("not used in tests");
      };
    }

    @RestController
    static class Api {
      @GetMapping("/api/v1/orders")
      String orders() {
        return "ok";
      }

      @PostMapping({"/internal/reservations", "/api/v1/internal/reservations"})
      String reserve() {
        return "reserved";
      }

      @GetMapping("/actuator/health")
      String health() {
        return "UP";
      }
    }
  }

  @Autowired MockMvc mvc;

  @Test
  void anonymousIsRejectedWithProblemJson() throws Exception {
    mvc.perform(get("/api/v1/orders"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
        .andExpect(jsonPath("$.status").value(401));
  }

  @Test
  void permitPathsAreOpen() throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  @Test
  void authenticatedCustomerCanCallApi() throws Exception {
    mvc.perform(
            get("/api/v1/orders").with(jwt().jwt(j -> j.subject("acc-1").claim("typ", "CUSTOMER"))))
        .andExpect(status().isOk());
  }

  /** 계약 servers.url 이 /api/v1 이라 실제 내부 경로는 /api/v1/internal/**. 사용자 토큰은 403. */
  @Test
  void versionedInternalPathAlsoRequiresServiceToken() throws Exception {
    mvc.perform(
            post("/api/v1/internal/reservations")
                .with(
                    jwt()
                        .jwt(j -> j.subject("acc-1").claim("typ", "CUSTOMER"))
                        .authorities(
                            j ->
                                new PmartJwtAuthenticationConverter().convert(j).getAuthorities())))
        .andExpect(status().isForbidden());

    mvc.perform(
            post("/api/v1/internal/reservations")
                .with(
                    jwt()
                        .jwt(j -> j.subject("order-service").claim("typ", "SERVICE"))
                        .authorities(
                            j ->
                                new PmartJwtAuthenticationConverter().convert(j).getAuthorities())))
        .andExpect(status().isOk());
  }

  @Test
  void internalRequiresServiceToken() throws Exception {
    mvc.perform(
            post("/internal/reservations")
                .with(
                    jwt()
                        .jwt(j -> j.subject("acc-1").claim("typ", "CUSTOMER"))
                        .authorities(
                            j ->
                                new PmartJwtAuthenticationConverter().convert(j).getAuthorities())))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    mvc.perform(
            post("/internal/reservations")
                .with(
                    jwt()
                        .jwt(
                            j ->
                                j.subject("order-orchestration-service")
                                    .claim("typ", "SERVICE")
                                    .claim("roles", List.of("internal")))
                        .authorities(
                            j ->
                                new PmartJwtAuthenticationConverter().convert(j).getAuthorities())))
        .andExpect(status().isOk());
  }
}
