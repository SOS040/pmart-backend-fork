package com.pmart.customer.domain;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** 프로필 변경 규칙(계약 POST /me/profile-changes). */
public final class ProfileRules {

  /** 계약 maxLength 와 같다. 글자 수는 사람이 보는 글자(코드 포인트)로 센다. */
  public static final int NICKNAME_MAX = 30;

  private ProfileRules() {}

  /** 닉네임 정규화: 앞뒤 공백을 떼고, 비어 있거나 30자를 넘거나 제어 문자(줄바꿈 등)가 있으면 거절 사유를 돌려준다. 정상이면 빈 값. */
  public static Optional<String> nicknameProblem(@Nullable String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String v = raw.strip();
    if (v.isEmpty()) {
      return Optional.of("닉네임은 비울 수 없습니다.");
    }
    if (v.codePointCount(0, v.length()) > NICKNAME_MAX) {
      return Optional.of("닉네임은 " + NICKNAME_MAX + "자까지입니다.");
    }
    if (v.codePoints().anyMatch(Character::isISOControl)) {
      return Optional.of("닉네임에 줄바꿈·제어 문자를 쓸 수 없습니다.");
    }
    return Optional.empty();
  }

  public static @Nullable String normalizeNickname(@Nullable String raw) {
    return raw == null ? null : raw.strip();
  }

  /** 탈퇴 신청 이후(WITHDRAW_PENDING·WITHDRAW_COMPLETED)는 프로필을 바꿀 수 없다(계약 409 INVALID_STATE). */
  public static boolean changeable(String status) {
    return "ACTIVE".equals(status);
  }
}
