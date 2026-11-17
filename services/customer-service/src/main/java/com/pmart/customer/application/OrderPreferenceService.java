package com.pmart.customer.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.customer.domain.ProfileRules;
import com.pmart.customer.infrastructure.CustomerStore;
import com.pmart.customer.infrastructure.CustomerStore.ProfileRow;
import com.pmart.customer.infrastructure.OrderPreferenceStore;
import com.pmart.customer.infrastructure.OrderPreferenceStore.Row;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 주문 기본 설정(2026-11-18, 계약 0.4.0 — 고객 앱 "다음에도 이 품절 처리 적용"). 앱이 주문서 기본값으로만 쓴다 — order-service 는 읽지
 * 않는다(주문마다 초안 값이 정본).
 *
 * <ul>
 *   <li>저장한 적 없으면 값 null·version 0(지어내지 않음).
 *   <li>즉시 반영, 바뀌면 version +1, 같은 값이면 그대로. X-Expected-Version 이 다르면 409 VERSION_CONFLICT.
 *   <li>탈퇴 신청 이후(ACTIVE 아님)는 409 INVALID_STATE. 상태·버전 검사는 멱등 처리 안(재시도가 처음 결과를 받게).
 * </ul>
 */
@Service
public class OrderPreferenceService {

  public static final String OPERATION = "updateMyOrderPreferences";

  /** order-service 주문 초안 outOfStockPreference 와 같은 값(contracts/openapi/order-service.yaml). */
  public static final Set<String> OUT_OF_STOCK_VALUES = Set.of("CANCEL_OOS_ONLY", "CANCEL_ALL");

  private static final Logger log = LoggerFactory.getLogger(OrderPreferenceService.class);

  /** 응답(멱등 기록에도 이 모양으로 남는다). updatedAt 은 ISO-8601 문자열. */
  public record View(
      @Nullable String outOfStockPreference, int version, @Nullable String updatedAt) {
    static View of(Row r) {
      return new View(r.outOfStockPreference(), r.version(), r.updatedAt().toString());
    }
  }

  private static final View NEVER_SAVED = new View(null, 0, null);

  private final OrderPreferenceStore store;
  private final CustomerStore customers;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public OrderPreferenceService(
      OrderPreferenceStore store,
      CustomerStore customers,
      RequestIdempotency idempotency,
      Clock clock) {
    this.store = store;
    this.customers = customers;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public View get(String accountId) {
    return store.find(accountId).map(View::of).orElse(NEVER_SAVED);
  }

  public View update(
      String accountId,
      String requestId,
      String sourceSystem,
      @Nullable Integer expectedVersion,
      @Nullable String outOfStockPreference) {
    if (outOfStockPreference == null || !OUT_OF_STOCK_VALUES.contains(outOfStockPreference)) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED,
          "outOfStockPreference 는 CANCEL_OOS_ONLY 또는 CANCEL_ALL 이어야 합니다.",
          List.of(
              new PmartException.Detail("outOfStockPreference", "CANCEL_OOS_ONLY 또는 CANCEL_ALL")));
    }
    customers.ensureProfile(accountId, clock.instant());
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("outOfStockPreference", outOfStockPreference);
    request.put("expectedVersion", expectedVersion);
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, OPERATION, accountId),
            request,
            View.class,
            () -> {
              Instant now = clock.instant();
              // 계정 행 잠금 — 같은 계정의 설정 변경을 줄 세우고 탈퇴 상태를 본다
              ProfileRow p =
                  customers
                      .lockProfile(accountId)
                      .orElseThrow(
                          () -> PmartException.notFound("ACCOUNT_NOT_FOUND", "고객 계정이 없습니다."));
              if (!ProfileRules.changeable(p.status())) {
                throw PmartException.invalidState("탈퇴 신청 이후에는 주문 설정을 바꿀 수 없습니다.");
              }
              Row current = store.find(accountId).orElse(null);
              int currentVersion = current == null ? 0 : current.version();
              if (expectedVersion != null && expectedVersion != currentVersion) {
                throw versionConflict();
              }
              if (current == null) {
                if (store.insert(accountId, outOfStockPreference, now) == 0) {
                  throw versionConflict();
                }
              } else if (outOfStockPreference.equals(current.outOfStockPreference())) {
                return View.of(current);
              } else if (store.update(accountId, current.version(), outOfStockPreference, now)
                  == 0) {
                throw versionConflict();
              }
              log.info("주문 설정 변경 {} 버전 {}", accountId, currentVersion + 1);
              return new View(outOfStockPreference, currentVersion + 1, now.toString());
            })
        .value();
  }

  private static PmartException versionConflict() {
    return PmartException.versionConflict("주문 설정이 그 사이 바뀌었습니다. 다시 조회하세요.");
  }
}
