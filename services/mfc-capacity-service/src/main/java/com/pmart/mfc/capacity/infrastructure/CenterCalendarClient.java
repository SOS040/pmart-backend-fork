package com.pmart.mfc.capacity.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.capacity.application.PlanProperties;
import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * center-master 영업 캘린더 조회(서비스 경로 — 사용자 토큰을 넘기지 않음). 근무를 등록하지 않은 작업자의 근무 = 센터 직원 근무 시간표(STAFF, 사용자
 * 결정 2026-11-09 "풀타임 = 센터 운영시간"). 시간표가 승인되지 않았거나 센터가 없으면 CONFIG_REQUIRED(상시 근무로 바꾸지 않음).
 */
@Component
public class CenterCalendarClient {

  public static final String CENTER_MASTER = "center-master-service calendar";
  public static final String BASE_URL_KEY = "pmart.capacity.plan.center-master-base-url";

  private final @Nullable RestClient client;
  private final ObjectMapper mapper;

  public CenterCalendarClient(
      RestClient.Builder builder, PlanProperties props, ObjectMapper mapper) {
    this.client =
        props.centerMasterBaseUrl() == null || props.centerMasterBaseUrl().isBlank()
            ? null
            : builder.clone().baseUrl(Objects.requireNonNull(props.centerMasterBaseUrl())).build();
    this.mapper = mapper;
  }

  /** 그 날(Asia/Seoul 영업일)의 직원 근무 시간표 원문(CalendarDay). */
  public JsonNode staffDay(String centerId, LocalDate date) {
    RestClient c = client;
    if (c == null) {
      throw PmartException.configRequired(BASE_URL_KEY);
    }
    try {
      String raw =
          Downstream.call(
              CENTER_MASTER,
              () ->
                  c.get()
                      .uri(
                          "/api/v1/internal/centers/{c}/calendar?date={d}&kind=STAFF",
                          centerId,
                          date.toString())
                      .retrieve()
                      .body(String.class));
      return raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
    } catch (HttpClientErrorException e) {
      // 422 CONFIG_REQUIRED(시간표 미승인)·404(센터 없음) — 상시 근무로 바꾸지 않는다
      throw PmartException.configRequired("center." + centerId + ".timetable.STAFF(센터 직원 근무 시간표)");
    }
  }
}
