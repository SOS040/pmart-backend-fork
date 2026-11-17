package com.pmart.customer.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.customer.domain.ConsentItem;
import com.pmart.customer.domain.ConsentRules;
import com.pmart.customer.domain.ConsentRules.Change;
import com.pmart.customer.domain.ConsentRules.Stored;
import com.pmart.customer.domain.ConsentRules.Violation;
import com.pmart.customer.domain.ProfileRules;
import com.pmart.customer.infrastructure.ConsentStore;
import com.pmart.customer.infrastructure.CustomerStore;
import com.pmart.customer.infrastructure.CustomerStore.ProfileRow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 약관 동의(CU-1, 사용자 결정 2026-11-17 "추천대로") — 필수 3(이용약관·개인정보 수집·이용·만 14세 이상) + 선택 1(마케팅 수신).
 *
 * <ul>
 *   <li>현행 버전은 설정 {@code pmart.customer.consents.versions.*}(없으면 CONFIG_REQUIRED). 버전이 바뀌면 필수 항목을
 *       다시 받는다 — 다음 로그인(auth AUTH_CONSENT)과 구매 가능 여부(내부 계정 상태)가 이 판단을 쓴다.
 *   <li>변경은 즉시 반영(APPLIED). 현행 버전으로만, 필수 항목은 끌 수 없다(철회 = 탈퇴, 범위 밖 → 422). 마케팅은 켜고 끈다.
 *   <li>같은 값·같은 버전이면 바꾸지 않는다(사건·기록 없음). 바뀐 항목마다 마지막 결정·바뀐 기록·CustomerConsentChanged.
 *   <li>탈퇴 신청 이후에는 동의(agreed=true)만 409, 철회는 받는다(마케팅 발송 중단을 막지 않게).
 *   <li>받은 곳(channel): APP(앱 /me/consent-changes), SIGNUP·LOGIN(로그인 중 auth 가 내부 경로로 넘김).
 * </ul>
 */
@Service
public class ConsentService {

  public static final String OPERATION_ME = "changeMyConsents";
  public static final String OPERATION_INTERNAL = "changeAccountConsentsInternal";
  public static final String CHANNEL_APP = "APP";

  private static final Logger log = LoggerFactory.getLogger(ConsentService.class);

  /** 항목 한 줄(계약 Consent). */
  public record View(
      ConsentItem item,
      boolean agreed,
      @Nullable String version,
      String latestVersion,
      @Nullable Instant agreedAt,
      @Nullable Instant withdrawnAt,
      @Nullable String channel) {}

  /** 계정의 동의 상태(계약 ConsentStatus·GET /me/consents). */
  public record Overview(String accountId, ConsentRules.Status status, List<View> items) {}

  /** 멱등 기록에 남기는 변경 결과(계약 CommandResponse). */
  public record Result(String requestId, String occurredAt, int changed) {}

  private final CustomerStore customers;
  private final ConsentStore consents;
  private final CustomerProperties props;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final ObjectMapper mapper;
  private final Clock clock;

  public ConsentService(
      CustomerStore customers,
      ConsentStore consents,
      CustomerProperties props,
      RequestIdempotency idempotency,
      EventPublisher events,
      EventEnvelopeCodec codec,
      ObjectMapper mapper,
      Clock clock) {
    this.customers = customers;
    this.consents = consents;
    this.props = props;
    this.idempotency = idempotency;
    this.events = events;
    this.codec = codec;
    this.mapper = mapper;
    this.clock = clock;
  }

  /** 현행 버전 기준 상태. 계정 행이 없어도(가입 중) 모두 고르지 않은 것으로 답한다. */
  public Overview overview(String accountId) {
    Map<ConsentItem, String> current = currentVersions();
    Map<ConsentItem, Stored> stored = consents.consents(accountId);
    List<View> items = new ArrayList<>();
    for (ConsentItem item : ConsentItem.values()) {
      Stored s = stored.get(item);
      items.add(
          s == null
              ? new View(item, false, null, current.get(item), null, null, null)
              : new View(
                  item,
                  s.agreed(),
                  s.termsVersion(),
                  current.get(item),
                  s.agreedAt(),
                  s.withdrawnAt(),
                  s.channel()));
    }
    return new Overview(accountId, ConsentRules.status(current, stored), List.copyOf(items));
  }

