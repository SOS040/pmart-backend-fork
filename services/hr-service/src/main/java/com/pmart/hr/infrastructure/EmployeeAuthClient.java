package com.pmart.hr.infrastructure;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.hr.application.HrProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * employee-auth-service 내부 조회(계약 employee-auth-service 0.11.0 — 2026-11-17 HR-2 권한 요청 검사). 역할 목록·센터
 * 지정과 직원의 지금 권한(grant)의 정본은 employee-auth-service 다(ADR-0056 결정 4) — hr-service 는 요청을 받을 때 읽기만 하고
 * 저장하지 않는다.
 *
 * <p>토큰 없는 내부 호출(앱 공용 {@code RestClient.Builder} — X-Source-System, LAN 은 Eureka 이름 풀기, 기본 시간 한도 연결
 * 1초·응답 5초). 읽지 못하면(연결 실패·시간 초과·오류 응답·형식 틀림) 검사 없이 받지 않고 503 {@code DEPENDENCY_UNAVAILABLE}.
 */
@Component
public class EmployeeAuthClient {

  private static final Logger log = LoggerFactory.getLogger(EmployeeAuthClient.class);

  /** 역할 한 줄(역할 목록 — 센터 지정 여부). */
  public record Role(String code, boolean centerScoped) {}

  /** 승인된 권한 한 줄. {@code validUntil} = 이 시각부터 무효(null = 기한 없음). */
  public record Grant(
      String grantId,
      String role,
      List<String> centers,
      Instant validFrom,
      @Nullable Instant validUntil) {}

  private final RestClient client;
  private final ObjectMapper mapper;

  public EmployeeAuthClient(RestClient.Builder builder, ObjectMapper mapper, HrProperties props) {
    this.client = builder.clone().baseUrl(props.employeeAuthBaseUrl()).build();
    this.mapper = mapper;
  }

  /** 역할 코드 → 역할(설정 순서). */
  public Map<String, Role> roles() {
    JsonNode body = get("/api/v1/internal/roles", null);
    Map<String, Role> out = new LinkedHashMap<>();
    for (JsonNode i : body.path("items")) {
      String code = i.path("code").asString("");
      if (!code.isBlank()) {
        out.put(code, new Role(code, i.path("centerScoped").asBoolean(false)));
      }
    }
    if (out.isEmpty()) {
      // 역할이 하나도 없으면 모든 요청이 "모르는 역할"이 된다 — 설정 문제로 보고 받지 않는다
      throw unavailable(null);
    }
    return out;
  }

  /** 계정에 직접 둔 값을 나타내는 grantId(2026-11-18 HR-2 ③). 기간은 기한 없음. */
  public static final String ACCOUNT = "account";

  /**
   * 지금 기준으로 끝나지 않은 grant(유효하거나 시작 전) + 계정에 직접 둔 역할(employee-auth 0.12.0 {@code account} — 역할마다 한 줄,
   * grantId {@link #ACCOUNT}, 시작 {@link Instant#EPOCH}·끝 없음, 센터 = 계정 센터. 역할 없이 센터만 있으면 역할 "" 한 줄 —
   * 섞임 검사가 센터를 보게).
   */
  public List<Grant> activeOrFutureGrants(String operatorId) {
    JsonNode body = get("/api/v1/internal/employees/{operatorId}/grants", operatorId);
    List<Grant> out = new ArrayList<>();
    try {
      for (JsonNode g : body.path("items")) {
        List<String> centers = new ArrayList<>();
        g.path("centers").forEach(c -> centers.add(c.asString()));
        String until = g.path("validUntil").asString(null);
        out.add(
            new Grant(
                g.path("grantId").asString(),
                g.path("role").asString(),
                List.copyOf(centers),
                Instant.parse(g.path("validFrom").asString()),
                until == null || until.isBlank() ? null : Instant.parse(until)));
      }
      JsonNode account = body.path("account");
      if (account.isObject()) {
        List<String> centers = new ArrayList<>();
        account.path("centers").forEach(c -> centers.add(c.asString()));
        List<String> roles = new ArrayList<>();
        account.path("roles").forEach(r -> roles.add(r.asString()));
        if (roles.isEmpty() && !centers.isEmpty()) {
          roles.add("");
        }
        for (String r : roles) {
          out.add(new Grant(ACCOUNT, r, List.copyOf(centers), Instant.EPOCH, null));
        }
      }
    } catch (RuntimeException e) {
      throw unavailable(e);
    }
    return out;
  }

  private JsonNode get(String path, @Nullable String operatorId) {
    try {
      String raw =
          client
              .get()
              .uri(
                  u -> {
                    u.path(path);
                    if (operatorId != null) {
                      u.queryParam("activeOrFuture", true);
                      return u.build(operatorId);
                    }
                    return u.build();
                  })
              .retrieve()
              .body(String.class);
      return mapper.readTree(raw == null ? "{}" : raw);
    } catch (RuntimeException e) {
      throw unavailable(e);
    }
  }

  private static PmartException unavailable(@Nullable Throwable cause) {
    // 운영자 ID·응답 본문은 남기지 않는다 — 원인 종류만
    log.warn(
        "employee-auth-service 내부 조회 실패 — 권한 요청을 받지 않음: {}",
        cause == null ? "역할 목록 비어 있음" : cause.getClass().getSimpleName());
    String message = "employee-auth-service 에 닿지 않아 권한 요청을 검사하지 못했습니다. 잠시 뒤 다시 시도하세요.";
    return cause == null
        ? new PmartException(ErrorCode.DEPENDENCY_UNAVAILABLE, message)
        : PmartException.dependencyUnavailable(message, cause);
  }
}
