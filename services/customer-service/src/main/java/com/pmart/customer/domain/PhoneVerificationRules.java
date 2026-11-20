package com.pmart.customer.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;

/**
 * 휴대폰 번호 확인 규칙(2026-11-19, 계약 0.9.0 — OCTOMO). OCTOMO 는 010 으로 시작하는 11자리 숫자만 받는다. 보낼 본문은 숫자 6자리(완전
 * 일치로 확인 — 고객이 고친 본문은 맞지 않는다).
 */
public final class PhoneVerificationRules {

  private PhoneVerificationRules() {}

  public static final String PENDING = "PENDING";
  public static final String VERIFIED = "VERIFIED";
  public static final String FAILED = "FAILED";
  public static final String EXPIRED = "EXPIRED";
  public static final String TOO_MANY_ATTEMPTS = "TOO_MANY_ATTEMPTS";
  public static final String PHONE_CHANGED = "PHONE_CHANGED";

  /** /me 확인에서 다른 계정의 확인 번호(2026-11-20 ADR-0074) — 그 계정은 알려 주지 않는다. */
  public static final String PHONE_ALREADY_REGISTERED = "PHONE_ALREADY_REGISTERED";

  private static final SecureRandom RANDOM = new SecureRandom();

  /** 저장된(숫자만) 번호가 OCTOMO 로 확인할 수 있는 번호인가 — 010 + 8자리. */
  public static boolean supported(@Nullable String phone) {
    return phone != null && phone.matches("^010\\d{8}$");
  }

  /** 보낼 문자 본문 — 숫자 6자리(앞자리 0 도 그대로). */
  public static String newCode() {
    return String.format("%06d", RANDOM.nextInt(1_000_000));
  }

  /** 번호 원문을 두지 않고 바뀌었는지만 보기 위한 해시. */
  public static String phoneHash(String phone) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(("phone-verification:" + phone).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * 가입 쿠폰 중복 방지 키(2026-11-21 ADR-0082 5단계) — HMAC-SHA256(비밀값, 번호). 비밀값 없이는 번호로 되돌릴 수 없다(번호 공간이 작아
   * 그냥 해시는 대입으로 풀린다). promotion 이 탈퇴 뒤에도 보관한다(처리방침 2026-11-21).
   */
  public static String signupKey(String secret, String phone) {
    try {
      javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
      mac.init(
          new javax.crypto.spec.SecretKeySpec(
              secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of()
          .formatHex(mac.doFinal(("signup-coupon:" + phone).getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public static int remaining(int maxAttempts, int attempts) {
    return Math.max(0, maxAttempts - attempts);
  }
}
