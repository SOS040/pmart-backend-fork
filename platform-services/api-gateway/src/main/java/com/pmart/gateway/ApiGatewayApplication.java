package com.pmart.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** API Gateway (Spring Cloud Gateway Server WebFlux, Reactor Netty). 라우팅 표는 application.yml. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ApiGatewayApplication {

  public static void main(String[] args) {
    SpringApplication.run(ApiGatewayApplication.class, args);
  }
}