  /**
   * 구매 가능 여부에 쓰는 동의 판단(내부 계정 상태).
   *
   * @param requiredOutdated 필수 항목 중 현행 버전 동의가 없는 것이 있다(알림용 — 현행 버전 설정이 없으면 false)
   * @param blocksPurchase 그래서 구매를 막는가 — 스위치 {@code pmart.customer.consents.purchase-gate} 가 켜졌을 때만
   */
  public record PurchaseCheck(boolean requiredOutdated, boolean blocksPurchase) {}

  /**
   * 스위치가 꺼져 있으면(LAN 2026-11-17 — 고객 앱 동의 흐름 반영 전) 구매를 막지 않고 지금까지처럼 계정 상태만으로 판단하게 한다. 켜져 있으면 현행 버전
   * 설정이 꼭 있어야 한다(없으면 CONFIG_REQUIRED — 동의를 확인하지 못한 채 구매를 열지 않는다).
   */
  public PurchaseCheck purchaseCheck(String accountId) {
    if (props.consents().purchaseGateOn()) {
      boolean outdated =
          ConsentRules.status(currentVersions(), consents.consents(accountId)).requiredOutdated();
      return new PurchaseCheck(outdated, outdated);
    }
    boolean outdated;
    try {
      outdated =
          ConsentRules.status(currentVersions(), consents.consents(accountId)).requiredOutdated();
    } catch (PmartException e) {
      outdated = false; // 스위치가 꺼져 있으면 버전 설정이 없어도 계정 상태 조회를 막지 않는다
    }
    return new PurchaseCheck(outdated, false);
  }

