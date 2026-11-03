package com.pmart.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerRequest;
import org.springframework.cloud.client.loadbalancer.LoadBalancerUriTools;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class LoadBalancedInterceptorTest {

  /** 등록부 대신: 서비스 이름 → 인스턴스 하나. */
  private static final class FakeLoadBalancer implements LoadBalancerClient {
    final Map<String, ServiceInstance> instances = new HashMap<>();

    @Override
    public @Nullable ServiceInstance choose(String serviceId) {
      return instances.get(serviceId);
    }

    @Override
    public <T> @Nullable ServiceInstance choose(String serviceId, Request<T> request) {
      return choose(serviceId);
    }

    @Override
    public <T> T execute(String serviceId, LoadBalancerRequest<T> request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T execute(
        String serviceId, ServiceInstance serviceInstance, LoadBalancerRequest<T> request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public URI reconstructURI(ServiceInstance instance, URI original) {
      return LoadBalancerUriTools.reconstructURI(instance, original);
    }
  }

  private final FakeLoadBalancer registry = new FakeLoadBalancer();

  private RestClient.Builder builder() {
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("loadBalancer", registry);
    RestClient.Builder b = RestClient.builder();
    new LoadBalancedRestClientAutoConfiguration()
        .pmartLoadBalancedRestClientCustomizer(beans.getBeanProvider(LoadBalancerClient.class))
        .customize(b);
    return b;
  }

  @Test
  void hostIsServiceNameAndPortComesFromRegisteredInstance() {
    registry.instances.put(
        "pricing-service",
        new DefaultServiceInstance(
            "pricing-service:10.0.0.5:8107", "pricing-service", "10.0.0.5", 9107, false));
    RestClient.Builder b = builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
    server
        .expect(requestTo("http://10.0.0.5:9107/api/v1/prices?skuId=S1"))
        .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

    String body =
        b.build()
            .get()
            .uri("http://pricing-service:8107/api/v1/prices?skuId=S1")
            .retrieve()
            .body(String.class);

    assertThat(body).isEqualTo("ok");
    server.verify();
  }

  @Test
  void noRegisteredInstanceIsDependencyUnavailable() {
    RestClient client = builder().build();

    assertThatThrownBy(
            () ->
                Downstream.call(
                    "pricing quotes",
                    () ->
                        client
                            .get()
                            .uri("http://pricing-service:8107/x")
                            .retrieve()
                            .body(String.class)))
        .isInstanceOfSatisfying(
            PmartException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE.name()));
  }
}
