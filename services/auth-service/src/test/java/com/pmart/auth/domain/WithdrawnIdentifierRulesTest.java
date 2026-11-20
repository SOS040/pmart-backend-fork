package com.pmart.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class WithdrawnIdentifierRulesTest {

  @Test
  void hashIsSha256HexOfProviderColonSubject() {
    // printf 'GOOGLE:abc' | sha256sum
    assertThat(WithdrawnIdentifierRules.hash("GOOGLE", "abc"))
        .isEqualTo("170262c2c4eb50634c505c85b95c7db623ab46aadb04a47d785eade693677884");
    // 같은 sub 라도 제공자가 다르면 다른 값, 원래 sub 는 들어 있지 않다
    assertThat(WithdrawnIdentifierRules.hash("KAKAO", "abc"))
        .isNotEqualTo(WithdrawnIdentifierRules.hash("GOOGLE", "abc"))
        .doesNotContain("abc");
  }

  @Test
  void expiresFiveCalendarYearsLater() {
    assertThat(WithdrawnIdentifierRules.expiresAt(Instant.parse("2026-11-20T03:00:00Z")))
        .isEqualTo(Instant.parse("2031-11-20T03:00:00Z"));
    // 윤일은 2월 28일로
    assertThat(WithdrawnIdentifierRules.expiresAt(Instant.parse("2028-02-29T00:00:00Z")))
        .isEqualTo(Instant.parse("2033-02-28T00:00:00Z"));
  }
}
