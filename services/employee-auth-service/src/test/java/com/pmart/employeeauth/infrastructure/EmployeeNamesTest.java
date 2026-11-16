package com.pmart.employeeauth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** 직원 이름(0.10.0): ID 를 묶어 한 번, 자동 승인(RULE:…)·중복은 빼고, 실패는 빈 결과(조회 응답을 막지 않음). */
class EmployeeNamesTest {

  @Test
  void batchesDistinctPersonIdsAndFallsBackToEmptyOnFailure() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(
            ExpectedCount.once(),
            requestTo("http://hr/api/v1/internal/employee-names?operatorIds=OP-1&operatorIds=OP-2"))
        .andRespond(
            withSuccess(
                "{\"items\":[{\"operatorId\":\"OP-1\",\"name\":\"김승인\"},"
                    + "{\"operatorId\":\"OP-X\",\"name\":\"묻지 않은 ID\"}]}",
                MediaType.APPLICATION_JSON));
    server
        .expect(
            ExpectedCount.once(),
            requestTo("http://hr/api/v1/internal/employee-names?operatorIds=OP-1"))
        .andRespond(withServerError());

    EmployeeNames names = new EmployeeNames(builder, JsonMapper.builder().build(), "http://hr");
    assertThat(
            names.names(
                Arrays.asList("OP-1", "RULE:GRANT-MFC-WORKER-OWN-CENTER@v1", "OP-2", "OP-1", null)))
        .containsOnlyKeys("OP-1")
        .containsEntry("OP-1", "김승인");
    assertThat(names.names(List.of("OP-1"))).as("hr-service 장애 → 이름 없음").isEmpty();
    assertThat(names.names(List.of("RULE:X@v1"))).as("사람이 없으면 부르지 않는다").isEmpty();
    server.verify();
  }
}
