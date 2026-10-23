package com.pmart.common.messaging;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Inbox 멱등 처리 (ADR-0009). {@code (sourceSystem, eventId)} 를 한 트랜잭션에서 기록하고 핸들러를 실행한다.
 *
 * <ul>
 *   <li>이미 기록된 이벤트 → 핸들러를 호출하지 않고 {@code false}.
 *   <li>핸들러가 예외를 던지면 inbox 기록도 롤백 → 재전달 시 다시 처리된다.
 *   <li>Kafka offset 커밋은 리스너가 정상 반환한 뒤 Spring Kafka 가 한다(업무 반영 후 커밋).
 * </ul>
 */
public class InboxGuard {

  private static final Logger log = LoggerFactory.getLogger(InboxGuard.class);
  private static final String INSERT =
      "INSERT INTO inbox_event (source_system, event_id, event_type, aggregate_id, received_at) "
          + "VALUES (?, ?, ?, ?, ?)";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  public InboxGuard(JdbcTemplate jdbc, TransactionTemplate tx) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.tx = Objects.requireNonNull(tx);
  }

  /**
   * @return true 면 처리함, false 면 중복이라 건너뜀
   */
  public boolean process(EventEnvelope envelope, Runnable handler) {
    Boolean handled =
        tx.execute(
            status -> {
              try {
                jdbc.update(
                    INSERT,
                    envelope.sourceSystem(),
                    envelope.eventId(),
                    envelope.eventType(),
                    envelope.aggregateId(),
                    Timestamp.from(Instant.now()));
              } catch (DuplicateKeyException dup) {
                log.info(
                    "inbox duplicate skipped {} {} from {}",
                    envelope.eventType(),
                    envelope.eventId(),
                    envelope.sourceSystem());
                status.setRollbackOnly();
                return false;
              }
              handler.run();
              return true;
            });
    return Boolean.TRUE.equals(handled);
  }
}
