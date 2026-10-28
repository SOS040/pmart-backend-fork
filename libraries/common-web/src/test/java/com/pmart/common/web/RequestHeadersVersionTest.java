package com.pmart.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

class RequestHeadersVersionTest {

  private static RequestHeaders withVersion(Integer v) {
    return new RequestHeaders("req-1", "customer-app", v, null);
  }

  @Test
  void matchingOrAbsentExpectedVersionPasses() {
    assertThatCode(() -> withVersion(3).checkExpectedVersion(3)).doesNotThrowAnyException();
    assertThatCode(() -> withVersion(null).checkExpectedVersion(3)).doesNotThrowAnyException();
  }

  @Test
  void staleExpectedVersionIsVersionConflict() {
    assertThatThrownBy(() -> withVersion(2).checkExpectedVersion(3))
        .isInstanceOfSatisfying(
            PmartException.class,
            e -> {
              assertThat(e.code()).isEqualTo("VERSION_CONFLICT");
              assertThat(e.status().value()).isEqualTo(409);
              assertThat(e.retryable()).isTrue();
            });
  }

  @Test
  void requireExpectedVersionRejectsMissingHeader() {
    assertThat(withVersion(5).requireExpectedVersion()).isEqualTo(5);
    assertThatThrownBy(() -> withVersion(null).requireExpectedVersion())
        .isInstanceOfSatisfying(
            PmartException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
  }

  @Test
  void optimisticLockFailureOnSaveBecomesVersionConflict() {
    PmartException e =
        OptimisticLockAdvice.conflict(new OptimisticLockingFailureException("row was updated"));
    assertThat(e.code()).isEqualTo("VERSION_CONFLICT");
    assertThat(e.status().value()).isEqualTo(409);
  }
}
