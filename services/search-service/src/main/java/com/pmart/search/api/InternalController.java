package com.pmart.search.api;

import com.pmart.search.api.generated.InternalApi;
import com.pmart.search.api.generated.model.GetIndexStatus200Response;
import com.pmart.search.application.IndexStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * 계약 InternalApi 의 구현 진입점. 아직 구현하지 않은 메서드는 인터페이스 default 가 501 을 돌려준다. 업무 규칙은 application/domain 에
 * 두고 여기서는 요청·응답 변환만 한다. 구현: 색인 신선도(2026-11-03). 재색인 요청은 501(색인 주체가 Logstash — 방식 결정 대기).
 */
@RestController
public class InternalController implements InternalApi {

  private final IndexStatus indexStatus;
  private final ObjectMapper mapper;

  public InternalController(IndexStatus indexStatus, ObjectMapper mapper) {
    this.indexStatus = indexStatus;
    this.mapper = mapper;
  }

  @Override
  public ResponseEntity<GetIndexStatus200Response> getIndexStatus() {
    IndexStatus.Status s = indexStatus.status();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("documents", s.documents());
    List<Map<String, Object>> sources =
        s.sources().stream()
            .map(
                x -> {
                  Map<String, Object> y = new LinkedHashMap<>();
                  y.put("eventType", x.eventType());
                  y.put("lastAppliedAt", x.lastAppliedAt().toString());
                  y.put("lagSeconds", x.lagSeconds());
                  return y;
                })
            .toList();
    m.put("sources", sources);
    m.put("asOf", Map.of("asOf", s.asOf().toString(), "freshness", "CURRENT"));
    return ResponseEntity.ok(mapper.convertValue(m, GetIndexStatus200Response.class));
  }
}
