package com.pmart.common.web;

import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.support.HttpRequestWrapper;

/**
 * 요청 주소의 호스트(서비스 이름)를 등록된 인스턴스 주소로 바꾼다(ADR-0032).
 *
 * <p>등록된 인스턴스가 없으면 {@link UnknownHostException} 을 던진다 — RestClient 가 {@code
 * ResourceAccessException} 으로 감싸고 {@link Downstream} 이 "요청이 가지 않았음"({@code DEPENDENCY_UNAVAILABLE},
 * 재시도 가능)으로 해석한다. Spring Cloud 기본 인터셉터는 이때 {@code IllegalStateException} 을 던져 알 수 없는 500 이 된다.
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
    if (serviceId == null) {
      throw new UnknownHostException("서비스 이름이 없는 주소: " + original);
    }
    LoadBalancerClient client = loadBalancer.getObject();
    ServiceInstance instance = client.choose(serviceId);
    if (instance == null) {
      throw new UnknownHostException("Eureka 에 등록된 인스턴스 없음: " + serviceId);
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
