package com.pmart.common.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code pmart.messaging.*}. */
@ConfigurationProperties(prefix = "pmart.messaging")
public class MessagingProperties {

  /** 이 서비스가 발행하는 이벤트의 토픽 도메인 접두사 (order, mfc, cdc, scm, tms, delivery, commerce, platform). 필수. */
  private String domain = "";

  /** 봉투 sourceSystem. 비우면 spring.application.name. */
  private String sourceSystem = "";

  private Outbox outbox = new Outbox();
  private Consumer consumer = new Consumer();

  public static class Outbox {
    /** INSERT 직후 같은 트랜잭션에서 DELETE (테이블을 비워 둠. Debezium 은 WAL 의 INSERT 를 캡처). */
    private boolean deleteAfterInsert = true;

    /** 저장 전 계약 검증 모드. */
    private EventSchemaValidator.Mode schemaValidation = EventSchemaValidator.Mode.ENFORCE;

    public boolean isDeleteAfterInsert() {
      return deleteAfterInsert;
    }

    public void setDeleteAfterInsert(boolean deleteAfterInsert) {
      this.deleteAfterInsert = deleteAfterInsert;
    }

    public EventSchemaValidator.Mode getSchemaValidation() {
      return schemaValidation;
    }

    public void setSchemaValidation(EventSchemaValidator.Mode schemaValidation) {
      this.schemaValidation = schemaValidation;
    }
  }

  public static class Consumer {
    /** 재시도 횟수(초기 처리 제외). 소진 시 {topic}.DLT 로 보낸다. */
    private int maxRetries = 3;

    /** 재시도 초기 간격(ms), 지수 배수 2. */
    private long initialBackoffMs = 1000;

    private long maxBackoffMs = 10000;

    public int getMaxRetries() {
      return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
      this.maxRetries = maxRetries;
    }

    public long getInitialBackoffMs() {
      return initialBackoffMs;
    }

    public void setInitialBackoffMs(long initialBackoffMs) {
      this.initialBackoffMs = initialBackoffMs;
    }

    public long getMaxBackoffMs() {
      return maxBackoffMs;
    }

    public void setMaxBackoffMs(long maxBackoffMs) {
      this.maxBackoffMs = maxBackoffMs;
    }
  }

  public String getDomain() {
    return domain;
  }

  public void setDomain(String domain) {
    this.domain = domain;
  }

  public String getSourceSystem() {
    return sourceSystem;
  }

  public void setSourceSystem(String sourceSystem) {
    this.sourceSystem = sourceSystem;
  }

  public Outbox getOutbox() {
    return outbox;
  }

  public void setOutbox(Outbox outbox) {
    this.outbox = outbox;
  }

  public Consumer getConsumer() {
    return consumer;
  }

  public void setConsumer(Consumer consumer) {
    this.consumer = consumer;
  }
}
