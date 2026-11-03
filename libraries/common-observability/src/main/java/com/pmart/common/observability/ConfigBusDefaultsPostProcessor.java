package com.pmart.common.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 설정 변경 알림(Spring Cloud Bus, ADR-0033) 기본값을 <b>가장 낮은 우선순위</b>로 넣는다. 모든 앱에 bus-kafka 가 들어
 * 있지만(convention) 기본은 꺼짐이고, 설정 저장소의 {@code application-lan.yml} 이 켠다(Eureka 와 같은 방식).
 *
 * <ul>
 *   <li>{@code spring.cloud.bus.enabled=false}: 설정 저장소를 읽지 않는 단위 시험·로컬에서 Kafka(springCloudBus)에 붙지
 *       않게.
 *   <li>{@code spring.cloud.stream.function.autodetect=false}: Spring Cloud Stream 이 앱의 {@code
 *       Supplier}·{@code Consumer} 빈을 Kafka 바인딩으로 잡지 않게. Bus 는 자기 함수(busConsumer)를 이름으로 지정한다.
 *   <li>{@code management.health.binders.enabled=false}: Bus 는 부가 기능이다. Bus 용 Kafka 연결 문제로 앱 health
 *       가 DOWN 이 되면 Eureka 에서 빠진다(healthcheck).
 *   <li>Bus 용 Kafka 클라이언트(바인더 수준 {@code producer-properties}·{@code consumer-properties}): 바인더는
 *       {@code spring.kafka.producer/consumer.*} 를 자기 클라이언트에 합친다. 앱 공통값(common-messaging: String
 *       직렬화·earliest)이 들어가면 byte[] 메시지를 String 직렬화기로 보내다 실패하고, 앱이 뜰 때마다 지난 갱신 알림을 처음부터 다시 받는다. 바인더
 *       수준 값이 그 위에 덮이므로 ByteArray 직렬화와 {@code latest}(뜬 뒤의 알림만)를 여기서 고정한다.
 * </ul>
 *
 * <p>Bus 의 {@code BusEnvironmentPostProcessor}(순서 없음 = 가장 늦음)가 {@code spring.cloud.bus.enabled} 를
 * 보고 함수 바인딩을 넣으므로 그보다 먼저 돈다. 설정 저장소 값(configserver: import)은 이보다 먼저 환경에 들어와 있다.
 */
public class ConfigBusDefaultsPostProcessor implements EnvironmentPostProcessor, Ordered {

  public static final String SOURCE_NAME = "pmart-config-bus-defaults";

  private static final String BYTES_SERIALIZER =
      "org.apache.kafka.common.serialization.ByteArraySerializer";
  private static final String BYTES_DESERIALIZER =
      "org.apache.kafka.common.serialization.ByteArrayDeserializer";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication app) {
    if (environment.getPropertySources().contains(SOURCE_NAME)) {
      return; // 설정 갱신(ContextRefresher)이 환경을 다시 만들 때도 한 번만
    }
    Map<String, Object> defaults = new LinkedHashMap<>();
    defaults.put("spring.cloud.bus.enabled", "false");
    defaults.put("spring.cloud.stream.function.autodetect", "false");
    defaults.put("management.health.binders.enabled", "false");
    String binder = "spring.cloud.stream.kafka.binder.";
    defaults.put(binder + "producer-properties[key.serializer]", BYTES_SERIALIZER);
    defaults.put(binder + "producer-properties[value.serializer]", BYTES_SERIALIZER);
    defaults.put(binder + "consumer-properties[key.deserializer]", BYTES_DESERIALIZER);
    defaults.put(binder + "consumer-properties[value.deserializer]", BYTES_DESERIALIZER);
    defaults.put(binder + "consumer-properties[auto.offset.reset]", "latest");
    environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, defaults));
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE - 10;
  }
}
