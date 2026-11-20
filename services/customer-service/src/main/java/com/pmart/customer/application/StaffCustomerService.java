package com.pmart.customer.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.customer.infrastructure.AuditClient;
import com.pmart.customer.infrastructure.CustomerStore;
import com.pmart.customer.infrastructure.CustomerStore.ProfileRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * 직원용 고객 조회(2026-11-20 CU-3·CU-4 — 백오피스 "고객" 메뉴, 사용자 "응 그대로 진행해"). 권한 CS_AGENT·OPERATOR_ADMIN.
 *
 * <ul>
 *   <li>찾기는 고객번호·전화 완전 일치 또는 고객 ID 여러 개(화면 표기) — 이름 검색·전체 목록 없음(BackOffice 개인정보, 목적별 제한)
 *   <li>응답은 가린 값만. 원문은 사유 + audit-service 동기 기록 뒤에만(ADR-0062 — rider 직원 상세와 같은 방식). 기록을 못 남기면 원문도
 *       없다
 *   <li>찾는 전화번호·사유·원문은 로그에 남기지 않는다
 * </ul>
 */
@Service
public class StaffCustomerService {

  public enum PiiField {
    NAME,
    PHONE
  }

  public record Reveal(
      String customerId,
      String piiAccessLogId,
      Instant recordedAt,
      List<PiiField> fields,
      @Nullable String name,
      @Nullable String phone) {}

  public static final List<String> ROLES = List.of("CS_AGENT", "OPERATOR_ADMIN");
  static final int MAX_IDS = 50;
  static final int REASON_MIN = 2;
  static final int REASON_MAX = 200;

  private final CustomerStore store;
  private final AuditClient audit;
  private final Clock clock;

  public StaffCustomerService(CustomerStore store, AuditClient audit, Clock clock) {
    this.store = store;
    this.audit = audit;
    this.clock = clock;
  }

  /** 셋 중 하나만. */
  public List<ProfileRow> search(
      @Nullable String customerNumber, @Nullable String phone, @Nullable String customerIds) {
    requireStaff();
    int given =
        (blank(customerNumber) ? 0 : 1) + (blank(phone) ? 0 : 1) + (blank(customerIds) ? 0 : 1);
    if (given != 1) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "customerNumber·phone·customerIds 중 하나만 주세요.");
    }
    if (!blank(customerNumber)) {
      return store.byCustomerNumber(customerNumber.strip());
    }
    if (!blank(phone)) {
      return store.byPhone(normalizePhone(phone));
    }
    return store.byIds(ids(customerIds));
  }

  public ProfileRow detail(String customerId) {
    requireStaff();
    return get(customerId);
  }

  public Reveal reveal(
      String customerId,
      String idempotencyKey,
      String clientSource,
      @Nullable String reason,
      @Nullable Set<PiiField> fields) {
    PmartPrincipal p = requireStaff();
    int n = reason == null ? 0 : reason.strip().length();
    if (n < REASON_MIN || n > REASON_MAX) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED,
          "reason(열람 사유)은 " + REASON_MIN + "~" + REASON_MAX + "자여야 합니다.");
    }
    Set<PiiField> wanted =
        fields == null || fields.isEmpty() ? EnumSet.allOf(PiiField.class) : EnumSet.copyOf(fields);
    ProfileRow c = get(customerId);
    List<PiiField> ordered = new ArrayList<>(wanted);

    Map<String, Object> actor = new LinkedHashMap<>();
    actor.put("actorId", p.subject());
    actor.put("actorType", "OPERATOR");
    actor.put("roles", p.roles());
    actor.put("centerScope", p.centerScope());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("occurredAt", clock.instant().toString());
    body.put("actor", actor);
    body.put("subjectType", "CUSTOMER");
    body.put("subjectRef", customerId);
    body.put("accessType", "VIEW");
    body.put("fields", ordered.stream().map(Enum::name).toList());
    body.put("purpose", reason.strip());
    body.put("screen", truncate(clientSource, 80) + ":customer-detail");
    body.put("caseRef", "CUSTOMER:" + customerId);
    body.put("recordCount", 1);
    AuditClient.Recorded rec =
        audit.recordPiiAccess(auditRequestId(p.subject(), customerId, idempotencyKey), body);
    return new Reveal(
        customerId,
        rec.piiAccessLogId(),
        rec.recordedAt(),
        ordered,
        wanted.contains(PiiField.NAME) ? c.name() : null,
        wanted.contains(PiiField.PHONE) ? c.phone() : null);
  }

  /** audit 기록 멱등 키: 직원·고객·클라이언트 키의 SHA-256(다른 직원과 섞이지 않게 — rider·order 와 같은 방식). */
  static String auditRequestId(String actor, String customerId, String idempotencyKey) {
    try {
      byte[] d =
          MessageDigest.getInstance("SHA-256")
              .digest(
                  (actor + "\n" + customerId + "\n" + idempotencyKey)
                      .getBytes(StandardCharsets.UTF_8));
      return "customer-pii-reveal:" + HexFormat.of().formatHex(d);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);
    }
  }

  /** 전화번호 검색어 → 숫자만(하이픈·공백 무시). 휴대전화 형식(01로 시작 10~11자리)이 아니면 400 — 라이더 찾기와 같은 규칙. */
  static String normalizePhone(String phone) {
    String digits = phone.replaceAll("[\\s-]", "");
    if (!digits.matches("01[0-9]{8,9}")) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "phone 은 휴대전화 번호(01로 시작하는 숫자 10~11자리)여야 합니다.");
    }
    return digits;
  }

  static List<String> ids(String raw) {
    List<String> list =
        Arrays.stream(raw.split(","))
            .map(String::strip)
            .filter(x -> !x.isEmpty())
            .distinct()
            .toList();
    if (list.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "customerIds 가 비었습니다.");
    }
    if (list.size() > MAX_IDS) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "customerIds 는 " + MAX_IDS + "개까지입니다.");
    }
    return list;
  }

  private ProfileRow get(String customerId) {
    return store
        .profile(customerId)
        .orElseThrow(() -> PmartException.notFound("CUSTOMER_NOT_FOUND", "고객이 없습니다."));
  }

  static PmartPrincipal requireStaff() {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> forbidden("직원만 고객을 조회할 수 있습니다."));
    if (ROLES.stream().noneMatch(p::hasRole)) {
      throw forbidden("고객 조회 권한(고객센터 상담원·운영 관리자)이 없습니다.");
    }
    return p;
  }

  private static PmartException forbidden(String message) {
    return new PmartException(ErrorCode.FORBIDDEN, message);
  }

  private static boolean blank(@Nullable String v) {
    return v == null || v.isBlank();
  }

  private static String truncate(String v, int max) {
    return v.length() <= max ? v : v.substring(0, max);
  }
}
