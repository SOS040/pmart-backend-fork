package com.pmart.employeeauth.infrastructure;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 센터 표시 이름(2026-11-16 사용자 "센터 이름 넣고 싶어" — 현재 세션 응답 {@code centerDetails}). center-master-service 내부
 * 조회 {@code GET /internal/centers/{centerId}} 의 {@code name} 을 10분 동안 들고 있는다. 화면 표시용이라 못 읽으면(장애·없는
 * 센터) 이름 없이 돌려주고 세션 응답을 실패시키지 않는다 — 실패도 1분 동안 기억해 장애 중에 매 요청 기다리지 않는다.
 */
@Component
public class CenterNames {

  private static final Logger log = LoggerFactory.getLogger(CenterNames.class);
  static final Duration TTL = Duration.ofMinutes(10);
  static final Duration FAILURE_TTL = Duration.ofMinutes(1);

  private record Entry(@Nullable String name, Instant until) {}

  private final RestClient client;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

  public CenterNames(
      RestClient.Builder builder,
      ObjectMapper mapper,
      Clock clock,
      @Value("${pmart.employee-auth.center-master-base-url:http://center-master-service:8507}")
          String baseUrl) {
    this.client = builder.clone().baseUrl(baseUrl).build();
    this.mapper = mapper;
    this.clock = clock;
  }

  /** 센터 이름, 모르면 null. */
  public @Nullable String name(String centerId) {
    Instant now = clock.instant();
    Entry e = cache.get(centerId);
    if (e != null && now.isBefore(e.until())) {
      return e.name();
    }
    String name = null;
    Duration ttl = TTL;
    try {
      String raw =
          client.get().uri("/api/v1/internal/centers/{id}", centerId).retrieve().body(String.class);
      JsonNode n = mapper.readTree(raw == null ? "{}" : raw);
      String v = n.path("name").asString("");
      name = v.isBlank() ? null : v;
    } catch (RuntimeException ex) {
      ttl = FAILURE_TTL;
      log.debug("센터 이름을 읽지 못함 {}: {}", centerId, ex.toString());
    }
    cache.put(centerId, new Entry(name, now.plus(ttl)));
    return name;
  }
}
