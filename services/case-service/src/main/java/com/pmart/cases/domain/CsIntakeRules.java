package com.pmart.cases.domain;

import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * CS 접수 규칙(0.12.0 — 2026-11-16 사용자 결정 BO-11) — 프레임워크 없음.
 *
 * <ul>
 *   <li>상담원이 받은 연락(전화·채팅·메일)만. 고객 앱 문의는 /inquiries.
 *   <li>본인 확인은 방법만 기록. 대리(PROXY)는 표시만 — 막지 않는다(대리 정책 없음).
 * </ul>
 */
public final class CsIntakeRules {

  private CsIntakeRules() {}

  /** 상담원이 남긴 문의의 출처 — 고객 앱에 보이지 않고 답변 알림을 내지 않는다. */
  public static final String ORIGIN_STAFF_INTAKE = "STAFF_INTAKE";

  public static final Set<String> CHANNELS = Set.of("PHONE", "CHAT", "EMAIL");
  public static final Set<String> WRITERS = Set.of(CaseRules.CS_AGENT, CaseRules.ADMIN);

  public static final String ORDER_DETAILS_MATCH = "ORDER_DETAILS_MATCH";
  public static final String APP_SIGNED_IN_CHAT = "APP_SIGNED_IN_CHAT";
  public static final String OTHER = "OTHER";

  /** 이력 action(case_history.action varchar(30)). */
  public static final String HISTORY_LINKED = "CS_INTAKE_LINKED";

  /** 요청 검사 — 어긋나면 사람이 읽을 이유, 맞으면 null. */
  public static @Nullable String rejection(
      String channel,
      String category,
      @Nullable String subjectRef,
      String identityMethod,
      @Nullable String identityNote) {
    if (!CHANNELS.contains(channel)) {
      return "channel 은 PHONE·CHAT·EMAIL 만 받습니다(고객 앱 문의는 /inquiries).";
    }
    if ("RIDER".equals(category)) {
      return "라이더 관련 연락은 라이더 컴플레인 기록(/rider-complaints)으로 남깁니다.";
    }
    if (ORDER_DETAILS_MATCH.equals(identityMethod) && blank(subjectRef)) {
      return "주문 내용 대조로 확인했으면 subjectRef(주문 ID)가 필요합니다.";
    }
    if (APP_SIGNED_IN_CHAT.equals(identityMethod) && !"CHAT".equals(channel)) {
      return "앱 로그인 채팅 확인은 channel CHAT 에서만 고를 수 있습니다.";
    }
    if (OTHER.equals(identityMethod) && blank(identityNote)) {
      return "본인 확인 방법이 기타면 어떻게 확인했는지(identityCheck.note)를 적어야 합니다.";
    }
    return null;
  }

  private static boolean blank(@Nullable String s) {
    return s == null || s.isBlank();
  }
}
