package com.pmart.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.common.messaging.RequestIdempotency.Outcome;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** 실제 PostgreSQL 에서 요청 멱등 기록(V0_0_2)을 검증한다. 재생·충돌·롤백·동시 요청. Docker 필요. */
@Testcontainers
class RequestIdempotencyPostgresIT {

  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  static {
    postgres.withCommand("postgres", "-c", "wal_level=logical");
  }

  record Body(String sku, int qty) {}

  record Result(String id, int version) {}

  static JdbcTemplate jdbc;
  static TransactionTemplate tx;
  static RequestIdempotency idempotency;

  @BeforeAll
  static void migrate() {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setUrl(postgres.getJdbcUrl());
    ds.setUser(postgres.getUsername());
    ds.setPassword(postgres.getPassword());
    Flyway.configure().dataSource(ds).locations("classpath:db/migration/common").load().migrate();
    jdbc = new JdbcTemplate(ds);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    idempotency = new RequestIdempotency(jdbc, tx, JsonMapper.builder().build());
  }

  private static RequestKey key(String requestId) {
    return new RequestKey("customer-app", requestId, "createCart", "cust-1");
  }

  @Test
  void sameKeySameBodyReplaysStoredResultWithoutRunningAgain() {
    String id = UUID.randomUUID().toString();
    AtomicInteger runs = new AtomicInteger();

    Outcome<Result> first =
        idempotency.execute(
            key(id),
            new Body("E-0000001", 2),
            Result.class,
            () -> new Result("r-" + runs.incrementAndGet(), 1));
    Outcome<Result> second =
        idempotency.execute(
            key(id),
            new Body("E-0000001", 2),
            Result.class,
            () -> new Result("r-" + runs.incrementAndGet(), 1));

    assertThat(first.replayed()).isFalse();
    assertThat(second.replayed()).isTrue();
    assertThat(second.value()).isEqualTo(first.value());
    assertThat(runs).hasValue(1);
  }

  @Test
  void keyOrderOfMapBodiesDoesNotMatter() {
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("sku", "E-0000001");
    a.put("qty", 2);
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("qty", 2);
    b.put("sku", "E-0000001");
    assertThat(idempotency.hash(a)).isEqualTo(idempotency.hash(b));
    assertThat(idempotency.hash(a))
        .isNotEqualTo(idempotency.hash(Map.of("sku", "E-0000001", "qty", 3)));
    assertThat(idempotency.hash(List.of(1, 2))).isNotEqualTo(idempotency.hash(List.of(2, 1)));
  }

  @Test
  void differentBodyOperationOrActorIsConflict() {
    String id = UUID.randomUUID().toString();
    idempotency.execute(key(id), new Body("E-0000001", 2), Result.class, () -> new Result("r", 1));

    assertConflict(
        () ->
            idempotency.execute(
                key(id), new Body("E-0000001", 3), Result.class, () -> new Result("x", 1)));
    assertConflict(
        () ->
            idempotency.execute(
                new RequestKey("customer-app", id, "deleteCart", "cust-1"),
                new Body("E-0000001", 2),
                Result.class,
                () -> new Result("x", 1)));
    assertConflict(
        () ->
            idempotency.execute(
                new RequestKey("customer-app", id, "createCart", "cust-2"),
                new Body("E-0000001", 2),
                Result.class,
                () -> new Result("x", 1)));
  }

  @Test
  void sameRequestIdFromAnotherSourceSystemIsIndependent() {
    String id = UUID.randomUUID().toString();
    idempotency.execute(key(id), null, Result.class, () -> new Result("a", 1));
    Outcome<Result> other =
        idempotency.execute(
            new RequestKey("backoffice-web", id, "createCart", "cust-1"),
            null,
            Result.class,
            () -> new Result("b", 1));
    assertThat(other.replayed()).isFalse();
    assertThat(other.value().id()).isEqualTo("b");
  }

  @Test
  void failureRollsBackRecordAndBusinessChangeSoTheKeyCanBeRetried() {
    jdbc.execute("CREATE TABLE IF NOT EXISTS it_business (id varchar(64) PRIMARY KEY)");
    String id = UUID.randomUUID().toString();

    assertThatThrownBy(
            () ->
                idempotency.execute(
                    key(id),
                    null,
                    Result.class,
                    () -> {
                      jdbc.update("INSERT INTO it_business (id) VALUES (?)", id);
                      throw new IllegalStateException("업무 실패");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count("SELECT count(*) FROM request_record WHERE request_id = ?", id)).isZero();
    assertThat(count("SELECT count(*) FROM it_business WHERE id = ?", id)).isZero();

    Outcome<Result> retry =
        idempotency.execute(
            key(id),
            null,
            Result.class,
            () -> {
              jdbc.update("INSERT INTO it_business (id) VALUES (?)", id);
              return new Result("ok", 1);
            });
    assertThat(retry.replayed()).isFalse();
    assertThat(count("SELECT count(*) FROM it_business WHERE id = ?", id)).isEqualTo(1);
  }

  @Test
  void concurrentDuplicatesRunTheActionOnceAndBothSeeTheSameResult() throws Exception {
    String id = UUID.randomUUID().toString();
    AtomicInteger runs = new AtomicInteger();
    CountDownLatch insideFirst = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Outcome<Result>> first =
          pool.submit(
              () ->
                  idempotency.execute(
                      key(id),
                      null,
                      Result.class,
                      () -> {
                        runs.incrementAndGet();
                        insideFirst.countDown();
                        await(release);
                        return new Result("first", 1);
                      }));
      assertThat(insideFirst.await(10, TimeUnit.SECONDS)).isTrue();
      Future<Outcome<Result>> second =
          pool.submit(
              () ->
                  idempotency.execute(
                      key(id),
                      null,
                      Result.class,
                      () -> {
                        runs.incrementAndGet();
                        return new Result("second", 1);
                      }));
      Thread.sleep(300); // 두 번째가 유일 키에서 기다리는 중
      assertThat(second.isDone()).isFalse();
      release.countDown();

      assertThat(first.get(10, TimeUnit.SECONDS).replayed()).isFalse();
      Outcome<Result> replayed = second.get(10, TimeUnit.SECONDS);
      assertThat(replayed.replayed()).isTrue();
      assertThat(replayed.value().id()).isEqualTo("first");
      assertThat(runs).hasValue(1);
    } finally {
      pool.shutdownNow();
    }
  }

  private static void assertConflict(Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            PmartException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT.name()));
  }

  private static int count(String sql, String id) {
    Integer n = jdbc.queryForObject(sql, Integer.class, id);
    return n == null ? 0 : n;
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
