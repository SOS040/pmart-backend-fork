package com.pmart.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

class OperatorRulesTest {

  static final Instant NOW = Instant.parse("2026-10-30T09:00:00Z");

  @Test
  void lockAfterMaxFailuresAndResetCount() {
    var first = OperatorRules.fail(0, 5, Duration.ofMinutes(15), NOW);
    assertThat(first.failedCount()).isEqualTo(1);
    assertThat(first.lockedUntil()).isNull();
    var fifth = OperatorRules.fail(4, 5, Duration.ofMinutes(15), NOW);
    assertThat(fifth.failedCount()).isZero();
    assertThat(fifth.lockedUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    assertThat(OperatorRules.locked(fifth.lockedUntil(), NOW.plusSeconds(60))).isTrue();
    assertThat(OperatorRules.locked(fifth.lockedUntil(), NOW.plus(Duration.ofMinutes(16))))
        .isFalse();
    assertThat(OperatorRules.locked(null, NOW)).isFalse();
  }

  @Test
  void passwordPolicyLengthAndNotLoginId() {
    assertThat(OperatorRules.passwordProblem("short", "md01", 10)).isPresent();
    assertThat(OperatorRules.passwordProblem("md01md01md", "md01md01md", 10)).isPresent();
    assertThat(OperatorRules.passwordProblem("correct-horse-1", "md01", 10)).isEmpty();
  }

  @Test
  void loginIdAndRoleFormats() {
    assertThat(OperatorRules.LOGIN_ID.matcher("md.kim-01").matches()).isTrue();
    assertThat(OperatorRules.LOGIN_ID.matcher("Admin").matches()).isFalse();
    assertThat(OperatorRules.ROLE.matcher("PRODUCT_APPROVER").matches()).isTrue();
    assertThat(OperatorRules.ROLE.matcher("md").matches()).isFalse();
  }

  @Test
  void localBootstrapHashIsPmartLocal() {
    // application-local.yml 의 로컬 관리자(admin / pmart-local) 해시가 문서와 맞는지
    assertThat(
            PasswordEncoderFactories.createDelegatingPasswordEncoder()
                .matches(
                    "pmart-local",
                    "{bcrypt}$2a$10$Qut13u4vwUA83/mKRLIfwOJFI5P5ZJWUVbSYxzWim244YpORIaYKG"))
        .isTrue();
  }
}
