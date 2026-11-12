package com.pmart.hr.application;

import com.pmart.common.messaging.EventPublisher;
import com.pmart.hr.infrastructure.HrStore.EmployeeRow;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 직원 사건(계약 {@code contracts/events/Employee*.v1.json}, 토픽 platform.employee.v1, 키 = 운영자 ID). 호출자
 * 트랜잭션 안(outbox). 역할·권한은 싣지 않는다(권한 정본은 employee-auth-service — ADR-0056 결정 4). 이름은 싣지 않는다(구독자가 쓰지
 * 않는 개인정보).
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
