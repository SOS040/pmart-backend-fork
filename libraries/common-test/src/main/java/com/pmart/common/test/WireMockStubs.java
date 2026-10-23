package com.pmart.common.test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;

/**
 * 외부 시스템(PG 등) 응답·오류·지연 재현 헬퍼 (testing.md). 서비스 테스트에서는
 * {@code @EnableWireMock}(wiremock-spring-boot) 로 서버를 띄우고 이 헬퍼로 스텁을 등록한다.
 *
 * <p>원칙: 타임아웃은 상대의 실패가 아니다 — {@link #unknownResult} 로 "응답 불명"을 재현해 원결과 조회 경로를 테스트한다.
 */
public final class WireMockStubs {

  private WireMockStubs() {}

  /** 정상 JSON 응답. */
  public static StubMapping jsonPost(WireMockServer server, String path, int status, String body) {
    return server.stubFor(
        post(urlPathEqualTo(path))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  /** RFC 9457 오류 응답. */
  public static StubMapping problemPost(
      WireMockServer server, String path, int status, String code, String detail) {
    String body =
        "{\"type\":\"https://problems.pmart.local/"
            + code
            + "\",\"title\":\""
            + code
            + "\",\"status\":"
            + status
            + ",\"detail\":\""
            + detail
            + "\",\"code\":\""
            + code
            + "\",\"retryable\":false}";
    return server.stubFor(
        post(urlPathEqualTo(path))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody(body)));
  }

  /** 응답 불명 재현: 지연 후 연결 종료(클라이언트 read timeout 보다 길게). */
  public static StubMapping unknownResult(WireMockServer server, String path, int delayMillis) {
    return server.stubFor(
        post(urlPathEqualTo(path))
            .willReturn(aResponse().withFixedDelay(delayMillis).withStatus(200).withBody("{}")));
  }

  /** 5xx. */
  public static StubMapping serverError(WireMockServer server, String path) {
    return server.stubFor(post(urlPathEqualTo(path)).willReturn(aResponse().withStatus(503)));
  }
}
