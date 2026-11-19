package com.pmart.gateway.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

/** 2026-11-19 — 추적 http.uri 에 조회 조건(좌표·전화번호 등)을 남기지 않는다. */
class TraceUriWithoutQueryTest {

  @Test
  void dropsQueryAndFragment() {
    assertThat(
            TraceUriWithoutQuery.withoutQuery(
                URI.create(
                    "https://192.168.0.40:8443/api/v1/address-reverse-geocodes?lat=35.1&lng=129.0")))
        .isEqualTo("https://192.168.0.40:8443/api/v1/address-reverse-geocodes");
    assertThat(
            TraceUriWithoutQuery.withoutQuery(
                URI.create("http://h/api/v1/orders/lookups?phone=010#x")))
        .isEqualTo("http://h/api/v1/orders/lookups");
    assertThat(TraceUriWithoutQuery.withoutQuery(URI.create("http://h/api/v1/me")))
        .isEqualTo("http://h/api/v1/me");
  }
}
