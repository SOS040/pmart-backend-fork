package com.pmart.common.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * 기본 SecurityFilterChain: permit / internal(토큰 없이 서비스 주체, ADR-0026) / authenticated, 401·403
 * problem+json.
 */
@SpringBootTest(
    classes = SecurityChainTest.App.class,
    properties = {
      "spring.application.name=sec-test",
      "spring.main.banner-mode=off",
      "pmart.security.guest-paths=/api/v1/carts/**"
    })
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
        // 호출자 = 서비스 주체(토큰 없는 내부 호출은 X-Source-System)
        return PmartPrincipal.current().map(p -> p.type() + ":" + p.subject()).orElse("none");
      }

      @GetMapping("/api/v1/carts/{id}")
      String cart() {
        return "cart";
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

  /**
   * 내부 경로(ADR-0026): 토큰 없이 들어오고 호출자는 X-Source-System 이름의 서비스 주체. 헤더가 없으면 "internal". 계약 servers.url
   * 이 /api/v1 이라 실제 경로는 /api/v1/internal/**.
   */
  @Test
  void internalPathNeedsNoTokenAndUsesSourceSystem() throws Exception {
    mvc.perform(post("/api/v1/internal/reservations").header("X-Source-System", "order-service"))
        .andExpect(status().isOk())
        .andExpect(content().string("SERVICE:order-service"));
    mvc.perform(post("/internal/reservations"))
        .andExpect(status().isOk())
        .andExpect(content().string("SERVICE:internal"));
    // 기존 서비스 토큰도 그대로 받는다(호출자 = 토큰 주체)
    mvc.perform(
            post("/internal/reservations")
                .with(
                    jwt()
                        .jwt(j -> j.subject("order-orchestration-service").claim("typ", "SERVICE"))
                        .authorities(
                            j ->
                                new PmartJwtAuthenticationConverter().convert(j).getAuthorities())))
        .andExpect(status().isOk())
        .andExpect(content().string("SERVICE:order-orchestration-service"));
  }

  /** 사용자 토큰(고객 등)으로 내부 경로를 부르면 403 — 내부 API 는 서비스 사이 전용이다. */
  @Test
  void userTokenOnInternalPathIsForbidden() throws Exception {
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
  }

  /** 비회원 토큰(typ=GUEST)은 guest-paths 에서만. 그 밖은 403(회원 전용 API 보호). 회원 토큰은 둘 다 된다. */
  @Test
  void guestTokenOnlyOnGuestPaths() throws Exception {
    mvc.perform(get("/api/v1/carts/C-1").with(token("GST-1", "GUEST"))).andExpect(status().isOk());
    mvc.perform(get("/api/v1/orders").with(token("GST-1", "GUEST")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mvc.perform(post("/api/v1/internal/reservations").with(token("GST-1", "GUEST")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/carts/C-1").with(token("acc-1", "CUSTOMER")))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/carts/C-1")).andExpect(status().isUnauthorized());
  }

  static org.springframework.test.web.servlet.request.RequestPostProcessor token(
      String subject, String typ) {
    return jwt()
        .jwt(j -> j.subject(subject).claim("typ", typ))
        .authorities(j -> new PmartJwtAuthenticationConverter().convert(j).getAuthorities());
  }
}
