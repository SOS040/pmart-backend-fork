package com.pmart.order.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 직원용 주문 검색 규칙(2026-11-15, O-6 — 백오피스 고객센터·라이더 운영). 볼 수 있는 역할, 기간 상한(전체 훑기 방지), 연락처 완전 일치용 정규화, 목록의
 * 연락처 가림.
 */
public final class StaffOrderRules {

  /** 직원 주문 조회를 할 수 있는 역할(OPERATOR 토큰). 그 밖 역할은 403. */
  public static final Set<String> ROLES = Set.of("OPERATOR_ADMIN", "CS_AGENT", "RIDER_OPS");

  /** orderId·customerId 없이 검색할 때 기간 상한. */
  public static final Duration MAX_RANGE = Duration.ofDays(31);

  private StaffOrderRules() {}

  public static boolean allowed(Collection<String> roles) {
    return roles.stream().anyMatch(ROLES::contains);
  }

  /**
   * 기간 검사. 문제가 있으면 사유, 없으면 null.
   *
   * @param keyed orderId 또는 customerId 가 있는가(있으면 기간 생략 가능)
   */
  public static @Nullable String rangeProblem(
      boolean keyed, @Nullable Instant from, @Nullable Instant to) {
    if (from != null && to != null && !from.isBefore(to)) {
      return "createdFrom 은 createdTo 보다 앞이어야 합니다.";
    }
    if (keyed) {
      return null;
    }
    if (from == null || to == null) {
      return "orderId·customerId 가 없으면 createdFrom·createdTo 가 필요합니다.";
    }
    if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
      return "검색 기간은 31일 이하여야 합니다.";
    }
    return null;
  }

  /**
   * 연락처 검색어 → 숫자만(완전 일치 비교용). 숫자가 9~11자리가 아니면 null(검색어 오류).
   *
   * <p>부분 일치는 하지 않는다 — 개인정보.
   */
  public static @Nullable String normalizePhone(String raw) {
    String digits = raw.replaceAll("[^0-9]", "");
    if (digits.length() < 9 || digits.length() > 11) {
      return null;
    }
    return digits;
  }

  /** 원문으로 볼 수 있는 배송지 항목(0.4.0). 도로명주소·우편번호·출입 방법은 가리지 않아 항목이 없다. */
  public enum PiiField {
    /** receiverName */
    NAME,
    /** receiverPhone */
    PHONE,
    /** detailAddress + geo(핀) */
    ADDRESS,
    /** entranceNote(출입 비밀번호일 수 있음) */
    ENTRANCE
  }

  /** 가린 상세주소. */
  public static final String MASKED_DETAIL = "***";

  public static final int REASON_MIN = 2;
  public static final int REASON_MAX = 200;

  /** 열람 사유 검사(앞뒤 공백 제외 2~200자). 문제가 있으면 사유, 없으면 null. 사유 글은 메시지에 되돌려 싣지 않는다. */
  public static @Nullable String reasonProblem(@Nullable String reason) {
    int n = reason == null ? 0 : reason.strip().length();
    if (n < REASON_MIN || n > REASON_MAX) {
      return "reason(열람 사유)은 " + REASON_MIN + "~" + REASON_MAX + "자여야 합니다.";
    }
    return null;
  }

  /**
   * 직원 화면용 배송지 가림. {@code reveal} 에 든 항목만 원문, 나머지는 가린다: 이름 첫·끝 글자({@link #maskName}), 연락처 앞 3·끝
   * 4자리({@link #maskPhone}), 상세주소 {@value #MASKED_DETAIL}·핀 없음, 출입 메모 없음. 도로명주소·우편번호·출입 방법은
   * 그대로(권역·센터 판단용).
   */
  public static ShippingAddress mask(ShippingAddress s, Set<PiiField> reveal) {
    boolean address = reveal.contains(PiiField.ADDRESS);
    String phone =
        reveal.contains(PiiField.PHONE) ? s.receiverPhone() : maskPhone(s.receiverPhone());
    return new ShippingAddress(
        s.roadAddress(),
        address ? s.detailAddress() : MASKED_DETAIL,
        s.postalCode(),
        reveal.contains(PiiField.NAME) ? s.receiverName() : maskName(s.receiverName()),
        phone == null ? "****" : phone,
        s.entranceMethod(),
        reveal.contains(PiiField.ENTRANCE) ? s.entranceNote() : null,
        address ? s.lat() : null,
        address ? s.lng() : null,
        s.capturedAt());
  }

  /** 이름 가림: 첫·끝 글자만(홍길동 → 홍*동, 홍길 → 홍*, 남궁민수 → 남**수, 한 글자 → *). 앞뒤 공백은 뺀다. */
  public static @Nullable String maskName(@Nullable String name) {
    if (name == null) {
      return null;
    }
    int[] cp = name.strip().codePoints().toArray();
    if (cp.length <= 1) {
      return "*";
    }
    if (cp.length == 2) {
      return new String(cp, 0, 1) + "*";
    }
    return new String(cp, 0, 1) + "*".repeat(cp.length - 2) + new String(cp, cp.length - 1, 1);
  }

  /** 목록용 연락처 가림: 앞 3자리·끝 4자리만 남긴다(예 010-****-1234). 숫자가 8자리 미만이면 전부 가린다. */
  public static @Nullable String maskPhone(@Nullable String phone) {
    if (phone == null) {
      return null;
    }
    String d = phone.replaceAll("[^0-9]", "");
    if (d.length() < 8) {
      return "****";
    }
    String head = d.substring(0, 3);
    String tail = d.substring(d.length() - 4);
    return head + "-" + "*".repeat(d.length() - 7) + "-" + tail;
  }
}
