package com.pmart.customer.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.customer.domain.ProfileRules;
import com.pmart.customer.infrastructure.CustomerStore;
import com.pmart.customer.infrastructure.CustomerStore.ProfileRow;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 프로필 변경(계약 POST /me/profile-changes) — 지금은 닉네임만(2026-11-03 사용자 결정). 연락처(phone)는 본인 확인 방식을 정한 뒤에
 * 한다: 그 전까지 phone 이 오면 400.
 *
 * <ul>
 *   <li>즉시 반영(APPLIED), 버전 +1. 같은 닉네임이면 바꾸지 않고 현재 버전으로 APPLIED.
 *   <li>탈퇴 신청 이후(WITHDRAW_PENDING·COMPLETED)는 409 INVALID_STATE. X-Expected-Version 이 다르면 409
 *       VERSION_CONFLICT.
 *   <li>확정된 주문의 배송 연락처는 주문 사본이라 여기서 바뀌지 않는다(BR-37).
 *   <li>결과는 멱등 기록(request_record)에 남아 GET /me/profile-changes/{requestId} 로 다시 본다.
 * </ul>
 *
 * 닉네임은 개인정보로 보고 로그에 값을 남기지 않는다.
 */
@Service
public class ProfileChangeService {

  public static final String OPERATION = "changeMyProfile";

  private static final Logger log = LoggerFactory.getLogger(ProfileChangeService.class);

  /** 멱등 기록에 남기는 결과(계약 CommandResponse 로 바꿔 돌려준다). */
  public record Result(String requestId, int version, String occurredAt, boolean changed) {}

  private final CustomerStore store;
  private final RequestIdempotency idempotency;
  private final ObjectMapper mapper;
  private final Clock clock;

  public ProfileChangeService(
      CustomerStore store, RequestIdempotency idempotency, ObjectMapper mapper, Clock clock) {
    this.store = store;
    this.idempotency = idempotency;
    this.mapper = mapper;
    this.clock = clock;
  }

  public Result change(
      String accountId,
      String requestId,
      String sourceSystem,
      @Nullable Integer expectedVersion,
      @Nullable String nickname,
      @Nullable String phone) {
    if (phone != null) {
      throw invalid("phone", "연락처 변경은 아직 지원하지 않습니다.");
    }
    if (nickname == null) {
      throw invalid("nickname", "바꿀 값이 없습니다.");
    }
    ProfileRules.nicknameProblem(nickname)
        .ifPresent(
            reason -> {
              throw invalid("nickname", reason);
            });
    String nick = Objects.requireNonNull(ProfileRules.normalizeNickname(nickname));

    store.ensureProfile(accountId, clock.instant());

    // 해시는 클라이언트가 보낸 값만 — 같은 키 재시도가 (그 사이 바뀐 서버 버전과 상관없이) 처음 결과를 다시 받게.
    // 상태·버전 검사는 멱등 처리 안에서 한다: 밖에서 먼저 보면 이미 반영된 요청의 재시도가 409 가 된다
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("nickname", nick);
    request.put("expectedVersion", expectedVersion);
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, OPERATION, accountId),
            request,
            Result.class,
            () -> {
              Instant now = clock.instant();
              ProfileRow locked = store.lockProfile(accountId).orElseThrow(this::noAccount);
              check(locked, expectedVersion);
              if (nick.equals(locked.nickname())) {
                return new Result(requestId, locked.version(), now.toString(), false);
              }
              if (store.updateNickname(accountId, locked.version(), nick, now) == 0) {
                throw versionConflict();
              }
              log.info("닉네임 변경 {} 버전 {}", accountId, locked.version() + 1);
              return new Result(requestId, locked.version() + 1, now.toString(), true);
            })
        .value();
  }

  /** 본인의 변경 결과. 없거나 다른 계정 것이면 404. */
  public Result result(String accountId, String requestId) {
    return store
        .profileChangeResult(accountId, requestId)
        .map(json -> mapper.readValue(json, Result.class))
        .orElseThrow(() -> PmartException.notFound("PROFILE_CHANGE_NOT_FOUND", "프로필 변경 요청이 없습니다."));
  }

  private PmartException noAccount() {
    return PmartException.notFound("ACCOUNT_NOT_FOUND", "고객 계정이 없습니다.");
  }

  private static void check(ProfileRow p, @Nullable Integer expectedVersion) {
    if (!ProfileRules.changeable(p.status())) {
      throw PmartException.invalidState("탈퇴 신청 이후에는 프로필을 바꿀 수 없습니다.");
    }
    if (expectedVersion != null && expectedVersion != p.version()) {
      throw versionConflict();
    }
  }

  private static PmartException versionConflict() {
    return PmartException.versionConflict("프로필이 그 사이 바뀌었습니다. 다시 조회하세요.");
  }

  private static PmartException invalid(String field, String reason) {
    return new PmartException(
        ErrorCode.VALIDATION_FAILED, reason, List.of(new PmartException.Detail(field, reason)));
  }
}
