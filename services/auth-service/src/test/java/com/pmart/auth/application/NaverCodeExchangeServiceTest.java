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
        new CustomerProperties.Naver(clientIds, null, null, secret, callback, null, null, null),
        false);
  }

  /** 불리면 세는 가짜 클라이언트. */
  static class CountingClient extends NaverTokenClient {
    final AtomicInteger calls = new AtomicInteger();

    CountingClient(CustomerProperties p) {
      super(p);
    }

    @Override
    public Tokens exchange(String tokenBaseUrl, Request r) {
      calls.incrementAndGet();
      return new Tokens("id-token", "access");
    }
  }

  /** 프로필 API 결과를 정해 두는 가짜 클라이언트(BR-70). */
  static class ProfileClient extends NaverTokenClient {
    final @Nullable String image;
    final boolean fail;
    final AtomicInteger calls = new AtomicInteger();

    ProfileClient(CustomerProperties p, @Nullable String image, boolean fail) {
      super(p);
      this.image = image;
      this.fail = fail;
    }

    @Override
    public @Nullable String profileImage(String profileBaseUrl, String accessToken) {
      calls.incrementAndGet();
      if (fail) {
        throw new IllegalStateException("boom");
      }
      return image;
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
    assertThat(x.profileBaseUrl()).isEqualTo("https://openapi.naver.com");
    assertThat(x.toString()).doesNotContain("secret=secret").contains("clientSecret=***");
    // ID 토큰 검증 쪽 값은 예전 그대로
    assertThat(p.naver().clientIds()).containsExactly("naver-client", "other");
    assertThat(p.naver().issuers()).containsExactly("https://nid.naver.com");
  }

  @Test
  void requestToStringHidesValues() {
    var r = new NaverTokenClient.Request("id", "sec", "code", VERIFIER, CALLBACK, "st");
    assertThat(r.toString()).doesNotContain("sec").doesNotContain("code").doesNotContain(VERIFIER);
    assertThat(new NaverTokenClient.Tokens("id.token", "access-secret").toString())
        .doesNotContain("id.token")
        .doesNotContain("access-secret");
    assertThat(new NaverCodeExchangeService.Exchanged("id.token", "https://a.pstatic.net/p.jpg"))
        .asString()
        .doesNotContain("id.token")
        .doesNotContain("pstatic");
    assertThatThrownBy(
            () -> {
              throw new NaverTokenClient.Rejected("invalid_grant");
            })
        .hasMessageContaining("invalid_grant");
  }

  @Test
  void profileImageOnlyPstaticHttpsAndNeverFails() {
    CustomerProperties p = props(List.of("naver-client"), "secret", CALLBACK);
    String ok = "https://phinf.pstatic.net/contact/a.jpg";
    var cfg = p.naverExchange();

    assertThat(
            new NaverCodeExchangeService(p, new ProfileClient(p, ok, false), null)
                .profileImage(cfg, "access"))
        .isEqualTo(ok);
    assertThat(
            new NaverCodeExchangeService(
                    p, new ProfileClient(p, "http://phinf.pstatic.net/a.jpg", false), null)
                .profileImage(cfg, "access"))
        .isNull();
    assertThat(
            new NaverCodeExchangeService(
                    p, new ProfileClient(p, "https://evil.example/a.jpg", false), null)
                .profileImage(cfg, "access"))
        .isNull();
    assertThat(
            new NaverCodeExchangeService(p, new ProfileClient(p, null, false), null)
                .profileImage(cfg, "access"))
        .isNull();
    // 클라이언트가 예외를 던져도 교환은 그대로(사진만 없음)
    assertThat(
            new NaverCodeExchangeService(p, new ProfileClient(p, ok, true), null)
                .profileImage(cfg, "access"))
        .isNull();
    // access token 이 없으면 부르지 않는다
    ProfileClient none = new ProfileClient(p, ok, false);
    assertThat(new NaverCodeExchangeService(p, none, null).profileImage(cfg, null)).isNull();
    assertThat(none.calls.get()).isZero();
  }
}
