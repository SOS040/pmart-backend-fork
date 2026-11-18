package com.pmart.customer.api;

import com.pmart.customer.api.generated.DeviceConsentsApi;
import com.pmart.customer.api.generated.model.ChangeMyConsentsRequest;
import com.pmart.customer.api.generated.model.ConsentTermList;
import com.pmart.customer.api.generated.model.ConsentTermListItemsInner;
import com.pmart.customer.api.generated.model.ConsentType;
import com.pmart.customer.api.generated.model.DeviceConsentList;
import com.pmart.customer.application.ConsentService;
import com.pmart.customer.application.DeviceConsentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 DeviceConsentsApi — 로그인 전(기기) 동의·약관 항목(2026-11-19 BR-59, 토큰 없음 — X-Device-Id). 업무 규칙은 {@link
 * DeviceConsentService}·{@link ConsentService}.
 */
@RestController
public class DeviceConsentsController implements DeviceConsentsApi {

  private final DeviceConsentService devices;
  private final ConsentService consents;

  public DeviceConsentsController(DeviceConsentService devices, ConsentService consents) {
    this.devices = devices;
    this.consents = consents;
  }

  @Override
  public ResponseEntity<DeviceConsentList> listDeviceConsents(String xDeviceId) {
    return ResponseEntity.ok(view(devices.overview(xDeviceId)));
  }

  @Override
  public ResponseEntity<DeviceConsentList> changeDeviceConsents(
      String idempotencyKey, String xSourceSystem, String xDeviceId, ChangeMyConsentsRequest body) {
    return ResponseEntity.ok(
        view(
            devices.change(
                xDeviceId,
                idempotencyKey,
                xSourceSystem,
                CustomerMapping.changes(body.getChanges()))));
  }

  @Override
  public ResponseEntity<ConsentTermList> listConsentTerms() {
    return ResponseEntity.ok(
        new ConsentTermList()
            .senderName(consents.senderName())
            .items(
                consents.terms().stream()
                    .map(
                        t ->
                            new ConsentTermListItemsInner()
                                .consentType(ConsentType.fromValue(t.item().name()))
                                .required(t.item().required())
                                .requiredFor(
                                    t.item().requiredFor() == null
                                        ? null
                                        : ConsentTermListItemsInner.RequiredForEnum.fromValue(
                                            t.item().requiredFor()))
                                .latestVersion(t.latestVersion())
                                .documentUrl(t.documentUrl()))
                    .toList()));
  }

  private static DeviceConsentList view(DeviceConsentService.DeviceView v) {
    return new DeviceConsentList()
        .deviceId(v.deviceId())
        .senderName(v.senderName())
        .items(v.items().stream().map(CustomerMapping::consent).toList());
  }
}
