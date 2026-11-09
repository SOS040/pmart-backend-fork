package com.pmart.cases.infrastructure;

import com.pmart.cases.application.CaseProperties;
import com.pmart.common.web.Downstream;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * order-service 본인 주문 확인(동기 1 hop, 호출자 고객 토큰 전달). order `GET /orders/{id}` 는 고객 토큰만 받고 남의·없는 주문은
 * 404 — 그래서 200 이면 본인 주문이다. 연결 실패·5xx 는 {@link Downstream} 이 503/UNKNOWN 으로 바꾼다.
 */
@Component
public class OrderClient {

  public static final String ORDER = "order-service";

  private final RestClient client;

  public OrderClient(RestClient.Builder builder, CaseProperties props) {
    this.client =
        builder
            .clone()
            .baseUrl(Objects.requireNonNullElse(props.orderServiceBaseUrl(), "http://unconfigured"))
            .build();
  }

  /** 호출한 고객의 주문인가. */
  public boolean ownsOrder(String orderId) {
    String auth = currentAuthorization();
    Integer status =
        Downstream.call(
            ORDER,
            () ->
                client
                    .get()
                    .uri("/api/v1/orders/{id}", orderId)
                    .headers(
                        h -> {
                          if (auth != null) {
                            h.set(HttpHeaders.AUTHORIZATION, auth);
                          }
                        })
                    .exchange(
                        (req, res) -> {
                          int s = res.getStatusCode().value();
                          if (s >= 500) {
                            throw new IllegalStateException("order-service " + s);
                          }
                          return s;
                        }));
    return status != null && status == 200;
  }

  private static @Nullable String currentAuthorization() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
      return a.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
    }
    return null;
  }
}
