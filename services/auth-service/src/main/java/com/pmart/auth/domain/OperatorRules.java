package com.pmart.auth.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 운영자 로그인 규칙(ADR-0018).
 *
 * <ul>
 *   <li>잠금 중이면 비밀번호가 맞아도 거절한다(잠금 해제 시각까지). 잠금은 연속 실패가 한도에 이르면 걸리고 실패 수는 0 으로 돌아간다.
 *   <li>로그인 성공은 실패 수를 0 으로 되돌린다.
 *   <li>비밀번호: 최소 길이, 아이디와 같으면 안 된다.
 * </ul>
 */
public final class OperatorRules {

  public static final Pattern LOGIN_ID = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,63}$");
  public static final Pattern ROLE = Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");

  private OperatorRules() {}

  public static boolean locked(@Nullable Instant lockedUntil, Instant now) {
    return lockedUntil != null && lockedUntil.isAfter(now);
  }

  /** 실패 1회 반영 결과. */
  public record Failure(int failedCount, @Nullable Instant lockedUntil) {}

  public static Failure fail(int failedCount, int maxAttempts, Duration lock, Instant now) {
    int next = failedCount + 1;
    return next >= maxAttempts ? new Failure(0, now.plus(lock)) : new Failure(next, null);
  }

  /** 비밀번호 정책 위반 사유. */
  public static Optional<String> passwordProblem(String password, String loginId, int minLength) {
    if (password.length() < minLength) {
      return Optional.of("비밀번호는 " + minLength + "자 이상이어야 합니다.");
    }
    if (password.equalsIgnoreCase(loginId)) {
      return Optional.of("비밀번호를 아이디와 같게 할 수 없습니다.");
    }
    if (password.isBlank()) {
      return Optional.of("비밀번호가 비어 있습니다.");
    }
    return Optional.empty();
  }
}
