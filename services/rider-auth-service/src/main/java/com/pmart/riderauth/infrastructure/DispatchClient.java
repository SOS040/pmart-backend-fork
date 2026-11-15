package com.pmart.riderauth.infrastructure;

import com.pmart.common.web.Downstream;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * delivery-dispatch 내부 호출 — 라이더가 맡고 있는 배송({@code GET /internal/riders/{id}/active-deliveries},
 * dispatch 0.3.3). 정지 라이더 로그인 끊기 시점을 정한다. {@code /internal} 은 토큰 없이(공용 RestClient 가 X-Source-System
 * 을 붙인다). 못 부르면 {@link Downstream} 예외 — 호출자는 다음 점검 때 다시 본다.
 */
@Component
public class DispatchClient {

  private final RestClient client;
  private final ObjectMapper mapper;

  public DispatchClient(
      RestClient.Builder builder,
      ObjectMapper mapper,
      @Value("${pmart.rider-auth.dispatch-base-url:http://delivery-dispatch-service:8404}")
          String baseUrl) {
    this.client = builder.clone().baseUrl(baseUrl).build();
    this.mapper = mapper;
  }

  public List<String> activeDeliveries(String riderId) {
    String raw =
        Downstream.call(
            "delivery-dispatch active-deliveries",
            () ->
                client
                    .get()
                    .uri("/api/v1/internal/riders/{id}/active-deliveries", riderId)
                    .retrieve()
                    .body(String.class));
    JsonNode n = mapper.readTree(raw == null ? "{}" : raw);
    if (!n.path("deliveryIds").isArray()) {
      throw new IllegalStateException("dispatch 응답에 deliveryIds 가 없습니다");
    }
    return n.path("deliveryIds").valueStream().map(JsonNode::asString).toList();
  }
}
