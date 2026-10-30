package com.pmart.common.messaging;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.databind.ObjectMapper;

/**
 * common-messaging auto-configuration.
 *
 * <ul>
 *   <li>{@link EventEnvelopeCodec}, {@link EventSchemaValidator}
 *   <li>{@link OutboxWriter}(JdbcTemplate 있을 때), {@link InboxGuard}(트랜잭션 매니저 있을 때)
 *   <li>Flyway 위치에 {@code classpath:db/migration/common} 추가(outbox/inbox 테이블)
 *   <li>Kafka 리스너 공통 오류 처리: 지수 백오프 재시도 후 {topic}.DLT (원 헤더·eventId 보존)
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
      "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration"
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

  /** Spring Kafka 리스너 컨테이너 기본 오류 처리. 서비스가 CommonErrorHandler 빈을 정의하면 그쪽이 우선. */
  @Bean
  @ConditionalOnMissingBean(CommonErrorHandler.class)
  @ConditionalOnBean(KafkaOperations.class)
  public CommonErrorHandler pmartKafkaErrorHandler(
      KafkaOperations<Object, Object> template, MessagingProperties props) {
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            template,
            (record, ex) ->
                new TopicPartition(Topics.deadLetter(record.topic()), record.partition()));
    ExponentialBackOff backOff =
        new ExponentialBackOff(props.getConsumer().getInitialBackoffMs(), 2.0);
    backOff.setMaxInterval(props.getConsumer().getMaxBackoffMs());
    backOff.setMaxAttempts(props.getConsumer().getMaxRetries());
    DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
    handler.setCommitRecovered(true);
    handler.addNotRetryableExceptions(
        EventSchemaValidator.EventSchemaViolation.class, IllegalArgumentException.class);
    return handler;
  }
}
