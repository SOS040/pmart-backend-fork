package com.pmart.riderauth.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** PROVIDER_REJECTED 안내 문구가 로그인한 제공자 이름을 쓰는지(카카오·네이버에 "Google" 이 나오던 문제, 2026-11-24). */
class RiderLoginServiceMessageTest {

  @Test
  void rejectedMessageNamesTheProvider() {
    assertThat(RiderLoginService.providerRejected("GOOGLE").getMessage())
        .startsWith("Google 인증 결과를 확인할 수 없습니다");
    assertThat(RiderLoginService.providerRejected("KAKAO").getMessage())
        .startsWith("카카오 인증 결과를 확인할 수 없습니다");
    assertThat(RiderLoginService.providerRejected("NAVER").getMessage())
        .startsWith("네이버 인증 결과를 확인할 수 없습니다");
    assertThat(RiderLoginService.providerRejected(null).getMessage())
        .startsWith("로그인 제공자 인증 결과를 확인할 수 없습니다");
  }
}
