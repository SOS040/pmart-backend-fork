package com.pmart.employeeauth.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.employeeauth.infrastructure.EmployeeAuthStore;
import com.pmart.employeeauth.infrastructure.EmployeeAuthStore.AccountRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * hr-service 직원 사건(platform.employee.v1, ADR-0056 결정 8)을 로그인 상태에 반영한다. inbox 트랜잭션 안에서 불린다(같은 사건 두 번
 * 반영 없음).
 *
 * <ul>
 *   <li>EmployeeRegistered: 직원 계정을 ACTIVE 로(없으면 만들고 — 권한은 없음), 근무 시작일, Keycloak 사용자 ↔ 운영자 ID
 *       연결(hr-service 가 정본).
 *   <li>EmployeeSuspended·EmployeeLeft: DISABLED·LEFT, 모든 세션 종료, {@code SubjectBlocked}(이미 나간 토큰 즉시
 *       차단).
 *   <li>EmployeeReinstated: ACTIVE(권한은 그대로 — 끝난 권한은 다시 요청).
 *   <li>사건 버전(aggregateVersion)이 이미 반영한 것보다 작거나 같으면 버린다(늦게 온 옛 사건). 등록 전에 온 상태 사건은 버린다 — hr-service
 *       는 발급이 끝난 뒤에만 상태 사건을 낸다.
 * </ul>
 */
@Component
public class EmployeeEventHandler {

  private static final Logger log = LoggerFactory.getLogger(EmployeeEventHandler.class);
  public static final List<String> TYPES =
      List.of("EmployeeRegistered", "EmployeeSuspended", "EmployeeReinstated", "EmployeeLeft");

  private final EmployeeAuthStore store;
  private final SubjectBlocks blocks;
  private final Clock clock;

  public EmployeeEventHandler(EmployeeAuthStore store, SubjectBlocks blocks, Clock clock) {
    this.store = store;
    this.blocks = blocks;
    this.clock = clock;
  }

  public void on(EventEnvelope e) {
    JsonNode p = e.payload();
    String operatorId = p.path("operatorId").asText(null);
    if (operatorId == null || operatorId.isBlank()) {
      throw new IllegalArgumentException("operatorId 없음"); // 계약 위반 — 바인더가 DLT 로
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    AccountRow current = store.account(operatorId, true).orElse(null);
    if (current != null
        && current.hrVersion() != null
        && e.aggregateVersion() <= current.hrVersion()) {
      log.info(
          "늦게 온 직원 사건 버림: {} operatorId={} v{}", e.eventType(), operatorId, e.aggregateVersion());
      return;
    }
    switch (e.eventType()) {
      case "EmployeeRegistered" -> registered(e, operatorId, p, now);
      case "EmployeeReinstated" -> {
        if (current == null) {
          log.warn("등록 전 복귀 사건 버림: operatorId={}", operatorId);
          return;
        }
        store.applyHr(
            operatorId,
            EmployeeLoginService.ACTIVE,
            date(p, "startDate"),
            e.aggregateVersion(),
            now);
        log.info("직원 복귀 반영: operatorId={}", operatorId);
      }
      case "EmployeeSuspended", "EmployeeLeft" -> {
        if (current == null) {
          log.warn("등록 전 {} 버림: operatorId={}", e.eventType(), operatorId);
          return;
        }
        String state = "EmployeeLeft".equals(e.eventType()) ? "LEFT" : "DISABLED";
        Instant effective = instant(p, "effectiveAt", now);
        store.applyHr(operatorId, state, null, e.aggregateVersion(), now);
        int revoked = store.revokeAllSessions(operatorId, "ACCOUNT_" + state, now);
        long version = store.account(operatorId, false).orElseThrow().version();
        // 효력 시각과 지금 중 늦은 쪽까지 발급된 토큰을 막는다(사건이 늦게 와도 그 사이 받은 토큰까지)
        blocks.block(
            operatorId,
            version,
            effective.isAfter(now) ? effective : now,
            new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, "hr-service"));
        log.info("직원 {} 반영: operatorId={} 세션 {}개 종료·즉시 차단", state, operatorId, revoked);
      }
      default -> log.debug("모르는 직원 사건 무시: {}", e.eventType());
    }
  }

  private void registered(EventEnvelope e, String operatorId, JsonNode p, Instant now) {
    store.applyHr(
        operatorId, EmployeeLoginService.ACTIVE, date(p, "startDate"), e.aggregateVersion(), now);
    String keycloakUserId = p.path("keycloakUserId").asText(null);
    if (keycloakUserId != null
        && !keycloakUserId.isBlank()
        && !store.relink(keycloakUserId, operatorId, now)) {
      log.warn("Keycloak 사용자가 다른 운영자 ID 에 이미 연결돼 있어 연결하지 않음: operatorId={}", operatorId);
    }
    log.info("직원 등록 반영: operatorId={}", operatorId);
  }

  private static @Nullable LocalDate date(JsonNode p, String field) {
    String v = p.path(field).asText(null);
    return v == null || v.isBlank() ? null : LocalDate.parse(v);
  }

  private static Instant instant(JsonNode p, String field, Instant fallback) {
    String v = p.path(field).asText(null);
    return v == null || v.isBlank() ? fallback : Instant.parse(v);
  }
}
