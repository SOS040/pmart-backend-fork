package com.pmart.notification.api;

import com.pmart.notification.api.generated.PushTokensApi;
import com.pmart.notification.api.generated.model.ListMyPushTokens200Response;
import com.pmart.notification.api.generated.model.PushDevice;
import com.pmart.notification.api.generated.model.PushPlatform;
import com.pmart.notification.api.generated.model.PushTokenRegistration;
import com.pmart.notification.application.PushTokenService;
import com.pmart.notification.infrastructure.PushTokenStore.PushDeviceRow;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 PushTokensApi(0.3.0) — 단말 푸시 토큰 등록·해제·목록. 저장만 하고 발송은 아직 없다. 응답에 토큰 값을 넣지 않는다. 요청 본문(생성 DTO 의
 * toString 에 토큰이 들어 있다)은 로그로 남기지 않는다.
 */
@RestController
public class PushTokensController implements PushTokensApi {

  private final PushTokenService service;

  public PushTokensController(PushTokenService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<ListMyPushTokens200Response> listMyPushTokens() {
    return ResponseEntity.ok(
        new ListMyPushTokens200Response()
            .items(service.list().stream().map(PushTokensController::view).toList()));
  }

  @Override
  public ResponseEntity<PushDevice> registerMyPushToken(
      String deviceId, String idempotencyKey, String xSourceSystem, PushTokenRegistration body) {
    return ResponseEntity.ok(
        view(
            service.register(
                deviceId, body.getToken(), body.getPlatform().getValue(), body.getAppVersion())));
  }

  @Override
  public ResponseEntity<Void> unregisterMyPushToken(
      String deviceId, String idempotencyKey, String xSourceSystem) {
    service.unregister(deviceId);
    return ResponseEntity.noContent().build();
  }

  static PushDevice view(PushDeviceRow r) {
    return new PushDevice()
        .deviceId(r.deviceId())
        .platform(PushPlatform.fromValue(r.platform()))
        .appVersion(r.appVersion())
        .registeredAt(OffsetDateTime.ofInstant(r.registeredAt(), ZoneOffset.UTC))
        .updatedAt(OffsetDateTime.ofInstant(r.updatedAt(), ZoneOffset.UTC));
  }
}
