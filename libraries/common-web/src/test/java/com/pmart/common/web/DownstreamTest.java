package com.pmart.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

class DownstreamTest {

  private static ResourceAccessException io(Exception cause) {
    return new ResourceAccessException("I/O error", new java.io.IOException("wrap", cause));
  }

  private static void assertCode(Runnable call, ErrorCode code) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            PmartException.class,
            e -> {
              assertThat(e.code()).isEqualTo(code.name());
              assertThat(e.getCause()).isNotNull();
            });
  }

  @Test
  void returnsValueOnSuccess() {
    assertThat(Downstream.call("pricing quotes", () -> 42)).isEqualTo(42);
  }

  @Test
  void requestNotDeliveredIsDependencyUnavailable() {
    for (Exception cause :
        new Exception[] {
          new ConnectException("refused"),
          new HttpConnectTimeoutException("connect timed out"),
          new UnknownHostException("pricing-service")
        }) {
      assertCode(
          () ->
              Downstream.run(
                  "pricing quotes",
                  () -> {
                    throw io(cause);
                  }),
          ErrorCode.DEPENDENCY_UNAVAILABLE);
    }
  }

  @Test
  void responseTimeoutIsUnknownResultNotFailure() {
    for (Exception cause :
        new Exception[] {
          new HttpTimeoutException("request timed out"), new SocketTimeoutException("read")
        }) {
      assertCode(
          () ->
              Downstream.run(
                  "inventory reservations",
                  () -> {
                    throw io(cause);
                  }),
          ErrorCode.UNKNOWN_RESULT);
    }
  }

  @Test
  void serviceUnavailableIsRetryableButOtherServerErrorsAreUnknown() {
    assertCode(
        () ->
            Downstream.run(
                "x",
                () -> {
                  throw HttpServerErrorException.create(
                      HttpStatus.SERVICE_UNAVAILABLE, "503", null, null, null);
                }),
        ErrorCode.DEPENDENCY_UNAVAILABLE);
    assertCode(
        () ->
            Downstream.run(
                "x",
                () -> {
                  throw HttpServerErrorException.create(
                      HttpStatus.INTERNAL_SERVER_ERROR, "500", null, null, null);
                }),
        ErrorCode.UNKNOWN_RESULT);
  }

  @Test
  void clientErrorsPassThroughForTheCallerToInterpret() {
    assertThatThrownBy(
            () ->
                Downstream.run(
                    "x",
                    () -> {
                      throw HttpClientErrorException.create(
                          HttpStatus.UNPROCESSABLE_CONTENT, "422", null, null, null);
                    }))
        .isInstanceOf(HttpClientErrorException.class);
  }
}
