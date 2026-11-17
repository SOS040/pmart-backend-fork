package com.pmart.customer.api;

import com.pmart.customer.api.generated.PreferencesApi;
import com.pmart.customer.api.generated.model.OrderPreferences;
import com.pmart.customer.api.generated.model.UpdateMyOrderPreferencesRequest;
import com.pmart.customer.application.OrderPreferenceService;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 PreferencesApi — 주문 기본 설정(품절 처리, 0.4.0). 업무 규칙은 {@link OrderPreferenceService}. */
@RestController
public class PreferencesController implements PreferencesApi {

  private final OrderPreferenceService preferences;

  public PreferencesController(OrderPreferenceService preferences) {
    this.preferences = preferences;
  }

  @Override
  public ResponseEntity<OrderPreferences> getMyOrderPreferences() {
    return ResponseEntity.ok(response(preferences.get(CustomerMapping.currentCustomer())));
  }

  @Override
  public ResponseEntity<OrderPreferences> updateMyOrderPreferences(
      String idempotencyKey,
      String xSourceSystem,
      UpdateMyOrderPreferencesRequest body,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        response(
            preferences.update(
                CustomerMapping.currentCustomer(),
                idempotencyKey,
                xSourceSystem,
                xExpectedVersion,
                body.getOutOfStockPreference() == null
                    ? null
                    : body.getOutOfStockPreference().getValue())));
  }

  private static OrderPreferences response(OrderPreferenceService.View v) {
    return new OrderPreferences()
        .outOfStockPreference(
            v.outOfStockPreference() == null
                ? null
                : OrderPreferences.OutOfStockPreferenceEnum.fromValue(v.outOfStockPreference()))
        .version(v.version())
        .updatedAt(
            v.updatedAt() == null ? null : CustomerMapping.time(Instant.parse(v.updatedAt())));
  }
}
