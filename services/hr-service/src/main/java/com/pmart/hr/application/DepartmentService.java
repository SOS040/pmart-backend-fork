package com.pmart.hr.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.hr.infrastructure.HrStore;
import com.pmart.hr.infrastructure.HrStore.DepartmentRow;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * 본사 부서 코드 목록(2026-11-15). 소속 코드는 아무 값이나 받고(등록·소속 변경이 처음 보는 코드를 더한다), 여기서는 목록 조회와 표시 이름만. 모두
 * HR_ADMIN.
 */
@Service
public class DepartmentService {

  private final HrStore store;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public DepartmentService(HrStore store, RequestIdempotency idempotency, Clock clock) {
    this.store = store;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public List<DepartmentRow> list() {
    EmployeeService.requireHrAdmin();
    return store.departments();
  }

  /** 표시 이름을 정한다(없으면 만든다). 빈 이름은 이름 없음. */
  public DepartmentRow rename(
      String idempotencyKey, String sourceSystem, String code, @Nullable String name) {
    PmartPrincipal hr = EmployeeService.requireHrAdmin();
    String clean = name == null || name.isBlank() ? null : name.strip();
    idempotency.execute(
        new RequestKey(sourceSystem, idempotencyKey, "putDepartment:" + code, hr.subject()),
        Arrays.asList(code, clean),
        String.class,
        () -> {
          store.upsertDepartment(
              code, clean, hr.subject(), clock.instant().truncatedTo(ChronoUnit.MICROS));
          return code;
        });
    return store.department(code).orElseThrow();
  }
}
