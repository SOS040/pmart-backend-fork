package com.pmart.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** auth-service. 책임·비소유는 services/auth-service/CLAUDE.md 와 docs/architecture/service-map.md. */
@SpringBootApplication
public class AuthServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(AuthServiceApplication.class, args);
  }
}
