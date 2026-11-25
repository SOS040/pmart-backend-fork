package com.pmart.riderauth.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderPicturesTest {

  /** 2026-11-25: 제공자마다 그 사진 호스트 https 만, 카카오 http 는 https 로. */
  @Test
  void onlyProviderHostsOverHttps() {
    assertThat(ProviderPictures.of("GOOGLE", "https://lh3.googleusercontent.com/a/x=s96-c"))
        .isEqualTo("https://lh3.googleusercontent.com/a/x=s96-c");
    assertThat(ProviderPictures.of("KAKAO", "http://k.kakaocdn.net/dn/a.jpg"))
        .isEqualTo("https://k.kakaocdn.net/dn/a.jpg");
    assertThat(ProviderPictures.of("NAVER", "https://phinf.pstatic.net/a.png"))
        .isEqualTo("https://phinf.pstatic.net/a.png");
    assertThat(ProviderPictures.of("NAVER", "http://phinf.pstatic.net/a.png")).isNull();
    assertThat(ProviderPictures.of("GOOGLE", "https://phinf.pstatic.net/a.png")).isNull();
    assertThat(ProviderPictures.of("KAKAO", "https://evil.example/kakaocdn.net.jpg")).isNull();
    assertThat(ProviderPictures.of("GOOGLE", "https://u@lh3.googleusercontent.com/a")).isNull();
    assertThat(ProviderPictures.of("GOOGLE", "https://lh3.googleusercontent.com:8443/a")).isNull();
    assertThat(ProviderPictures.of("APPLE", "https://lh3.googleusercontent.com/a")).isNull();
    assertThat(ProviderPictures.of("GOOGLE", null)).isNull();
  }
}
