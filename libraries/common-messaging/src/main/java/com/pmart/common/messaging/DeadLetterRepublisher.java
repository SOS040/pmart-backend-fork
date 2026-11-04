package com.pmart.common.messaging;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * 미처리 메시지를 원래 토픽에 다시 보낸다({@link DeadLetters#replay}). 운영 구현은 Spring Cloud Stream {@code
 * StreamBridge} (ADR-0035). 보내기가 끝날 때까지 기다리고, 실패하면 예외를 던진다(호출자가 OPEN 으로 되돌린다).
 */
@FunctionalInterface
public interface DeadLetterRepublisher {

  void send(
      String topic, @Nullable String key, @Nullable byte[] payload, Map<String, byte[]> headers)
      throws Exception;
}
