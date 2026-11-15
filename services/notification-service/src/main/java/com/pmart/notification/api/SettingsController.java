package com.pmart.notification.api;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.notification.api.generated.SettingsApi;
import com.pmart.notification.api.generated.model.Category;
import com.pmart.notification.api.generated.model.ChangeMyNotificationSettings200Response;
import com.pmart.notification.api.generated.model.ChangeMyNotificationSettingsRequest;
import com.pmart.notification.api.generated.model.Channel;
import com.pmart.notification.api.generated.model.NotificationSettings;
import com.pmart.notification.api.generated.model.NotificationSettingsItemsInner;
import com.pmart.notification.application.NotificationService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 SettingsApi(0.2.0) — 지금은 앱 알림함의 거래 안내뿐이라 모두 켜짐·잠김. 변경은 검사만 하고(끄기·다른 채널은 422) 바뀌는 것이 없으면
 * APPLIED. 변경 결과 조회는 아직 501.
 */
@RestController
public class SettingsController implements SettingsApi {

  private final NotificationService service;

  public SettingsController(NotificationService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<NotificationSettings> getMyNotificationSettings() {
    service.requireRecipient();
    var s =
        new NotificationSettings()
            .accountId(PmartPrincipal.current().map(PmartPrincipal::subject).orElse(null))
            .version(0)
            .marketingConsent(NotificationSettings.MarketingConsentEnum.UNKNOWN)
            .osPermission(NotificationSettings.OsPermissionEnum.UNKNOWN);
    for (String c : NotificationService.CATEGORIES) {
      s.addItemsItem(
          new NotificationSettingsItemsInner()
              .category(Category.fromValue(c))
              .channel(Channel.IN_APP)
              .enabled(true)
              .locked(true));
    }
    return ResponseEntity.ok(s);
  }

  @Override
  public ResponseEntity<ChangeMyNotificationSettings200Response> changeMyNotificationSettings(
      String idempotencyKey,
      String xSourceSystem,
      ChangeMyNotificationSettingsRequest body,
      @Nullable Integer xExpectedVersion) {
    service.checkChanges(
        body.getChanges().stream()
            .map(
                c ->
                    new String[] {
                      c.getCategory().getValue(),
                      c.getChannel().getValue(),
                      String.valueOf(c.getEnabled())
                    })
            .toList());
    return ResponseEntity.ok(
        new ChangeMyNotificationSettings200Response()
            .status(ChangeMyNotificationSettings200Response.StatusEnum.APPLIED)
            .requestId(idempotencyKey)
            .version(0)
            .occurredAt(OffsetDateTime.now(ZoneOffset.UTC)));
  }
}
