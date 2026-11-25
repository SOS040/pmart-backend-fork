package com.pmart.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProfileImageRulesTest {

  static final List<String> GOOGLE = List.of("googleusercontent.com");

  @Test
  void googlePhotoIsResizedAndAllowed() {
    assertThat(
            ProfileImageRules.fetchUri(
                "https://lh3.googleusercontent.com/a/ACg8abc=s96-c", GOOGLE, false, 256))
        .contains(URI.create("https://lh3.googleusercontent.com/a/ACg8abc=s256-c"));
    assertThat(
            ProfileImageRules.fetchUri(
                "https://lh3.googleusercontent.com/a/x=s96", GOOGLE, false, 256))
        .contains(URI.create("https://lh3.googleusercontent.com/a/x=s256-c"));
    // 크기 지정이 없으면 그대로
    assertThat(
            ProfileImageRules.fetchUri("https://lh3.googleusercontent.com/a/x", GOOGLE, false, 256))
        .contains(URI.create("https://lh3.googleusercontent.com/a/x"));
  }

  @Test
  void kakaoHttpPhotoIsUpgradedToHttps() {
    List<String> kakao = List.of("kakaocdn.net");
    assertThat(
            ProfileImageRules.fetchUri(
                "http://k.kakaocdn.net/dn/abc/img_640x640.jpg", kakao, false, 256))
        .contains(URI.create("https://k.kakaocdn.net/dn/abc/img_640x640.jpg"));
    // 허용 밖 호스트의 http 는 그대로 거절
    assertThat(ProfileImageRules.fetchUri("http://evil.example/a.jpg", kakao, false, 256))
        .isEmpty();
  }

  @Test
  void naverPhotoAsksCdnForSmallSize() {
    List<String> naver = List.of("pstatic.net");
    assertThat(
            ProfileImageRules.fetchUri(
                "https://phinf.pstatic.net/contact/20231010_1/abc.png", naver, false, 256))
        .contains(URI.create("https://phinf.pstatic.net/contact/20231010_1/abc.png?type=s160"));
    assertThat(
            ProfileImageRules.fetchUri(
                "https://phinf.pstatic.net/contact/x.png?type=f80_80", naver, false, 256))
        .contains(URI.create("https://phinf.pstatic.net/contact/x.png?type=s160"));
  }

  @Test
  void otherHostsSchemesAndTricksAreRejected() {
    for (String u :
        List.of(
            "http://lh3.googleusercontent.com/a/x", // https 만
            "https://evil.example/a/x",
            "https://googleusercontent.com.evil.example/a/x",
            "https://evilgoogleusercontent.com/a/x",
            "https://user@lh3.googleusercontent.com/a/x",
            "https://169.254.169.254/latest/meta-data",
            "file:///etc/passwd",
            "not a url",
            "")) {
      assertThat(ProfileImageRules.fetchUri(u, GOOGLE, false, 256)).as(u).isEmpty();
    }
    assertThat(ProfileImageRules.fetchUri(null, GOOGLE, false, 256)).isEmpty();
    // 시험 설정에서만 http
    assertThat(
            ProfileImageRules.fetchUri("http://localhost:1/a/x", List.of("localhost"), true, 256))
        .isPresent();
  }

  @Test
  void imageTypeComesFromTheBytesNotTheHeader() {
    assertThat(ProfileImageRules.imageType(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}))
        .contains("image/jpeg");
    assertThat(
            ProfileImageRules.imageType(
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0}))
        .contains("image/png");
    assertThat(
            ProfileImageRules.imageType(
                new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'}))
        .contains("image/webp");
    assertThat(ProfileImageRules.imageType("<html>".getBytes())).isEmpty();
    assertThat(ProfileImageRules.imageType(new byte[0])).isEmpty();
  }
}
