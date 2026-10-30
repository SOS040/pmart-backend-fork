package com.pmart.delivery.capacity.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.capacity.application.CapacityProperties;
import com.pmart.delivery.capacity.domain.SlotRules;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * center-master 고객 주문 운영 캘린더(서비스 토큰 내부 조회 — 공용 RestClient 가 {@code /internal/} 에 서비스 토큰을 붙인다). 센터
 * 없음 404, 시간표 미승인 422 CONFIG_REQUIRED 를 그대로 올린다.
 */
@Component
public class CenterCalendarClient {

  /** 그 날 운영 여부와 운영 구간(분), 캘린더 버전. */
  public record Day(boolean open, List<int[]> ranges, int calendarVersion) {}

  private final RestClient http;
  private final ObjectMapper mapper;

  public CenterCalendarClient(
      RestClient.Builder builder, CapacityProperties props, ObjectMapper mapper) {
    this.http = builder.baseUrl(props.centerMasterBaseUrl()).build();
    this.mapper = mapper;
  }

  public Day customerOrderDay(String centerId, LocalDate date) {
    String raw;
    try {
      raw =
          Downstream.call(
              "center-master-service calendar",
              () ->
                  http.get()
                      .uri(
                          "/api/v1/internal/centers/{id}/calendar?date={d}&kind=CUSTOMER_ORDER",
                          centerId,
                          date)
                      .retrieve()
                      .body(String.class));
    } catch (HttpClientErrorException.NotFound e) {
      throw PmartException.notFound("CENTER_NOT_FOUND", "센터가 없습니다: " + centerId);
    } catch (HttpClientErrorException.UnprocessableContent e) {
      throw PmartException.configRequired("center." + centerId + ".timetable.CUSTOMER_ORDER");
    }
    JsonNode n = mapper.readTree(raw == null ? "{}" : raw);
    List<int[]> ranges = new ArrayList<>();
    for (JsonNode r : n.path("ranges")) {
      ranges.add(
          new int[] {
            SlotRules.minutes(r.path("start").asString()),
            SlotRules.minutes(r.path("end").asString())
          });
    }
    return new Day(n.path("open").asBoolean(false), ranges, n.path("calendarVersion").asInt(0));
  }
}
