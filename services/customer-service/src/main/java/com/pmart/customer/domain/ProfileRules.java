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

  /**
   * 실명(2026-11-25 BR-107): 닉네임과 같은 규칙 — 1~30자, 줄바꿈·제어 문자 불가. 빈 문자열은 "제공자 이름으로 되돌리기"라 여기서 보지 않는다.
   */
  public static Optional<String> nameProblem(String raw) {
    String v = raw.strip();
    if (v.codePointCount(0, v.length()) > NICKNAME_MAX) {
      return Optional.of("이름은 " + NICKNAME_MAX + "자까지입니다.");
    }
    if (v.codePoints().anyMatch(Character::isISOControl)) {
      return Optional.of("이름에 줄바꿈·제어 문자를 쓸 수 없습니다.");
    }
    return Optional.empty();
  }

  public static @Nullable String normalizeNickname(@Nullable String raw) {
    return raw == null ? null : raw.strip();
  }

  /** 별명이 없을 때 리뷰 작성자 표시. */
  public static final String ANONYMOUS_AUTHOR = "PMart 고객";

  /**
   * 리뷰 작성자 표시(계약 0.2.0 review-author): 별명 첫 글자 + 나머지 글자 수만큼 *(최대 4개). 별명이 없거나 비면 {@link
   * #ANONYMOUS_AUTHOR}. 글자는 코드 포인트로 센다.
   */
  public static String maskedAuthor(@Nullable String nickname) {
    String v = nickname == null ? "" : nickname.strip();
    if (v.isEmpty()) {
      return ANONYMOUS_AUTHOR;
    }
    int first = v.codePointAt(0);
    int rest = Math.min(4, Math.max(1, v.codePointCount(0, v.length()) - 1));
    return new String(Character.toChars(first)) + "*".repeat(rest);
  }

  /** 탈퇴 신청 이후(WITHDRAW_PENDING·WITHDRAW_COMPLETED)는 프로필을 바꿀 수 없다(계약 409 INVALID_STATE). */
  public static boolean changeable(String status) {
    return "ACTIVE".equals(status);
  }

  /** 휴대폰 번호(2026-11-19 BR-32): 01X 로 시작하는 10~11자리. 하이픈·공백·괄호는 뗀다. */
  private static final java.util.regex.Pattern MOBILE =
      java.util.regex.Pattern.compile("^01[016789][0-9]{7,8}$");

  /**
   * 저장할 휴대폰 번호(숫자만). 빈 문자열이면 지우기({@code Optional.empty()}). 형식이 틀리면 예외 대신 null 을 돌려주지 않고 — 호출자가
   * {@link #phoneProblem} 으로 먼저 본다.
   */
  public static java.util.Optional<String> normalizePhone(String phone) {
    String digits = phone.replaceAll("[\\s\\-()]", "");
    return digits.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(digits);
  }

  /** 휴대폰 번호 문제(사유). 빈 문자열(지우기)은 문제 아님. */
  public static java.util.Optional<String> phoneProblem(String phone) {
    java.util.Optional<String> n = normalizePhone(phone);
    if (n.isEmpty()) {
      return java.util.Optional.empty();
    }
    return MOBILE.matcher(n.get()).matches()
        ? java.util.Optional.empty()
        : java.util.Optional.of("휴대폰 번호 형식이 아닙니다(01X 로 시작하는 10~11자리).");
  }

  /** 가린 번호 010-****-5678(10자리는 011-***-5678). 번호가 없으면 null. */
  public static @org.jspecify.annotations.Nullable String maskPhone(
      @org.jspecify.annotations.Nullable String digits) {
    if (digits == null || digits.length() < 10) {
      return null;
    }
    String head = digits.substring(0, 3);
    String tail = digits.substring(digits.length() - 4);
    String mid = "*".repeat(digits.length() - 7);
    return head + "-" + mid + "-" + tail;
  }

  /**
   * 직원 화면용 이름 가림(2026-11-20 CU-3·CU-4): 첫·끝 글자만, 두 글자면 첫 글자 + *, 한 글자면 * — 라이더·주문 직원 상세와 같은 방식
   * (ADR-0062). 이름이 없으면 별명, 둘 다 없으면 null.
   */
  public static @Nullable String maskName(@Nullable String name, @Nullable String nickname) {
    String v = name != null && !name.isBlank() ? name : nickname;
    if (v == null || v.isBlank()) {
      return null;
    }
    int[] cp = v.strip().codePoints().toArray();
    if (cp.length <= 1) {
      return "*";
    }
    if (cp.length == 2) {
      return new String(cp, 0, 1) + "*";
    }
    return new String(cp, 0, 1) + "*".repeat(cp.length - 2) + new String(cp, cp.length - 1, 1);
  }

  /** 가린 이메일(예 a***@k***) — 기존 계정 안내(2026-11-20 ADR-0074, 라이더와 같은 모양). 없거나 형식이 아니면 null. */
  public static @Nullable String maskEmail(@Nullable String email) {
    if (email == null || !email.contains("@")) {
      return null;
    }
    String[] parts = email.split("@", 2);
    String local = parts[0].isEmpty() ? "*" : parts[0].substring(0, 1);
    String domain = parts[1].isEmpty() ? "*" : parts[1].substring(0, 1);
    return local + "***@" + domain + "***";
  }
}
