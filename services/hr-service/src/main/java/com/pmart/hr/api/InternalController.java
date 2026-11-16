package com.pmart.hr.api;

import com.pmart.hr.api.generated.InternalApi;
import com.pmart.hr.api.generated.model.EmployeeNameList;
import com.pmart.hr.api.generated.model.EmployeeNameListItemsInner;
import com.pmart.hr.application.EmployeeService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 InternalApi — 서비스 간 전용(0.5.0 이름 조회, 토큰 없는 내부 호출). 응답의 이름은 로그에 남기지 않는다. */
@RestController
public class InternalController implements InternalApi {

  private final EmployeeService employees;

  public InternalController(EmployeeService employees) {
    this.employees = employees;
  }

  @Override
  public ResponseEntity<EmployeeNameList> getEmployeeNamesInternal(List<String> operatorIds) {
    return ResponseEntity.ok(
        new EmployeeNameList()
            .items(
                employees.names(operatorIds).entrySet().stream()
                    .map(
                        e ->
                            new EmployeeNameListItemsInner()
                                .operatorId(e.getKey())
                                .name(e.getValue()))
                    .toList()));
  }
}
