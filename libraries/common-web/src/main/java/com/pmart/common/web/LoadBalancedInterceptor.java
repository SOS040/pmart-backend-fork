package com.pmart.common.web;

import java.io.IOException;
import java.net.URI;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.support.HttpRequestWrapper;

/**
 * 요청 주소의 호스트(서비스 이름)가 Eureka 에 등록돼 있으면 그 인스턴스 주소로 바꾼다(ADR-0032).
 *
 * <p>등록이 없는 호스트는 <b>원래 주소 그대로</b> 보낸다. Eureka 클라이언트 자신도 같은 {@code RestClient.Builder} 로 {@code
 * eureka-server} 에 등록·조회하므로(2026-11-03 LAN 에서 확인), 등록부에만 의존하면 등록 자체를 못 한다. 기동 직후 등록부가 비어 있을 때도 LAN
 * 은 이름 + {@code extra_hosts} 로 닿는다. 상대가 정말 없으면 연결 거부 → {@link Downstream} 이 {@code
 * DEPENDENCY_UNAVAILABLE} 로 해석한다.
 */
final class LoadBalancedInterceptor implements ClientHttpRequestInterceptor {

  private final ObjectProvider<LoadBalancerClient> loadBalancer;

  LoadBalancedInterceptor(ObjectProvider<LoadBalancerClient> loadBalancer) {
    this.loadBalancer = loadBalancer;
  }

  @Override
  public ClientHttpResponse intercept(
      HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
    URI original = request.getURI();
    String serviceId = original.getHost();
    LoadBalancerClient client = loadBalancer.getIfAvailable();
    ServiceInstance instance =
        serviceId == null || client == null ? null : client.choose(serviceId);
    if (instance == null) {
      return execution.execute(request, body);
    }
    URI target = client.reconstructURI(instance, original);
    return execution.execute(
        new HttpRequestWrapper(request) {
          @Override
          public URI getURI() {
            return target;
          }
        },
        body);
  }
}
