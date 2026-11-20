package com.pmart.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.auth.infrastructure.NaverTokenClient;
import com.pmart.common.web.PmartException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** 네이버 코드 교환 설정·redirectUri 검사(ADR-0078) — 네이버를 부르기 전에 끝나는 경로. 교환·멱등은 NaverCodeExchangeIT. */
class NaverCodeExchangeServiceTest {

  static final String CALLBACK =
      "http://192.168.0.40:8080/api/v1/auth/oauth-callbacks/naver/customer";
  static final String VERIFIER = "v".repeat(43);

  static CustomerProperties props(
      @Nullable List<String> clientIds, @Nullable String secret, @Nullable String callback) {
    return new CustomerProperties(
        null,
        null,
        null,
        false,
        null,
        null,
        0,
        null,
        null,
        null,
        new CustomerProperties.Naver(clientIds, null, null, secret, callback, null, null),
        false);
  }

  /** 불리면 세는 가짜 클라이언트. */
  static class CountingClient extends NaverTokenClient {
    final AtomicInteger calls = new AtomicInteger();

    CountingClient(CustomerProperties p) {
      super(p);
    }

    @Override
    public String exchange(String tokenBaseUrl, Request r) {
      calls.incrementAndGet();
      return "id-token";
    }
  }

  static PmartException run(CustomerProperties p, CountingClient client, String redirectUri) {
    // 멱등 기록 전에 끝나는 경로라 RequestIdempotency 는 쓰이지 않는다
    NaverCodeExchangeService s = new NaverCodeExchangeService(p, client, null);
    try {
      s.exchange("k-1", "customer-app", "code", VERIFIER, redirectUri, null);
    } catch (PmartException e) {
      return e;
    }
    throw new AssertionError("예외가 나야 한다");
  }

  @Test
  void missingSecretOrCallbackOrClientIdIsConfigRequired() {
    for (CustomerProperties p :
        List.of(
            props(List.of("naver-client"), null, CALLBACK),
            props(List.of("naver-client"), " ", CALLBACK),
            props(List.of("naver-client"), "secret", null),
            props(null, "secret", CALLBACK))) {
      CountingClient client = new CountingClient(p);
      PmartException e = run(p, client, CALLBACK);
      assertThat(e.status().value()).isEqualTo(422);
      assertThat(e.code()).isEqualTo("CONFIG_REQUIRED");
      assertThat(client.calls.get()).isZero();
    }
  }

  @Test
  void redirectUriMustMatchCallbackExactly() {
    CustomerProperties p = props(List.of("naver-client"), "secret", CALLBACK);
    CountingClient client = new CountingClient(p);
    PmartException e = run(p, client, CALLBACK + "/");
    assertThat(e.status().value()).isEqualTo(400);
    assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
    assertThat(client.calls.get()).isZero();
  }

  @Test
  void exchangeSettingsUseFirstClientIdDefaultsAndHideSecret() {
    CustomerProperties p = props(List.of(" naver-client ", "other"), " secret ", CALLBACK);
    CustomerProperties.NaverExchange x = p.naverExchange();
    assertThat(x.clientId()).isEqualTo("naver-client");
    assertThat(x.clientSecret()).isEqualTo("secret");
    assertThat(x.tokenBaseUrl()).isEqualTo("https://nid.naver.com");
    assertThat(x.tokenTimeout()).isEqualTo(Duration.ofSeconds(5));
    assertThat(x.toString()).doesNotContain("secret=secret").contains("clientSecret=***");
    // ID 토큰 검증 쪽 값은 예전 그대로
    assertThat(p.naver().clientIds()).containsExactly("naver-client", "other");
    assertThat(p.naver().issuers()).containsExactly("https://nid.naver.com");
  }

  @Test
  void requestToStringHidesValues() {
    var r = new NaverTokenClient.Request("id", "sec", "code", VERIFIER, CALLBACK, "st");
    assertThat(r.toString()).doesNotContain("sec").doesNotContain("code").doesNotContain(VERIFIER);
    assertThatThrownBy(
            () -> {
              throw new NaverTokenClient.Rejected("invalid_grant");
            })
        .hasMessageContaining("invalid_grant");
  }
}
