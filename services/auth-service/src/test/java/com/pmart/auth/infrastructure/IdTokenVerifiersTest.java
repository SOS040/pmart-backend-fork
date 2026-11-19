package com.pmart.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.common.web.PmartException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 제공자별 검증기 고르기(ADR-0071, 2026-11-19): 목록 밖(APPLE)은 501, 클라이언트 ID 가 없으면 422 CONFIG_REQUIRED. */
class IdTokenVerifiersTest {

  final IdTokenVerifier any = new IdTokenVerifier(token -> null);

  final IdTokenVerifiers verifiers =
      new IdTokenVerifiers(
          Map.of(
              "GOOGLE", new IdTokenVerifiers.Entry(any, List.of("web-client")),
              "KAKAO", new IdTokenVerifiers.Entry(any, List.of()),
              "NAVER", new IdTokenVerifiers.Entry(any, List.of("naver-client"))));

  @Test
  void picksConfiguredProvider() {
    assertThat(verifiers.require("GOOGLE")).isSameAs(any);
    assertThat(verifiers.require("NAVER")).isSameAs(any);
  }

  @Test
  void unconfiguredIsConfigRequiredAndUnknownIsUnsupported() {
    assertThatThrownBy(() -> verifiers.require("KAKAO"))
        .isInstanceOf(PmartException.class)
        .satisfies(
            e -> {
              assertThat(((PmartException) e).code()).isEqualTo("CONFIG_REQUIRED");
              assertThat(e.getMessage()).contains("pmart.auth.customer.kakao.client-ids");
            });
    assertThatThrownBy(() -> verifiers.require("APPLE"))
        .isInstanceOf(PmartException.class)
        .satisfies(e -> assertThat(((PmartException) e).code()).isEqualTo("UNSUPPORTED_ACTION"));
  }
}
