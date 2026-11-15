package com.pmart.audit.application;

import com.pmart.audit.domain.PiiAccess;
import com.pmart.audit.infrastructure.PiiAccessLogStore;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 개인정보 열람·다운로드 기록(BO-25, 2026-11-15). 호출자(예 order-service 직원 주문 상세 원문 보기)가 원문을 주기 전에 동기로 부른다.
 *
 * <p>멱등: (X-Source-System, Idempotency-Key). 같은 키·같은 본문(occurredAt·traceId 제외)이면 처음 기록을 재생, 다르면
 * IDEMPOTENCY_CONFLICT. 사유(purpose)는 로그에 남기지 않는다.
 */
@Service
public class PiiAccessLogService {

  /** 기록 결과(재생이면 replayed). */
  public record Recorded(String piiAccessLogId, Instant recordedAt, boolean replayed) {}

  /** 재생용으로 저장되는 결과(JSON 왕복). */
  public record Result(String piiAccessLogId, Instant recordedAt) {}

  private final PiiAccessLogStore store;
  private final RequestIdempotency idempotency;

  public PiiAccessLogService(PiiAccessLogStore store, RequestIdempotency idempotency) {
    this.store = store;
    this.idempotency = idempotency;
  }

  public Recorded record(String sourceSystem, String requestId, PiiAccess access) {
    String problem = access.problem();
    if (problem != null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, problem);
    }
    var outcome =
        idempotency.execute(
            new RequestKey(sourceSystem, requestId, "recordPiiAccessLog", actor()),
            access.forIdempotency(),
            Result.class,
            () -> {
              String id = "PAL-" + UUID.randomUUID();
              Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
              store.insert(id, sourceSystem, requestId, now, access);
              return new Result(id, now);
            });
    return new Recorded(
        outcome.value().piiAccessLogId(), outcome.value().recordedAt(), outcome.replayed());
  }

  @Transactional(readOnly = true)
  public PiiAccessLogStore.Stored get(String piiAccessLogId) {
    return store
        .find(piiAccessLogId)
        .orElseThrow(
            () -> PmartException.notFound("PII_ACCESS_LOG_NOT_FOUND", "개인정보 접근 기록이 없습니다."));
  }

  /** 직원 조회 한 쪽(0.3.0). */
  public record StaffPage(List<PiiAccessLogStore.Stored> items, @Nullable String nextCursor) {}

  /**
   * 개인정보 열람 기록 목록 — OPERATOR_ADMIN 만(ADR-0062 결정 10, 2026-11-16). 커서 = "열람 시각 epoch 마이크로초_추가 순서"(새
   * 것부터 keyset — 그 사이 새 기록이 들어와도 쪽이 밀리지 않는다).
   */
  @Transactional(readOnly = true)
  public StaffPage list(PiiAccessLogStore.Search search, @Nullable String cursor, int size) {
    requireAdmin();
    if (search.from() != null && search.to() != null && !search.from().isBefore(search.to())) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "from 은 to 보다 앞이어야 합니다.");
    }
    Instant afterAt = null;
    long afterSeq = 0;
    if (cursor != null && !cursor.isBlank()) {
      try {
        String[] p = cursor.split("_", 2);
        afterAt = Instant.EPOCH.plus(Long.parseLong(p[0]), ChronoUnit.MICROS);
        afterSeq = Long.parseLong(p[1]);
      } catch (RuntimeException e) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "cursor 가 올바르지 않습니다.");
      }
    }
    int n = Math.min(Math.max(size, 1), 200);
    List<PiiAccessLogStore.Stored> rows = store.search(search, afterAt, afterSeq, n + 1);
    boolean more = rows.size() > n;
    List<PiiAccessLogStore.Stored> items = more ? rows.subList(0, n) : rows;
    String next = null;
    if (more) {
      PiiAccessLogStore.Stored last = items.get(items.size() - 1);
      next =
          ChronoUnit.MICROS.between(Instant.EPOCH, last.access().occurredAt())
              + "_"
              + last.sequence();
    }
    return new StaffPage(items, next);
  }

  /** 한 건 — OPERATOR_ADMIN 만. */
  @Transactional(readOnly = true)
  public PiiAccessLogStore.Stored getForStaff(String piiAccessLogId) {
    requireAdmin();
    return get(piiAccessLogId);
  }

  private static void requireAdmin() {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자만 볼 수 있습니다."));
    if (!p.hasRole("OPERATOR_ADMIN")) {
      throw new PmartException(
          ErrorCode.FORBIDDEN, "개인정보 열람 기록은 OPERATOR_ADMIN 만 볼 수 있습니다(ADR-0062).");
    }
  }

  private static String actor() {
    return PmartPrincipal.current()
        .map(PmartPrincipal::subject)
        .orElse(RequestIdempotency.ANONYMOUS);
  }
}
