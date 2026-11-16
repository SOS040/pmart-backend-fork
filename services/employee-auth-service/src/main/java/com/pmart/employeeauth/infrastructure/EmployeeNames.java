package com.pmart.employeeauth.infrastructure;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 직원 표시 이름(2026-11-17 사용자 결정 — 인사팀 앱 권한 상세의 승인 칸이 이름 대신 OP-… 로 보임 → 직원 조회 응답에 {@code
 * approverName}·{@code revokedByName}). 이름의 정본은 hr-service 다 — 이 서비스는 이름을 저장하지 않으므로(CLAUDE.md 데이터)
 * 응답마다 hr-service 내부 조회 {@code GET /internal/employee-names} 를 <b>한 번</b>(ID 묶어서) 부르고, 메모리에도 들고 있지
 * 않는다. 화면 표시용이라 못 읽으면(장애·시간 초과·모르는 ID) 그 이름만 빠지고 조회 응답은 실패시키지 않는다. 이름은 로그에 남기지 않는다(개인정보).
 */
@Component
public class EmployeeNames {

  private static final Logger log = LoggerFactory.getLogger(EmployeeNames.class);

  /** hr-service 계약의 형식과 같다. 자동 승인 {@code RULE:…} 은 사람이 아니라 부르지 않는다. */
  private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_.-]{1,128}$");

  static final int MAX_IDS = 200;

  private final RestClient client;
  private final ObjectMapper mapper;

  public EmployeeNames(
      RestClient.Builder builder,
      ObjectMapper mapper,
      @Value("${pmart.employee-auth.hr-base-url:http://hr-service:8512}") String baseUrl) {
    this.client = builder.clone().baseUrl(baseUrl).build();
    this.mapper = mapper;
  }

  /** 운영자 ID → 이름. 모르는 ID·자동 승인·읽기 실패는 빠진다(빈 맵일 수 있음). */
  public Map<String, String> names(Collection<String> operatorIds) {
    Set<String> ids = new LinkedHashSet<>();
    for (String id : operatorIds) {
      if (id != null && ID.matcher(id).matches() && ids.size() < MAX_IDS) {
        ids.add(id);
      }
    }
    Map<String, String> out = new ConcurrentHashMap<>();
    if (ids.isEmpty()) {
      return out;
    }
    List<String> query = List.copyOf(ids);
    try {
      String raw =
          client
              .get()
              .uri(
                  u ->
                      u.path("/api/v1/internal/employee-names")
                          .queryParam("operatorIds", query.toArray())
                          .build())
              .retrieve()
              .body(String.class);
      JsonNode items = mapper.readTree(raw == null ? "{}" : raw).path("items");
      for (JsonNode i : items) {
        String id = i.path("operatorId").asString("");
        String name = i.path("name").asString("");
        if (ids.contains(id) && !name.isBlank()) {
          out.put(id, name);
        }
      }
    } catch (RuntimeException ex) {
      // 이름·ID 목록은 남기지 않는다 — 몇 개였는지와 원인 종류만
      log.debug("직원 이름을 읽지 못함 ({}개): {}", ids.size(), ex.getClass().getSimpleName());
    }
    return out;
  }
}
