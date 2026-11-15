package com.pmart.employeeauth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** 센터 이름(0.9.0): 10분 캐시, 실패는 null + 1분 기억(세션 응답을 막지 않음). */
class CenterNamesTest {

  final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-16T00:00:00Z"));
  final Clock clock =
      new Clock() {
        @Override
        public ZoneOffset getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return now.get();
        }
      };

  @Test
  void cachesNameAndFallsBackToNullOnFailure() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(ExpectedCount.once(), requestTo("http://cm/api/v1/internal/centers/MFC-1"))
        .andRespond(
            withSuccess(
                "{\"centerId\":\"MFC-1\",\"name\":\"강남 1센터\"}", MediaType.APPLICATION_JSON));
    server
        .expect(ExpectedCount.once(), requestTo("http://cm/api/v1/internal/centers/MFC-2"))
        .andRespond(withServerError());
    server
        .expect(ExpectedCount.once(), requestTo("http://cm/api/v1/internal/centers/MFC-1"))
        .andRespond(
            withSuccess(
                "{\"centerId\":\"MFC-1\",\"name\":\"강남 1센터(새 이름)\"}", MediaType.APPLICATION_JSON));

    CenterNames names = new CenterNames(builder, JsonMapper.builder().build(), clock, "http://cm");
    assertThat(names.name("MFC-1")).isEqualTo("강남 1센터");
    assertThat(names.name("MFC-1")).as("캐시").isEqualTo("강남 1센터");
    assertThat(names.name("MFC-2")).as("실패 → null").isNull();
    assertThat(names.name("MFC-2")).as("실패도 1분 기억 — 다시 부르지 않음").isNull();

    now.set(now.get().plusSeconds(11 * 60));
    assertThat(names.name("MFC-1")).as("10분 뒤 다시 읽음").isEqualTo("강남 1센터(새 이름)");
    server.verify();
  }
}
