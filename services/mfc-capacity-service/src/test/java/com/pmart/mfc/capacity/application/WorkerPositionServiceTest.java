package com.pmart.mfc.capacity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.pmart.mfc.capacity.infrastructure.LocationClient;
import com.pmart.mfc.capacity.infrastructure.WorkerLastLocationClient;
import com.pmart.mfc.capacity.infrastructure.WorkerLastLocationClient.Item;
import java.time.Instant;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** 작업자 도면 위치(0.34.0) — 작업자마다 늦은 한 건, 원천 일부 실패, 칸 좌표 읽기. */
class WorkerPositionServiceTest {

  static final Instant T = Instant.parse("2026-11-21T01:00:00Z");

  @Test
  void latestRecordPerWorkerWins() {
    var latest =
        WorkerPositionService.latestPerWorker(
            List.of(
                new Item("mfc-fulfillment-service", "w1", "A-C1-01-1-01", "PICK", T),
                new Item("mfc-inbound-service", "w1", "RCV-A-01", "PUTAWAY", T.plusSeconds(60)),
                new Item("mfc-fulfillment-service", "w2", "PAK-A-01", "PACK", T)));
    assertThat(latest)
        .extracting(Item::workerId, Item::locationCode)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("w1", "RCV-A-01"),
            org.assertj.core.groups.Tuple.tuple("w2", "PAK-A-01"));
  }

  @Test
  void collectsLastLocationsAndNamesFailedSource() {
    RestClient.Builder b = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(b).ignoreExpectOrder(true).build();
    PlanProperties plan =
        new PlanProperties(
            "http://ful", null, null, null, null, "http://inb", null, null, null, null, null);
    var client = new WorkerLastLocationClient(b, plan, JsonMapper.builder().build());
    server
        .expect(
            ExpectedCount.once(),
            requestTo(
                Matchers.startsWith(
                    "http://ful/api/v1/internal/worker-last-locations?mfcId=MFC-1&since=")))
        .andRespond(
            withSuccess(
                "{\"items\":[{\"workerId\":\"w1\",\"locationCode\":\"A-C1-01-1-01\",\"activityType\":\"PICK\",\"at\":\"2026-11-21T01:00:00Z\"}]}",
                MediaType.APPLICATION_JSON));
    server
        .expect(ExpectedCount.once(), requestTo(Matchers.startsWith("http://inb/")))
        .andRespond(withServerError());
    var c = client.collect("MFC-1", T);
    assertThat(c.items()).extracting(Item::workerId).containsExactly("w1");
    assertThat(c.unavailable()).containsExactly("mfc-inbound-service");
  }

  @Test
  void positionsReadFromDistanceAttributes() {
    RestClient.Builder b = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
    PlanProperties plan =
        new PlanProperties(
            null, null, null, null, null, null, null, "http://loc", null, null, null);
    var client = new LocationClient(b, plan, JsonMapper.builder().build());
    server
        .expect(requestTo("http://loc/api/v1/internal/location-distances"))
        .andRespond(
            withSuccess(
                "{\"codes\":[\"A-C1-01-1-01\"],\"meters\":[[0]],\"missing\":[\"PAK-A-01\"],\"attributes\":[{\"locationCode\":\"A-C1-01-1-01\",\"zone\":\"STORAGE\",\"temperatureZone\":\"AMBIENT\",\"position\":{\"xM\":4.5,\"zM\":12.0}}]}",
                MediaType.APPLICATION_JSON));
    var pos = client.positions("MFC-1", List.of("A-C1-01-1-01", "PAK-A-01"));
    assertThat(pos).containsOnlyKeys("A-C1-01-1-01");
    assertThat(pos.get("A-C1-01-1-01")).isEqualTo(new LocationClient.Position(4.5, 12.0));
  }
}
