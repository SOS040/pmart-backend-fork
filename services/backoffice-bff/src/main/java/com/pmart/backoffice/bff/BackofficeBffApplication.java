package com.pmart.backoffice.bff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** backoffice-bff. 책임·비소유는 services/backoffice-bff/CLAUDE.md 와 docs/architecture/service-map.md. */
@SpringBootApplication
public class BackofficeBffApplication {
  public static void main(String[] args) {
    SpringApplication.run(BackofficeBffApplication.class, args);
  }
}
