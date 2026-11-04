package com.pmart.common.messaging;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.cloud.stream.binder.kafka.utils.DlqDestinationResolver;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * common-messaging auto-configuration.
 *
 * <ul>
 *   <li>{@link EventEnvelopeCodec}, {@link EventSchemaValidator}
 *   <li>{@link OutboxWriter}(JdbcTemplate 있을 때), {@link InboxGuard}(트랜잭션 매니저 있을 때)
 *   <li>Flyway 위치에 {@code classpath:db/migration/common} 추가(outbox/inbox 테이블)
 *   <li>업무 이벤트 소비(Spring Cloud Stream, ADR-0035): {@link EventConsumers}(→ {@link
 *       PmartEventConsumer} 빈), 바인딩 기본값은 {@link StreamBindingDefaultsPostProcessor}, 설정 누락 검사
 *       {@link ConsumerBindingVerifier}
 *   <li>재시도를 다 쓴 메시지: Kafka 바인더 DLQ → {topic}.DLT({@link PmartDlqDestinationResolver}) + 서비스 DB
 *       dead_letter 기록, 재처리는 StreamBridge({@link DeadLetters}, 내부 API {@link DeadLetterController}
 *       — ADR-0030)
 * </ul>
 */
// JdbcTemplate·트랜잭션 매니저가 먼저 만들어져야 @ConditionalOnBean 이 맞게
// 판정된다(OutboxWriter·InboxGuard·RequestIdempotency).
// 이름으로 지정: jdbc/jpa 모듈이 없는 서비스에서도 클래스 로딩 없이 동작한다.
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
      "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
      "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
      "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration",
      "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
      "org.springframework.cloud.stream.function.FunctionConfiguration"
    })
@EnableConfigurationProperties(MessagingProperties.class)
public class CommonMessagingAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public EventEnvelopeCodec eventEnvelopeCodec(ObjectProvider<ObjectMapper> mapper) {
    ObjectMapper m = mapper.getIfAvailable();
    return m == null ? EventEnvelopeCodec.withDefaultMapper() : new EventEnvelopeCodec(m);
  }

  @Bean
  @ConditionalOnMissingBean
  public EventSchemaValidator eventSchemaValidator(
      MessagingProperties props, EventEnvelopeCodec codec) {
    return new EventSchemaValidator(props.getOutbox().getSchemaValidation(), codec);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(JdbcTemplate.class)
  public OutboxWriter outboxWriter(
      JdbcTemplate jdbc,
      EventEnvelopeCodec codec,
      EventSchemaValidator validator,
      MessagingProperties props) {
    String domain = props.getDomain();
    if (domain.isBlank()) {
      throw new IllegalStateException(
          "pmart.messaging.domain 이 필요합니다 (토픽 {domain}.{aggregate}.v{n} 의 domain).");
    }
    return new OutboxWriter(
        jdbc, codec, validator, domain, props.getOutbox().isDeleteAfterInsert());
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean({JdbcTemplate.class, PlatformTransactionManager.class})
  public InboxGuard inboxGuard(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
    return new InboxGuard(jdbc, new TransactionTemplate(txManager));
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean({JdbcTemplate.class, PlatformTransactionManager.class})
  public RequestIdempotency requestIdempotency(
      JdbcTemplate jdbc, PlatformTransactionManager txManager, EventEnvelopeCodec codec) {
    return new RequestIdempotency(jdbc, new TransactionTemplate(txManager), codec.mapper());
  }

  /** 봉투 생성 시 sourceSystem 기본값 = spring.application.name. */
  @Bean
  @ConditionalOnMissingBean
  public EventPublisher eventPublisher(
      ObjectProvider<OutboxWriter> outbox,
      EventEnvelopeCodec codec,
      MessagingProperties props,
      Environment env) {
    String source =
        props.getSourceSystem().isBlank()
            ? env.getProperty("spring.application.name", "unknown")
            : props.getSourceSystem();
    return new EventPublisher(outbox, codec, source);
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(FlywayConfigurationCustomizer.class)
  static class FlywayCommonLocation {
    @Bean
    public FlywayConfigurationCustomizer pmartCommonMigrationLocation() {
      return configuration -> {
        var existing = configuration.getLocations();
        var merged = new org.flywaydb.core.api.Location[existing.length + 1];
        System.arraycopy(existing, 0, merged, 0, existing.length);
        merged[existing.length] =
            new org.flywaydb.core.api.Location("classpath:db/migration/common");
        configuration.locations(merged);
        // 공통 마이그레이션은 V0_x 라 서비스 V1 이후보다 항상 낮다. 이미 V1 이 적용된 DB 에 새 공통 마이그레이션(V0_0_2 등)이
        // 생기면 Flyway 가 "ignored" 로 보고 기동을 거부한다(2026-10-30 LAN). 공통 마이그레이션은 서로 독립된 새 표만 만드므로
        // 순서가 어긋난 적용을 허용한다.
        configuration.outOfOrder(true);
      };
    }
  }

  /**
   * 미처리 메시지 기록·재처리(ADR-0030). DB(JdbcTemplate)와 Spring Cloud Stream 이 있는 서비스만. 재처리는 StreamBridge.
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(JdbcTemplate.class)
  @ConditionalOnClass(StreamBridge.class)
  public DeadLetters pmartDeadLetters(
      JdbcTemplate jdbc,
      EventEnvelopeCodec codec,
      ObjectProvider<StreamBridge> bridge,
      ObjectProvider<java.time.Clock> clock,
      ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters) {
    // StreamBridge 는 재처리할 때 꺼낸다(바인더 구성 순서와 무관하게)
    DeadLetterRepublisher republisher =
        (topic, key, payload, headers) ->
            new StreamBridgeRepublisher(bridge.getObject()).send(topic, key, payload, headers);
    return new DeadLetters(
        jdbc,
        codec.mapper(),
        republisher,
        clock.getIfAvailable(java.time.Clock::systemUTC),
        meters.getIfAvailable(),
        true);
  }

  /** 내부 API(서블릿 서비스만). 같은 클래스의 앞선 빈(DeadLetters)으로 조건을 판정한다. */
  @Bean
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnBean(DeadLetters.class)
  public DeadLetterController pmartDeadLetterController(DeadLetters deadLetters) {
    return new DeadLetterController(deadLetters);
  }

  /** 업무 이벤트 소비 함수 공장(ADR-0035). */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(InboxGuard.class)
  public EventConsumers pmartEventConsumers(EventEnvelopeCodec codec, InboxGuard inbox) {
    return new EventConsumers(codec, inbox);
  }

  /** Kafka 바인더 DLQ 목적지 = {topic}.DLT + dead_letter 기록(ADR-0030·0035). DLQ 가 켜진 업무 바인딩에만 쓰인다. */
  @Bean
  @ConditionalOnMissingBean(DlqDestinationResolver.class)
  @ConditionalOnClass(DlqDestinationResolver.class)
  public DlqDestinationResolver pmartDlqDestinationResolver(
      ObjectProvider<DeadLetters> deadLetters) {
    return new PmartDlqDestinationResolver(deadLetters);
  }

  /** {@link PmartEventConsumer} 빈이 설정 저장소 바인딩에 빠져 있으면 기동을 멈춘다(조용히 소비하지 않는 일을 막는다). */
  @Bean
  public ConsumerBindingVerifier pmartConsumerBindingVerifier() {
    return new ConsumerBindingVerifier();
  }
}
