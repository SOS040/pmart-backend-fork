package com.pmart.customer.api;

import com.pmart.customer.api.generated.StaffApi;
import com.pmart.customer.api.generated.model.AccountStatus;
import com.pmart.customer.api.generated.model.CustomerPiiField;
import com.pmart.customer.api.generated.model.CustomerPiiReveal;
import com.pmart.customer.api.generated.model.CustomerPiiRevealRequest;
import com.pmart.customer.api.generated.model.StaffCustomerDetail;
import com.pmart.customer.api.generated.model.StaffCustomerList;
import com.pmart.customer.api.generated.model.StaffCustomerSummary;
import com.pmart.customer.application.StaffCustomerService;
import com.pmart.customer.domain.ProfileRules;
import com.pmart.customer.infrastructure.CustomerStore.ProfileRow;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 StaffApi — 직원용 고객 조회(0.11.0, CU-3·CU-4). 규칙은 {@link StaffCustomerService}, 여기는 변환만. */
@RestController
public class StaffController implements StaffApi {

  private final StaffCustomerService staff;

  public StaffController(StaffCustomerService staff) {
    this.staff = staff;
  }

  @Override
  public ResponseEntity<StaffCustomerList> searchCustomersForStaff(
      @Nullable String customerNumber, @Nullable String phone, @Nullable String customerIds) {
    return ResponseEntity.ok(
        new StaffCustomerList()
            .items(
                staff.search(customerNumber, phone, customerIds).stream()
                    .map(StaffController::summary)
                    .toList()));
  }

  @Override
  public ResponseEntity<StaffCustomerDetail> getCustomerForStaff(String customerId) {
    ProfileRow c = staff.detail(customerId);
    return ResponseEntity.ok(
        new StaffCustomerDetail()
            .customerId(c.accountId())
            .customerNumber(c.customerNumber())
            .nameMasked(ProfileRules.maskName(c.name(), c.nickname()))
            .phoneMasked(ProfileRules.maskPhone(c.phone()))
            .emailMasked(ProfileRules.maskEmail(c.email()))
            .status(AccountStatus.fromValue(c.status()))
            .socialProvider(c.socialProvider())
            .phoneVerified(c.phone() != null && c.phoneVerified())
            .joinedAt(c.joinedAt().atOffset(ZoneOffset.UTC)));
  }

  @Override
  public ResponseEntity<CustomerPiiReveal> revealCustomerPiiForStaff(
      String customerId,
      String idempotencyKey,
      String xSourceSystem,
      CustomerPiiRevealRequest body) {
    Set<StaffCustomerService.PiiField> fields = null;
    if (body.getFields() != null && !body.getFields().isEmpty()) {
      fields = EnumSet.noneOf(StaffCustomerService.PiiField.class);
      for (CustomerPiiField f : body.getFields()) {
        fields.add(StaffCustomerService.PiiField.valueOf(f.getValue()));
      }
    }
    StaffCustomerService.Reveal r =
        staff.reveal(customerId, idempotencyKey, xSourceSystem, body.getReason(), fields);
    return ResponseEntity.ok(
        new CustomerPiiReveal()
            .customerId(r.customerId())
            .piiAccessLogId(r.piiAccessLogId())
            .recordedAt(r.recordedAt().atOffset(ZoneOffset.UTC))
            .fields(r.fields().stream().map(f -> CustomerPiiField.fromValue(f.name())).toList())
            .name(r.name())
            .phone(r.phone()));
  }

  static StaffCustomerSummary summary(ProfileRow c) {
    return new StaffCustomerSummary()
        .customerId(c.accountId())
        .customerNumber(c.customerNumber())
        .nameMasked(ProfileRules.maskName(c.name(), c.nickname()))
        .phoneMasked(ProfileRules.maskPhone(c.phone()))
        .status(AccountStatus.fromValue(c.status()));
  }
}
