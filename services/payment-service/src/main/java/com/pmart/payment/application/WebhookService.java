package com.pmart.payment.application;

import com.pmart.common.web.PmartException;
import com.pmart.payment.infrastructure.PaymentStore;
import com.pmart.payment.infrastructure.toss.TossProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 토스 웹훅(계약 /webhooks/toss). 서명 검증 → 본문 해시로 중복 제거 → 해당 결제 시도의 원거래 조회를 앞당긴다. 웹훅 내용만으로 승인·취소를 확정하지
 * 않는다(ADR-0005) — 대사는 {@link ReconciliationService} 가 paymentKey 조회로 한다.
 *
 * <p>서명: {@code Toss-Signature} = Base64(HMAC-SHA256(웹훅 키, 원문 본문)). 웹훅 키가 없으면 모든 웹훅을 거부한다(401). 실제
 * 토스 웹훅 서명 방식과 맞추는 일은 웹훅을 켤 때 확인한다.
 */
@Service
public class WebhookService {

  private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

  private final PaymentStore store;
  private final TossProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;

  public WebhookService(
      PaymentStore store, TossProperties props, TransactionTemplate tx, Clock clock) {
    this.store = store;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
  }

  public void receive(
      byte[] rawBody, String signature, String eventType, @Nullable String orderId) {
    if (props.webhookSecret() == null || !valid(rawBody, signature, props.webhookSecret())) {
      throw new PmartException(
          "WEBHOOK_SIGNATURE_INVALID",
          HttpStatus.UNAUTHORIZED,
          "웹훅 서명이 맞지 않습니다.",
          false,
          List.of());
    }
    Instant now = clock.instant();
    String hash = sha256(rawBody);
    tx.executeWithoutResult(
        s -> {
          String attemptId =
              orderId != null && store.find(orderId, false).isPresent() ? orderId : null;
          if (!store.recordWebhook(hash, eventType, attemptId, now)) {
            return; // 같은 웹훅 재수신
          }
          if (attemptId != null) {
            store.log(attemptId, "WEBHOOK", null, eventType, null, null, "toss", null, now);
            store.expedite(attemptId, now);
          }
        });
    log.info("toss 웹훅 {} 접수 orderId={}", eventType, orderId);
  }

  static boolean valid(byte[] body, String signature, String secret) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] expected = mac.doFinal(body);
      byte[] given = Base64.getDecoder().decode(signature.strip());
      return MessageDigest.isEqual(expected, given);
    } catch (IllegalArgumentException e) {
      return false;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static String sha256(byte[] body) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
