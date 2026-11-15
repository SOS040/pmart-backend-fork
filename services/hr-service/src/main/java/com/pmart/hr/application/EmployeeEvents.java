package com.pmart.hr.application;

import com.pmart.common.messaging.EventPublisher;
import com.pmart.hr.infrastructure.HrStore;
import com.pmart.hr.infrastructure.HrStore.EmployeeRow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * 직원 사건(계약 {@code contracts/events/Employee*.v1.json}, 토픽 platform.employee.v1, 키 = 운영자 ID). 호출자
 * 트랜잭션 안(outbox). 직원 사건에는 역할·권한을 싣지 않는다(권한 정본은 employee-auth-service — ADR-0056 결정 4). 요청 권한은 따로
 * GrantRequested(같은 토픽) — approval-service 가 승인해야 효력. 이름은 싣지 않는다(구독자가 쓰지 않는 개인정보).
 */
@Component
public class EmployeeEvents {

  private static final String AGGREGATE = "Employee";

  private final EventPublisher publisher;

  public EmployeeEvents(EventPublisher publisher) {
    this.publisher = publisher;
  }

  /** 계정 발급 완료. */
  public void registered(EmployeeRow e, Instant now) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("operatorId", e.operatorId());
    p.put("employeeNo", e.employeeNo());
    p.put("keycloakUserId", Objects.requireNonNull(e.keycloakUserId()));
    p.put("mailAddress", e.mailAddress());
    p.put("employmentType", e.employmentType());
    p.put("affiliation", Map.of("type", e.affiliationType(), "code", e.affiliationCode()));
    p.put("startDate", e.startDate().toString());
    if (e.endDate() != null) {
      p.put("endDate", e.endDate().toString());
    }
    publish("EmployeeRegistered", e, p);
  }

  /**
   * 등록 화면에서 받은 요청 권한을 approval-service 에 올린다(계약 GrantRequested, ADR-0056 5-4). 비워 받은 기간은 근무 기간(시작일
   * ~ 계약 종료일). 행 키 = 요청 권한 행 ID — ApprovalDecided.lines[].lineId 로 돌아온다.
   */
  public void grantRequested(
      EmployeeRow e, String requestId, List<HrStore.GrantRow> grants, Instant now) {
    grantRequested(e, requestId, grants, now, e.registeredBy());
  }

  /** 재직 중 권한 추가·변경 요청(BO-01, 2026-11-15) — 요청자 = 요청한 인사팀 직원(승인자와 달라야 한다). */
  public void grantRequested(
      EmployeeRow e,
      String requestId,
      List<HrStore.GrantRow> grants,
      Instant now,
      String requestedBy) {
    List<Map<String, Object>> lines = new ArrayList<>();
    for (HrStore.GrantRow g : grants) {
      Map<String, Object> l = new LinkedHashMap<>();
      l.put("lineKey", Long.toString(g.grantId()));
      l.put("role", g.role());
      l.put("centers", g.centers());
      l.put("validFrom", (g.validFrom() != null ? g.validFrom() : e.startDate()).toString());
      LocalDate to = g.validTo() != null ? g.validTo() : e.endDate();
      l.put("validTo", to == null ? null : to.toString());
      l.put("reason", g.reason());
      lines.add(l);
    }
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("requestId", requestId);
    p.put("operatorId", e.operatorId());
    p.put("requestedBy", requestedBy);
    p.put("employeeNo", e.employeeNo());
    p.put("mailAddress", e.mailAddress());
    p.put("affiliation", Map.of("type", e.affiliationType(), "code", e.affiliationCode()));
    p.put("lines", lines);
    p.put("requestedAt", now.toString());
    publish("GrantRequested", e, p);
  }

  /**
   * 소속 변경(2026-11-16 BO-01) — 이전 소속이 센터면 그 센터 grant 를 {@code previousCenterAccessUntil} 에 끝내라고
   * employee-auth-service 에 알린다. {@code e} 는 바뀐 뒤 행(버전 포함).
   */
  public void affiliationChanged(
      EmployeeRow e,
      String previousType,
      String previousCode,
      Instant now,
      @Nullable Instant previousCenterAccessUntil) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("operatorId", e.operatorId());
    p.put("previous", Map.of("type", previousType, "code", previousCode));
    p.put("affiliation", Map.of("type", e.affiliationType(), "code", e.affiliationCode()));
    p.put("changedAt", now.toString());
    if (previousCenterAccessUntil != null) {
      p.put("previousCenterAccessUntil", previousCenterAccessUntil.toString());
    }
    publish("EmployeeAffiliationChanged", e, p);
  }

  /** 지금 재직 상태를 알리는 사건(정지·복귀·퇴사). ACTIVE 면 Reinstated. */
  public void statusChanged(EmployeeRow e, Instant now) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("operatorId", e.operatorId());
    p.put("effectiveAt", now.toString());
    switch (e.status()) {
      case "SUSPENDED" -> {
        p.put("reason", "CONTRACT_ENDED".equals(e.statusReason()) ? "CONTRACT_ENDED" : "SUSPENDED");
        publish("EmployeeSuspended", e, p);
      }
      case "LEFT" -> publish("EmployeeLeft", e, p);
      default -> {
        p.put("startDate", e.startDate().toString());
        if (e.endDate() != null) {
          p.put("endDate", e.endDate().toString());
        }
        publish("EmployeeReinstated", e, p);
      }
    }
  }

  private void publish(String type, EmployeeRow e, Map<String, Object> payload) {
    publisher.publish(type, 1, AGGREGATE, e.operatorId(), e.version(), null, payload);
  }
}
