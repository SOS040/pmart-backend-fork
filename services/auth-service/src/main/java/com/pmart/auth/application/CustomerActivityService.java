package com.pmart.auth.application;

import com.pmart.auth.infrastructure.CustomerStore;
import com.pmart.auth.infrastructure.CustomerStore.Activity;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객 최근 활동(2026-11-26 CU-6 — 2026-11-23 사용자 "추천대로": lastActiveAt = max(마지막 로그인·토큰 갱신, 마지막 주문)).
 * auth 는 로그인· 갱신 두 값만 준다 — 마지막 주문은 order, 셋을 합치는 것은 backoffice-bff. 읽기만.
 */
@Service
public class CustomerActivityService {

  public static final int MAX_ACCOUNTS = 200;

  private final CustomerStore store;

  public CustomerActivityService(CustomerStore store) {
    this.store = store;
  }

  /** 요청 순서·중복 한 번, auth 에 없는 계정은 두 값 null. */
  @Transactional(readOnly = true)
  public List<Activity> activities(@Nullable String accountIds) {
    List<String> ids = ids(accountIds);
    if (ids == null) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "accountIds 는 1~" + MAX_ACCOUNTS + "개여야 합니다.");
    }
    Map<String, Activity> by = new HashMap<>();
    store.activities(ids).forEach(a -> by.put(a.accountId(), a));
    return ids.stream().map(id -> by.getOrDefault(id, new Activity(id, null, null))).toList();
  }

  /** 쉼표 구분 → 공백 빼고 빈 것 빼고 중복 한 번. 1~200 개가 아니면 null. */
  static @Nullable List<String> ids(@Nullable String raw) {
    if (raw == null) {
      return null;
    }
    LinkedHashSet<String> out = new LinkedHashSet<>();
    for (String x : raw.split(",")) {
      String v = x.strip();
      if (!v.isEmpty()) {
        out.add(v);
      }
    }
    return out.isEmpty() || out.size() > MAX_ACCOUNTS ? null : new ArrayList<>(out);
  }
}
