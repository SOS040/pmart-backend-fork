package com.pmart.notification.application;

import com.pmart.notification.domain.Notices;
import com.pmart.notification.infrastructure.FcmSender;
import com.pmart.notification.infrastructure.PushTokenStore;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * 고객 휴대폰 푸시(2026-11-17 사용자 "응 해줘"). 고객 알림함에 새 알림이 저장되면 <b>커밋 뒤</b>, 정한 알림 종류({@code
 * pmart.notification.fcm.customer-templates} — 배송 출발·완료, 결품, 주문 취소, 환불 결과)만 그 고객의 단말 토큰마다 FCM 으로
 * 보낸다. 나머지는 알림함만. 제목·본문은 알림함과 같고(개인정보 없음) data 에 알림 ID·목적지 — 라이더 푸시({@link RiderPush})와 같은 모양.
 *
 * <p>키는 고객 앱 Firebase 프로젝트의 서비스 계정 키 {@code pmart.notification.fcm.customer-service-account-json}.
 * 오늘(2026-11-17) 고객 앱은 라이더와 다른 프로젝트라 따로 두었다 — 한 프로젝트로 합치면 같은 키를 이 자리에도 넣으면 된다(코드 변경 없음). 키나 종류 목록이
 * 없으면 아무 것도 하지 않는다(기본값을 지어내지 않는다). 발송기는 라이더 쪽 {@link FcmSender}(PC 5)를 고치지 않고 고객 키로 하나 더 만든다. 실패는
 * 기록만 — 알림함이 정본이고 다시 보내지 않는다. 더는 쓸 수 없는 토큰(UNREGISTERED)은 지운다.
 */
@Component
public class CustomerPush {

  private static final Logger log = LoggerFactory.getLogger(CustomerPush.class);

  private final @Nullable FcmSender fcm;
  private final PushTokenStore tokens;
  private final Set<String> templates;

  public CustomerPush(
      NotificationProperties props,
      PushTokenStore tokens,
      RestClient.Builder builder,
      ObjectMapper mapper,
      Clock clock) {
    this.tokens = tokens;
    NotificationProperties.Fcm f = props.fcm();
    List<String> t = f == null ? null : f.customerTemplates();
    this.templates = t == null ? Set.of() : Set.copyOf(t);
    String key = f == null ? null : f.customerServiceAccountJson();
    if (key == null || key.isBlank()) {
      this.fcm = null;
      log.info("FCM 고객 키 없음 — 고객 푸시 꺼짐(알림함만)");
    } else {
      // 라이더 발송기와 같은 코드, 고객 키만 바꿔 넣는다(토큰 주소·FCM 주소는 공통 — 시험용)
      this.fcm =
          new FcmSender(
              new NotificationProperties(
                  null,
                  null,
                  new NotificationProperties.Fcm(key, null, null, f.tokenUri(), f.sendBaseUrl()),
                  null,
                  null),
              builder,
              mapper,
              clock);
    }
    if (fcm != null && templates.isEmpty()) {
      log.info("FCM 고객 키는 있지만 보낼 알림 종류(customer-templates)가 없음 — 고객 푸시 없음");
    }
  }

  /** 이 알림 종류를 고객에게 푸시로도 보내는가(고객 키가 읽혔고 정한 종류일 때만). */
  boolean pushes(String templateKey) {
    return fcm != null && fcm.enabled() && templates.contains(templateKey);
  }

  /** 지금 트랜잭션이 커밋되면 보낸다(트랜잭션 밖이면 바로). 보내지 않는 종류면 아무 것도 하지 않는다. */
  public void afterCommit(String accountId, String notificationId, Notices.Notice n) {
    if (!pushes(n.templateKey())) {
      return;
    }
    Runnable send = () -> send(accountId, notificationId, n);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              send.run();
            }
          });
    } else {
      send.run();
    }
  }

  void send(String accountId, String notificationId, Notices.Notice n) {
    FcmSender s = fcm;
    if (s == null) {
      return;
    }
    Map<String, String> data = new LinkedHashMap<>();
    data.put("notificationId", notificationId);
    data.put("templateKey", n.templateKey());
    data.put("destinationType", n.destinationType());
    if (n.destinationRef() != null) {
      data.put("destinationRef", n.destinationRef());
    }
    int sent = 0;
    for (PushTokenStore.TokenRow t : tokens.tokens(NotificationService.CUSTOMER, accountId)) {
      FcmSender.Result r = s.send(t.token(), n.title(), n.body(), data);
      if (r == FcmSender.Result.SENT) {
        sent++;
      } else if (r == FcmSender.Result.UNREGISTERED) {
        tokens.delete(NotificationService.CUSTOMER, accountId, t.deviceId());
        log.info("고객 {} 단말 {} 토큰이 더는 유효하지 않아 지움", accountId, t.deviceId());
      }
    }
    if (sent > 0) {
      log.info("고객 {} 푸시 {} — 단말 {}대", accountId, n.templateKey(), sent);
    }
  }
}
