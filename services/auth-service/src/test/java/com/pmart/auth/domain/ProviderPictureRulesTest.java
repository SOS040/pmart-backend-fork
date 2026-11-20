package com.pmart.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderPictureRulesTest {

  static final String OK = "https://phinf.pstatic.net/contact/20240101_1/abc.jpg";

  @Test
  void naverAppPictureOnlyWhenTokenHasNone() {
    assertThat(ProviderPictureRules.pick("NAVER", null, OK)).isEqualTo(OK);
    assertThat(ProviderPictureRules.pick("NAVER", "https://x.pstatic.net/t.jpg", OK))
        .isEqualTo("https://x.pstatic.net/t.jpg");
    assertThat(ProviderPictureRules.pick("KAKAO", null, OK)).isNull();
    assertThat(ProviderPictureRules.pick("GOOGLE", "https://lh3.googleusercontent.com/a", OK))
        .isEqualTo("https://lh3.googleusercontent.com/a");
  }

  @Test
  void onlyHttpsPstaticWithoutPortOrUserInfo() {
    assertThat(ProviderPictureRules.naver("http://phinf.pstatic.net/a.jpg")).isNull();
    assertThat(ProviderPictureRules.naver("https://evil.example/a.jpg")).isNull();
    assertThat(ProviderPictureRules.naver("https://pstatic.net.evil.example/a.jpg")).isNull();
    assertThat(ProviderPictureRules.naver("https://evilpstatic.net/a.jpg")).isNull();
    assertThat(ProviderPictureRules.naver("https://u:p@phinf.pstatic.net/a.jpg")).isNull();
    assertThat(ProviderPictureRules.naver("https://phinf.pstatic.net:8443/a.jpg")).isNull();
    assertThat(ProviderPictureRules.naver("https://phinf.pstatic.net/" + "a".repeat(1000)))
        .isNull();
    assertThat(ProviderPictureRules.naver("not a url")).isNull();
    assertThat(ProviderPictureRules.naver(OK)).isEqualTo(OK);
  }
}
