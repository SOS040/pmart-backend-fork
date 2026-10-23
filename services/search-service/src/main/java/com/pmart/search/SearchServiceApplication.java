package com.pmart.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** search-service. 책임·비소유는 services/search-service/CLAUDE.md 와 docs/architecture/service-map.md. */
@SpringBootApplication
public class SearchServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(SearchServiceApplication.class, args);
  }
}
