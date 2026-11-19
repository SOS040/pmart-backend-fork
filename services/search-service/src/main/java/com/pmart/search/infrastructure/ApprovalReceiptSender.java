package com.pmart.search.infrastructure;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventSchemaValidator;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.core.env.Environment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 승인 반영 회신 ApplicationReceiptRecorded(ADR-0039)를 Kafka 로 바로 보낸다(ADR-0073 단계 B). 이 서비스는 DB 가 없어
 * outbox 를 쓰지 못한다 — 대신 반영 결과를 규칙 색인에 먼저 남기고 보낸다. 보내기가 실패하면 예외 → 바인더가 ApprovalDecided 를 다시 주고, 반영
 * 기록을 보고 같은 회신을 다시 보낸다(approval-service 는 같은 승인·수신자·순번을 한 번만 반영). 키 = 승인 ID. 동기 전송(sync=true 기본값 —
 * common-messaging).
 */
@Component
public class ApprovalReceiptSender {

  public static final String TOPIC = "platform.approval-receipt.v1";

  private final ObjectProvider<StreamBridge> bridge;
  private final EventEnvelopeCodec codec;
  private final EventSchemaValidator validator;
  private final String sourceSystem;

  public ApprovalReceiptSender(
      ObjectProvider<StreamBridge> bridge,
      EventEnvelopeCodec codec,
      EventSchemaValidator validator,
      Environment env) {
    this.bridge = bridge;
    this.codec = codec;
    this.validator = validator;
    this.sourceSystem = env.getProperty("spring.application.name", "search-service");
  }

  public String sourceSystem() {
    return sourceSystem;
  }

  public void send(String approvalId, Map<String, Object> payload) {
    EventEnvelope envelope =
        codec.create(
            sourceSystem,
            "ApplicationReceiptRecorded",
            1,
            "ApplicationReceipt",
            approvalId,
            1,
            new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, sourceSystem),
            EventEnvelope.EventSource.REAL,
            payload);
    validator.validate(envelope);
    var message =
        MessageBuilder.withPayload(codec.encode(envelope).getBytes(StandardCharsets.UTF_8))
            .setHeader(KafkaHeaders.KEY, approvalId.getBytes(StandardCharsets.UTF_8))
            .build();
    if (!bridge.getObject().send(TOPIC, message)) {
      throw new IllegalStateException("승인 회신을 " + TOPIC + " 로 보내지 못했습니다: " + approvalId);
    }
  }
}
