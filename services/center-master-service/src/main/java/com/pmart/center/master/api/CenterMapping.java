package com.pmart.center.master.api;

import com.pmart.center.master.api.generated.model.CalendarDay;
import com.pmart.center.master.api.generated.model.CalendarDayAsOf;
import com.pmart.center.master.api.generated.model.CalendarDayTemporaryRestrictionsInner;
import com.pmart.center.master.api.generated.model.Center;
import com.pmart.center.master.api.generated.model.CenterCoordinates;
import com.pmart.center.master.api.generated.model.CenterState;
import com.pmart.center.master.api.generated.model.CenterType;
import com.pmart.center.master.api.generated.model.TimeRange;
import com.pmart.center.master.api.generated.model.TimetableKind;
import com.pmart.center.master.application.CenterService.CalendarView;
import com.pmart.center.master.domain.Timetable;
import com.pmart.center.master.infrastructure.CenterStore.CenterRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 계약 DTO 변환. 진입 위치·층수·반입 조건 등은 승인된 profile JSON 그대로. */
@Component
class CenterMapping {

  private final ObjectMapper mapper;

  CenterMapping(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  Center center(CenterRow c) {
    ObjectNode profile = (ObjectNode) mapper.readTree(c.profile());
    // 핵심 필드는 열 값이 정본 — profile 에서 빼고 따로 채운다
    List.of(
            "centerId",
            "version",
            "type",
            "name",
            "state",
            "address",
            "coordinates",
            "temperatureZones")
        .forEach(profile::remove);
    Center out = mapper.treeToValue(profile, Center.class);
    out.centerId(c.centerId())
        .version(c.version())
        .type(CenterType.fromValue(c.type()))
        .name(c.name())
        .state(CenterState.fromValue(c.state()))
        .address(c.address())
        .approvedBy(c.approvedBy())
        .approvalRef(c.approvalRef())
        .effectiveFrom(time(c.effectiveFrom()))
        .updatedAt(time(c.updatedAt()));
    if (c.lat() != null && c.lng() != null) {
      out.coordinates(
          new CenterCoordinates()
              .lat(BigDecimal.valueOf(c.lat()))
              .lng(BigDecimal.valueOf(c.lng())));
    }
    List<Center.TemperatureZonesEnum> zones = new ArrayList<>();
    for (JsonNode z : mapper.readTree(c.temperatureZones())) {
      zones.add(Center.TemperatureZonesEnum.fromValue(z.asString()));
    }
    out.temperatureZones(zones);
    return out;
  }

  static CalendarDay calendar(CalendarView v, Instant now) {
    Timetable.Day d = v.day();
    return new CalendarDay()
        .centerId(v.center().centerId())
        .date(d.date())
        .kind(TimetableKind.fromValue(v.timetable().kind()))
        .calendarVersion(v.timetable().version())
        .isHoliday(d.holiday())
        .open(d.open())
        .ranges(
            d.ranges().stream().map(r -> new TimeRange().start(r.start()).end(r.end())).toList())
        .temporaryRestrictions(
            d.restrictions().stream()
                .map(
                    r ->
                        new CalendarDayTemporaryRestrictionsInner()
                            .range(new TimeRange().start(r.range().start()).end(r.range().end()))
                            .reason(r.reason())
                            .approvalRef(v.timetable().approvalRef()))
                .toList())
        .approvedBy(v.timetable().approvedBy())
        .approvalRef(v.timetable().approvalRef())
        .asOf(
            new CalendarDayAsOf()
                .asOf(time(now))
                .sourceVersion(v.timetable().version())
                .freshness(CalendarDayAsOf.FreshnessEnum.CURRENT));
  }

  static OffsetDateTime time(Instant i) {
    return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
