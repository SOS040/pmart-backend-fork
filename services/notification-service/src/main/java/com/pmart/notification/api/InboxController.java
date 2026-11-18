package com.pmart.notification.api;

import com.pmart.notification.api.generated.InboxApi;
import com.pmart.notification.api.generated.model.Category;
import com.pmart.notification.api.generated.model.Destination;
import com.pmart.notification.api.generated.model.ListMyNotifications200Response;
import com.pmart.notification.api.generated.model.ListMyNotifications200ResponsePage;
import com.pmart.notification.api.generated.model.Notification;
import com.pmart.notification.application.NotificationService;
import com.pmart.notification.infrastructure.NotificationStore.NotificationRow;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 InboxApi — 본인 알림함·읽음(0.2.0). */
@RestController
public class InboxController implements InboxApi {

  private final NotificationService service;

  public InboxController(NotificationService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<ListMyNotifications200Response> listMyNotifications(
      @Nullable Boolean unreadOnly,
      @Nullable Category category,
      @Nullable Integer size,
      @Nullable String cursor) {
    int n = size == null ? 50 : size;
    var p =
        service.page(
            Boolean.TRUE.equals(unreadOnly),
            category == null ? null : category.getValue(),
            cursor,
            n);
    return ResponseEntity.ok(
        new ListMyNotifications200Response()
            .items(p.items().stream().map(InboxController::view).toList())
            .unreadCount(p.unreadCount())
            .page(new ListMyNotifications200ResponsePage().nextCursor(p.nextCursor()).size(n)));
  }

  @Override
  public ResponseEntity<Notification> getMyNotification(String notificationId) {
    return ResponseEntity.ok(view(service.get(notificationId)));
  }

  @Override
  public ResponseEntity<Notification> markNotificationRead(
      String notificationId, String idempotencyKey, String xSourceSystem) {
    return ResponseEntity.ok(view(service.markRead(notificationId)));
  }

  static Notification view(NotificationRow r) {
    var n =
        new Notification()
            .notificationId(r.notificationId())
            .category(Category.fromValue(r.category()))
            .title(r.title())
            .body(r.body())
            .sourceEventId(r.sourceEventId())
            .sourceEventType(r.sourceEventType())
            .templateKey(r.templateKey())
            .state(Notification.StateEnum.fromValue(r.state()))
            .createdAt(time(r.createdAt()))
            .readAt(time(r.readAt()));
    if (r.destinationType() != null) {
      n.destination(
          new Destination()
              .type(Destination.TypeEnum.fromValue(r.destinationType()))
              .ref(r.destinationRef()));
    }
    return n;
  }

  private static @Nullable OffsetDateTime time(@Nullable Instant i) {
    return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
