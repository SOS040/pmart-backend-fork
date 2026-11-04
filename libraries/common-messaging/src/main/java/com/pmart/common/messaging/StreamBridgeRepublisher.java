package com.pmart.common.messaging;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;

/**
 * {@link DeadLetterRepublisher} 운영 구현(ADR-0035): Spring Cloud Stream {@link StreamBridge} 로 원래
 * 토픽(동적 목적지)에 같은 키·본문·헤더를 보낸다. Kafka 바인더 producer 는 ByteArray 직렬화이므로 키·본문은 바이트 그대로 간다.
 *
 * <p>성공 여부를 알기 위해 {@code spring.cloud.stream.kafka.default.producer.sync=true}({@link
 * StreamBindingDefaultsPostProcessor})로 보낸다 — 실패하면 예외가 난다. {@code false} 를 돌려줘도 실패로 본다.
 */
final class StreamBridgeRepublisher implements DeadLetterRepublisher {

  private final StreamBridge bridge;

  StreamBridgeRepublisher(StreamBridge bridge) {
    this.bridge = Objects.requireNonNull(bridge);
  }

  @Override
  public void send(
      String topic, @Nullable String key, @Nullable byte[] payload, Map<String, byte[]> headers) {
    MessageBuilder<byte[]> b = MessageBuilder.withPayload(payload == null ? new byte[0] : payload);
    headers.forEach(b::setHeader);
    if (key != null) {
      b.setHeader(KafkaHeaders.KEY, key.getBytes(StandardCharsets.UTF_8));
    }
    if (!bridge.send(topic, b.build())) {
      throw new IllegalStateException("StreamBridge 가 " + topic + " 로 보내지 못했습니다.");
    }
  }
}
