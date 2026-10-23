package com.pmart.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.status.Status;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** pmart-logback.xml 이 파싱되고, JSON 출력에 traceId/correlationId 가 포함되며 민감값이 마스킹되는지. */
class LogbackConfigTest {

  @Test
  void jsonProfileEmitsMdcFieldsAndMasksSensitiveValues() throws Exception {
    LoggerContext ctx = new LoggerContext();
    ctx.putProperty("serviceName", "order-service");
    // springProfile 태그는 Spring 이 처리한다. 순수 Logback 에서는 <springProfile name="!local"> 블록을 활성화하기 위해
    // 동일 설정을 Spring 없이 읽을 수 없으므로, 인코더만 직접 구성해 마스킹·MDC 포함 규칙을 검증한다.
    net.logstash.logback.encoder.LogstashEncoder encoder =
        new net.logstash.logback.encoder.LogstashEncoder();
    encoder.setContext(ctx);
    encoder.addIncludeMdcKeyName("traceId");
    encoder.addIncludeMdcKeyName("correlationId");
    net.logstash.logback.mask.MaskingJsonGeneratorDecorator masking =
        new net.logstash.logback.mask.MaskingJsonGeneratorDecorator();
    masking.setDefaultMask("***");
    masking.addValue("01[016789]-?\\d{3,4}-?\\d{4}");
    masking.start(); // LifeCycle: XML에서는 Joran이 자동 호출
    encoder.addDecorator(masking);
    encoder.start();

    ch.qos.logback.classic.Logger logger = ctx.getLogger("test");
    MDC.put("traceId", "t-1");
    MDC.put("correlationId", "c-1");
    ch.qos.logback.classic.spi.LoggingEvent event =
        new ch.qos.logback.classic.spi.LoggingEvent(
            "test",
            logger,
            ch.qos.logback.classic.Level.INFO,
            "call 010-1234-5678 now",
            null,
            null);
    event.setMDCPropertyMap(MDC.getCopyOfContextMap());
    MDC.clear();

    String json = new String(encoder.encode(event), StandardCharsets.UTF_8);

    assertThat(json).contains("\"traceId\":\"t-1\"").contains("\"correlationId\":\"c-1\"");
    assertThat(json).doesNotContain("010-1234-5678").contains("***");
  }

  @Test
  void configFileParsesWithoutErrors() throws Exception {
    LoggerContext ctx = new LoggerContext();
    JoranConfigurator configurator = new JoranConfigurator();
    configurator.setContext(ctx);
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    PrintStream original = System.err;
    System.setErr(new PrintStream(sink));
    try {
      configurator.doConfigure(getClass().getResource("/pmart-logback.xml"));
    } finally {
      System.setErr(original);
    }
    // springProfile/springProperty 는 순수 Logback 에서 알 수 없는 태그로 경고가 날 수 있으나 ERROR 는 없어야 한다.
    assertThat(ctx.getStatusManager().getCopyOfStatusList())
        .filteredOn(s -> s.getLevel() == Status.ERROR)
        .isEmpty();
  }
}
