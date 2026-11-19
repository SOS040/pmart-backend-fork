package com.pmart.customer.api;

import com.pmart.customer.api.generated.PhoneVerificationsApi;
import com.pmart.customer.api.generated.model.PhoneVerification;
import com.pmart.customer.api.generated.model.PhoneVerificationStart;
import com.pmart.customer.application.PhoneVerificationService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 PhoneVerificationsApi(0.9.0, 2026-11-19 — OCTOMO 휴대폰 번호 확인). 규칙은 {@link
 * PhoneVerificationService}.
 */
@RestController
public class PhoneVerificationsController implements PhoneVerificationsApi {

  private final PhoneVerificationService verifications;

  public PhoneVerificationsController(PhoneVerificationService verifications) {
    this.verifications = verifications;
  }

  @Override
  public ResponseEntity<PhoneVerificationStart> startPhoneVerification(
      String idempotencyKey, String xSourceSystem) {
    PhoneVerificationService.Started s =
        verifications.start(CustomerMapping.currentCustomer(), idempotencyKey, xSourceSystem);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new PhoneVerificationStart()
                .verificationId(s.verificationId())
                .state(PhoneVerificationStart.StateEnum.PENDING)
                .sendTo(s.sendTo())
                .text(s.text())
                .expiresAt(OffsetDateTime.parse(s.expiresAt()))
                .phoneMasked(s.phoneMasked())
                .maxAttempts(s.maxAttempts()));
  }

  @Override
  public ResponseEntity<PhoneVerification> getPhoneVerification(String verificationId) {
    return ResponseEntity.ok(
        body(verifications.get(CustomerMapping.currentCustomer(), verificationId)));
  }

  @Override
  public ResponseEntity<PhoneVerification> confirmPhoneVerification(
      String verificationId, String idempotencyKey, String xSourceSystem) {
    return ResponseEntity.ok(
        body(verifications.confirm(CustomerMapping.currentCustomer(), verificationId)));
  }

  private static PhoneVerification body(PhoneVerificationService.View v) {
    return new PhoneVerification()
        .verificationId(v.verificationId())
        .state(PhoneVerification.StateEnum.fromValue(v.state()))
        .failureReason(
            v.failureReason() == null
                ? null
                : PhoneVerification.FailureReasonEnum.fromValue(v.failureReason()))
        .remainingAttempts(v.remainingAttempts())
        .expiresAt(v.expiresAt().atOffset(ZoneOffset.UTC))
        .phoneMasked(v.phoneMasked())
        .verifiedAt(v.verifiedAt() == null ? null : v.verifiedAt().atOffset(ZoneOffset.UTC));
  }
}