  /**
   * 동의·철회 반영.
   *
   * @param operation 멱등 기록의 작업 이름(앱 {@link #OPERATION_ME}, auth 내부 {@link #OPERATION_INTERNAL})
   * @param channel APP·SIGNUP·LOGIN
   */
  public Result change(
      String accountId,
      String requestId,
      String sourceSystem,
      List<Change> changes,
      String channel,
      String operation) {
    if (changes.isEmpty()) {
      throw reject(new Violation(ConsentRules.INVALID_REQUEST, "changes", "바꿀 항목이 없습니다."));
    }
    ConsentRules.checkRequest(changes)
        .ifPresent(
            v -> {
              throw reject(v);
            });
    customers.ensureProfile(accountId, clock.instant());

    // 해시는 요청 값만 — 같은 키 재시도가 (그 사이 바뀐 현행 버전과 상관없이) 처음 결과를 받게. 버전·상태 검사는 멱등 처리 안에서
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("channel", channel);
    List<Map<String, Object>> list = new ArrayList<>();
    for (Change c : changes) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("consentType", c.item().name());
      m.put("version", c.version());
      m.put("agreed", c.agreed());
      list.add(m);
    }
    request.put("changes", list);
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, operation, accountId),
            request,
            Result.class,
            () -> apply(accountId, requestId, changes, channel))
        .value();
  }

  private Result apply(String accountId, String requestId, List<Change> changes, String channel) {
    Map<ConsentItem, String> current = currentVersions();
    ConsentRules.checkVersions(changes, current)
        .ifPresent(
            v -> {
              throw reject(v);
            });
    Instant now = clock.instant();
    ProfileRow locked =
        customers
            .lockProfile(accountId)
            .orElseThrow(() -> PmartException.notFound("ACCOUNT_NOT_FOUND", "고객 계정이 없습니다."));
    if (!ProfileRules.changeable(locked.status()) && changes.stream().anyMatch(Change::agreed)) {
      throw PmartException.invalidState("탈퇴 신청 이후에는 동의할 수 없습니다(철회만 가능).");
    }
    Map<ConsentItem, Stored> stored = new EnumMap<>(consents.consents(accountId));
    int changed = 0;
    for (Change c : changes) {
      Stored s = stored.get(c.item());
      if (ConsentRules.unchanged(s, c)) {
        continue;
      }
      ConsentRules.Times t = ConsentRules.times(s, c, now);
      int version =
          consents.save(
              accountId,
              c.item(),
              c.agreed(),
              c.version(),
              t.agreedAt(),
              t.withdrawnAt(),
              channel,
              now);
      consents.appendHistory(
          accountId, c.item(), c.agreed(), c.version(), channel, version, requestId, now);
      publish(accountId, c, version, channel, now);
      changed++;
    }
    log.info("약관 동의 반영 {} 바뀐 항목 {}건 ({})", accountId, changed, channel);
    return new Result(requestId, now.toString(), changed);
  }

  /** 본인의 앱 동의 변경 결과. 없거나 다른 계정 것이면 404. */
  public Result result(String accountId, String requestId) {
    return consents
        .changeResult(accountId, requestId, OPERATION_ME)
        .map(json -> mapper.readValue(json, Result.class))
        .orElseThrow(() -> PmartException.notFound("CONSENT_CHANGE_NOT_FOUND", "동의 변경 요청이 없습니다."));
  }

  private void publish(String accountId, Change c, int version, String channel, Instant at) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("accountId", accountId);
    payload.put("consentType", c.item().name());
    payload.put("version", version);
    payload.put("granted", c.agreed());
    payload.put("changedAt", at.toString());
    payload.put("termsVersion", c.version());
    payload.put("required", c.item().required());
    payload.put("channel", channel);
    EventEnvelope envelope =
        codec.create(
            events.sourceSystem(),
            "CustomerConsentChanged",
            1,
            "Customer",
            accountId,
            version,
            new EventEnvelope.Actor(EventEnvelope.ActorType.CUSTOMER, accountId),
            EventEnvelope.EventSource.REAL,
            payload);
    // 계약 토픽 명시(commerce.customer.v1)
    events.publish(envelope, AddressService.TOPIC);
  }

  /** 설정의 현행 버전. 하나라도 비면 CONFIG_REQUIRED(임의 버전으로 받지 않는다 — SCR-CONSENT-001). */
  Map<ConsentItem, String> currentVersions() {
    CustomerProperties.Versions v = props.consents().versions();
    Map<ConsentItem, String> out = new EnumMap<>(ConsentItem.class);
    out.put(
        ConsentItem.TERMS_OF_SERVICE,
        required(v == null ? null : v.termsOfService(), "terms-of-service"));
    out.put(
        ConsentItem.PRIVACY_POLICY,
        required(v == null ? null : v.privacyPolicy(), "privacy-policy"));
    out.put(ConsentItem.AGE_OVER_14, required(v == null ? null : v.ageOver14(), "age-over-14"));
    out.put(ConsentItem.MARKETING, required(v == null ? null : v.marketing(), "marketing"));
    return out;
  }

  private static String required(@Nullable String version, String key) {
    if (version == null
        || version.isBlank()
        || version.strip().length() > ConsentRules.VERSION_MAX) {
      throw PmartException.configRequired("pmart.customer.consents.versions." + key);
    }
    return version.strip();
  }

  private static PmartException reject(Violation v) {
    if (ConsentRules.INVALID_REQUEST.equals(v.code())) {
      return new PmartException(
          ErrorCode.VALIDATION_FAILED,
          v.message(),
          List.of(new PmartException.Detail(v.field(), v.message())));
    }
    return new PmartException(
        v.code(),
        HttpStatus.UNPROCESSABLE_CONTENT,
        v.message(),
        false,
        List.of(new PmartException.Detail(v.field(), v.message())));
  }
}
