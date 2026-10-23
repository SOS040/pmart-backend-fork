package com.pmart.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ActionAuthorizerTest {

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private static void loginAs(String sub, String typ) {
    Jwt jwt =
        Jwt.withTokenValue("tok")
            .header("alg", "none")
            .subject(sub)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .claim("typ", typ)
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, List.of(), sub));
  }

  @Test
  void deniesWhenUnauthenticatedWithoutCallingAuthService() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://auth");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    ActionAuthorizer authz = new ActionAuthorizer(builder.build());
    ActionAuthorizer.Decision d =
        authz.decide(new ActionAuthorizer.Request("APPROVE_PRICE", "PriceChange", "pc-1", null));
    assertThat(d.allowed()).isFalse();
    assertThat(d.reasonCode()).isEqualTo("UNAUTHENTICATED");
    server.verify();
  }

  @Test
  void allowsOnAllowDecisionAndSendsActorTargetScope() {
    loginAs("op-1", "OPERATOR");
    RestClient.Builder builder = RestClient.builder().baseUrl("http://auth");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("http://auth/internal/authorizations"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.actor.principalId").value("op-1"))
        .andExpect(header("Idempotency-Key", org.hamcrest.Matchers.notNullValue()))
        .andExpect(jsonPath("$.action").value("APPROVE_PRICE"))
        .andExpect(jsonPath("$.target.id").value("pc-1"))
        .andExpect(jsonPath("$.centerScope[0]").value("MFC-BSN-01"))
        .andRespond(
            withSuccess(
                "{\"decision\":\"ALLOW\",\"decisionId\":\"d-9\"}", MediaType.APPLICATION_JSON));
    ActionAuthorizer authz = new ActionAuthorizer(builder.build());

    ActionAuthorizer.Decision d =
        authz.require(
            new ActionAuthorizer.Request("APPROVE_PRICE", "PriceChange", "pc-1", "MFC-BSN-01"));

    assertThat(d.allowed()).isTrue();
    assertThat(d.decisionId()).isEqualTo("d-9");
    server.verify();
  }

  @Test
  void denyCarriesFirstReasonFromContract() {
    loginAs("op-1", "OPERATOR");
    RestClient.Builder builder = RestClient.builder().baseUrl("http://auth");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("http://auth/internal/authorizations"))
        .andExpect(jsonPath("$.target.requesterPrincipalId").value("op-1"))
        .andRespond(
            withSuccess(
                "{\"decision\":\"DENY\",\"decisionId\":\"d-2\",\"reasons\":[\"SELF_APPROVAL\"]}",
                MediaType.APPLICATION_JSON));
    ActionAuthorizer authz = new ActionAuthorizer(builder.build());

    ActionAuthorizer.Decision d =
        authz.decide(
            new ActionAuthorizer.Request("APPROVE", "APPROVAL", "ap-1", null, null, "op-1"));

    assertThat(d.allowed()).isFalse();
    assertThat(d.reasonCode()).isEqualTo("SELF_APPROVAL");
    assertThat(d.decisionId()).isEqualTo("d-2");
    server.verify();
  }

  @Test
  void failsClosedWhenAuthServiceErrors() {
    loginAs("op-1", "OPERATOR");
    RestClient.Builder builder = RestClient.builder().baseUrl("http://auth");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("http://auth/internal/authorizations")).andRespond(withServerError());
    ActionAuthorizer authz = new ActionAuthorizer(builder.build());

    assertThatThrownBy(() -> authz.require(new ActionAuthorizer.Request("X", "T", "1", null)))
        .isInstanceOf(ActionAuthorizer.AuthorizationDeniedException.class)
        .hasMessageContaining("DEPENDENCY_UNAVAILABLE");
  }
}
