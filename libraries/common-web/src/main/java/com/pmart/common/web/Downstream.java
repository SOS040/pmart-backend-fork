package com.pmart.common.web;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * 서비스 간 동기 호출 결과의 해석 규약(api-and-events.md "시간 초과를 상대 서비스의 업무 실패로 단정하지 않는다").
 *
 * <ul>
 *   <li>요청이 상대에게 <b>가지 않았음</b>이 확실(연결 거부·연결 시간 초과·호스트 없음), 또는 상대가 503 → {@code
 *       DEPENDENCY_UNAVAILABLE} (재시도 가능).
 *   <li>요청이 <b>갔을 수 있음</b>(응답 시간 초과·응답 중 끊김·그 밖의 5xx) → {@code UNKNOWN_RESULT}. 호출자는 같은 키 재전송이나 원결과
 *       조회로만 확정한다. 실패로 보고 보상하지 않는다.
 *   <li>4xx 는 상대의 명시적 판정이므로 그대로 던진다({@code HttpClientErrorException}). 호출자가 Problem 코드를 해석한다.
 * </ul>
 */
public final class Downstream {

  private Downstream() {}

  /**
   * @param target 로그·오류 문구용 대상 이름(예: "pricing-service quotes"). 개인정보·토큰을 넣지 않는다
   */
  public static <T> T call(String target, Supplier<T> call) {
    try {
      return call.get();
    } catch (ResourceAccessException e) {
      if (notDelivered(e)) {
        throw PmartException.dependencyUnavailable(target + " 에 연결하지 못했습니다.", e);
      }
      throw unknown(target, e);
    } catch (HttpServerErrorException e) {
      if (e.getStatusCode().value() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
        throw PmartException.dependencyUnavailable(target + " 가 일시적으로 응답할 수 없습니다.", e);
      }
      throw unknown(target, e);
    }
  }

  /** 반환값이 없는 호출. */
  public static void run(String target, Runnable call) {
    call(
        target,
        () -> {
          call.run();
          return Boolean.TRUE;
        });
  }

  /** 연결 단계 실패(요청 미전달). 응답 시간 초과는 여기 들지 않는다. */
  static boolean notDelivered(Throwable e) {
    for (@Nullable Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof HttpConnectTimeoutException
          || t instanceof ConnectException
          || t instanceof UnknownHostException
          || t instanceof NoRouteToHostException) {
        return true;
      }
      if (t instanceof InterruptedIOException && !(t instanceof HttpConnectTimeoutException)) {
        // SocketTimeoutException·HttpTimeoutException(응답 대기) — 요청은 이미 나갔을 수 있다
        return false;
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return false;
  }

  private static PmartException unknown(String target, Exception cause) {
    return new PmartException(
        ErrorCode.UNKNOWN_RESULT.name(),
        ErrorCode.UNKNOWN_RESULT.status(),
        target + " 의 처리 결과를 확인하지 못했습니다. 원결과를 조회하세요.",
        ErrorCode.UNKNOWN_RESULT.retryable(),
        cause);
  }
}
