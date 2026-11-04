package com.pmart.common.messaging;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 업무 이벤트 소비 바인딩 기본값(ADR-0035)을 <b>가장 낮은 우선순위</b>로 넣는다. 설정 저장소가 {@code
 * spring.cloud.function.definition}(빈 이름, {@code ;} 구분)과 {@code
 * spring.cloud.stream.bindings.<fn>-in-0.destination}(토픽)만 정하면, 함수마다 아래를 채운다 — 설정에 같은 키가 있으면 그쪽이
 * 이긴다.
 *
 * <ul>
 *   <li>{@code group = spring.application.name}: 서비스 이름 그룹(인스턴스끼리 나눠 받고, 커밋된 위치를 이어받는다). 빠지면 익명 그룹
 *       + 바인더 수준 {@code latest} 라 이벤트를 잃는다.
 *   <li>재시도: {@code max-attempts = 1 + pmart.messaging.consumer.max-retries}(기본 3), 지수 백오프 {@code
 *       initial-backoff-ms}(1000)·배수 2·{@code max-backoff-ms}(10000). 잘못된
 *       인자(IllegalArgumentException)는 재시도하지 않는다.
 *   <li>Kafka: {@code enable-dlq}(목적지는 {@link PmartDlqDestinationResolver} — {@code {topic}.DLT}),
 *       {@code ack-mode RECORD}(한 건씩, 처리 뒤 커밋), {@code auto.offset.reset=earliest}, {@code
 *       isolation.level=read_committed}. 바인더 수준 소비 설정은 Bus 용(ByteArray·latest,
 *       common-observability)이라 바인딩마다 덮는다.
 *   <li>{@code spring.cloud.stream.kafka.default.producer.sync=true}: 재처리({@link
 *       StreamBridgeRepublisher})가 전송 결과를 기다린다.
 * </ul>
 *
 * <p>Bus 함수({@code busConsumer})는 건드리지 않는다 — Bus 는 인스턴스별 익명 그룹·latest 여야 한다. 그래서 {@code
 * spring.cloud.stream.default.*}·{@code spring.cloud.stream.kafka.default.consumer.*} 는 쓰지 않는다. Bus
 * 의 {@code BusEnvironmentPostProcessor}(순서 없음 = 가장 늦음)가 definition 뒤에 busConsumer 를 붙이므로 그보다 먼저 돈다.
 * 설정 저장소 값(configserver: import)은 이보다 먼저 환경에 들어와 있다.
 */
public class StreamBindingDefaultsPostProcessor implements EnvironmentPostProcessor, Ordered {

  public static final String SOURCE_NAME = "pmart-stream-binding-defaults";
  static final String DEFINITION = "spring.cloud.function.definition";
  static final String BUS_FUNCTION = "busConsumer";
  private static final String BYTES_SERIALIZER =
      "org.apache.kafka.common.serialization.ByteArraySerializer";
  private static final String BYTES_DESERIALIZER =
      "org.apache.kafka.common.serialization.ByteArrayDeserializer";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication app) {
    if (environment.getPropertySources().contains(SOURCE_NAME)) {
      return; // 설정 갱신(ContextRefresher)이 환경을 다시 만들 때도 한 번만
    }
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("spring.cloud.stream.kafka.default.producer.sync", "true");
    // 바인더는 spring.kafka.producer/consumer.*(시험 KafkaTemplate 용 String 직렬화,
    // MessagingDefaultsPostProcessor)를 자기
    // 클라이언트에 합친다. 바인더 수준 값이 그 위에 덮이므로 ByteArray 를 고정한다 — DLQ·재처리(StreamBridge)는 byte[] 를 보낸다.
    // common-observability(ConfigBusDefaultsPostProcessor)도 같은 값을 넣는다(Bus 용). 같은 값이라 어느 쪽이 먼저여도 된다.
    String binder = "spring.cloud.stream.kafka.binder.";
    d.put(binder + "producer-properties[key.serializer]", BYTES_SERIALIZER);
    d.put(binder + "producer-properties[value.serializer]", BYTES_SERIALIZER);
    int retries = environment.getProperty("pmart.messaging.consumer.max-retries", Integer.class, 3);
    long initial =
        environment.getProperty("pmart.messaging.consumer.initial-backoff-ms", Long.class, 1000L);
    long max =
        environment.getProperty("pmart.messaging.consumer.max-backoff-ms", Long.class, 10000L);
    for (String fn : functions(environment.getProperty(DEFINITION))) {
      String b = "spring.cloud.stream.bindings." + fn + "-in-0.";
      d.put(b + "group", "${spring.application.name}");
      d.put(b + "consumer.max-attempts", String.valueOf(Math.max(0, retries) + 1));
      d.put(b + "consumer.back-off-initial-interval", String.valueOf(initial));
      d.put(b + "consumer.back-off-multiplier", "2.0");
      d.put(b + "consumer.back-off-max-interval", String.valueOf(max));
      // 잘못된 인자는 다시 해도 같다 — 바로 DLQ. (계약 위반 EventSchemaViolation 은 발행 때만 나고, 중첩 클래스 이름의 '$' 는
      // 설정 키로 쓸 수 없어 넣지 않는다)
      d.put(
          b + "consumer.retryable-exceptions." + IllegalArgumentException.class.getName(), "false");
      String k = "spring.cloud.stream.kafka.bindings." + fn + "-in-0.consumer.";
      d.put(k + "enable-dlq", "true");
      d.put(k + "ack-mode", "RECORD");
      d.put(k + "configuration[auto.offset.reset]", "earliest");
      d.put(k + "configuration[isolation.level]", "read_committed");
      d.put(k + "configuration[key.deserializer]", BYTES_DESERIALIZER);
      d.put(k + "configuration[value.deserializer]", BYTES_DESERIALIZER);
    }
    environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, d));
  }

  /** definition 의 함수 이름들(합성 {@code a|b} 는 바인딩 이름 그대로), busConsumer 제외. */
  static List<String> functions(String definition) {
    List<String> out = new ArrayList<>();
    if (definition == null || definition.isBlank()) {
      return out;
    }
    for (String f : definition.split(";")) {
      String name = f.trim();
      if (!name.isEmpty() && !BUS_FUNCTION.equals(name)) {
        out.add(name);
      }
    }
    return out;
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE - 10;
  }
}
