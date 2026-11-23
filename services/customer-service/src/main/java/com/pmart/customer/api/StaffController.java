package com.pmart.customer.api;

import com.pmart.customer.api.generated.StaffApi;
import com.pmart.customer.api.generated.model.AccountRestriction;
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
  private final com.pmart.customer.application.RestrictionService restrictions;

  public StaffController(
      StaffCustomerService staff, com.pmart.customer.application.RestrictionService restrictions) {
    this.staff = staff;
    this.restrictions = restrictions;
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
            .joinedAt(c.joinedAt().atOffset(ZoneOffset.UTC))
            .restriction(restriction(c))
            .restrictedUntil(until(c)));
  }

  /** 0.16.0 CU-7 — 제한 이력. */
  @Override
  public ResponseEntity<
          com.pmart.customer.api.generated.model.ListCustomerRestrictionsForStaff200Response>
      listCustomerRestrictionsForStaff(String customerId) {
    var h = restrictions.history(customerId);
    return ResponseEntity.ok(
        new com.pmart.customer.api.generated.model.ListCustomerRestrictionsForStaff200Response()
            .customerId(customerId)
            .restriction(restriction(h.customer()))
            .restrictedUntil(until(h.customer()))
            .items(
                h.items().stream()
                    .map(
                        r ->
                            new com.pmart.customer.api.generated.model.CustomerRestrictionRecord()
                                .recordId(r.recordId())
                                .restriction(AccountRestriction.fromValue(r.restriction()))
                                .previous(AccountRestriction.fromValue(r.previous()))
                                .restrictedUntil(
                                    r.until() == null ? null : r.until().atOffset(ZoneOffset.UTC))
                                .source(
                                    com.pmart.customer.api.generated.model.CustomerRestrictionRecord
                                        .SourceEnum.fromValue(r.source()))
                                .reason(r.reason())
                                .actor(r.actor())
                                .createdAt(r.createdAt().atOffset(ZoneOffset.UTC)))
                    .toList()));
  }

  @Override
  public ResponseEntity<com.pmart.customer.api.generated.model.ChangeMyProfile200Response>
      restrictCustomerForStaff(
          String customerId,
          String idempotencyKey,
          String xSourceSystem,
          com.pmart.customer.api.generated.model.RestrictCustomerForStaffRequest body) {
    var r =
        restrictions.restrict(
            customerId,
            body.getRestriction().getValue(),
            body.getReason(),
            idempotencyKey,
            xSourceSystem);
    return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
        .body(applied(idempotencyKey, r.recordId()));
  }

  @Override
  public ResponseEntity<com.pmart.customer.api.generated.model.ChangeMyProfile200Response>
      releaseCustomerRestrictionForStaff(
          String customerId,
          String idempotencyKey,
          String xSourceSystem,
          com.pmart.customer.api.generated.model.ReleaseCustomerRestrictionForStaffRequest body) {
    var r = restrictions.release(customerId, body.getReason(), idempotencyKey, xSourceSystem);
    return ResponseEntity.ok(applied(idempotencyKey, r.recordId()));
  }

  private static com.pmart.customer.api.generated.model.ChangeMyProfile200Response applied(
      String requestId, String resultId) {
    return new com.pmart.customer.api.generated.model.ChangeMyProfile200Response()
        .status(
            com.pmart.customer.api.generated.model.ChangeMyProfile200Response.StatusEnum.APPLIED)
        .requestId(requestId)
        .resultId(resultId);
  }

  static AccountRestriction restriction(ProfileRow c) {
    return AccountRestriction.fromValue(c.restrictionAt(java.time.Instant.now()));
  }

  static java.time.@Nullable OffsetDateTime until(ProfileRow c) {
    java.time.Instant u = c.restrictionUntilAt(java.time.Instant.now());
    return u == null ? null : u.atOffset(ZoneOffset.UTC);
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
        .status(AccountStatus.fromValue(c.status()))
        .restriction(restriction(c))
        .restrictedUntil(until(c));
  }
}
