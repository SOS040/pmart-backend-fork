package com.pmart.customer.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.customer.domain.ConsentItem;
import com.pmart.customer.domain.ConsentRules;
import com.pmart.customer.domain.ConsentRules.Change;
import com.pmart.customer.domain.ConsentRules.Stored;
import com.pmart.customer.domain.ConsentRules.Violation;
import com.pmart.customer.infrastructure.DeviceConsentStore;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * 로그인 전(기기) 동의(2026-11-19, 클라이언트 요청 BR-59 처음 실행 약관 — 사용자 "응 그렇게 해줘"). 토큰 없이 X-Device-Id 로(비회원 배송지와
 * 같은 방식). 항목은 위치기반서비스 약관·마케팅 수신 둘({@link ConsentItem#device()}). 현행 버전으로만 받고 즉시 반영, 사건은 내지 않는다 —
 * 로그인하면 auth 가 계정으로 옮긴다({@link ConsentService#migrateDevice}, 그때 CustomerConsentChanged). 기기 ID 는
 * 개인정보가 아니지만 로그에 남기지 않는다(기기 추적 방지).
 */
@Service
public class DeviceConsentService {

  public static final String OPERATION = "changeDeviceConsents";

  /** 기기 동의 상태(계약 DeviceConsentList). */
  public record DeviceView(
      String deviceId, @Nullable String senderName, List<ConsentService.View> items) {}

  /** 멱등 기록에 남기는 결과(재시도는 지금 상태를 다시 읽어 답한다). */
  public record Result(String requestId, String occurredAt, int changed) {}

  private final DeviceConsentStore devices;
  private final ConsentService consents;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public DeviceConsentService(
      DeviceConsentStore devices,
      ConsentService consents,
      RequestIdempotency idempotency,
      Clock clock) {
    this.devices = devices;
    this.consents = consents;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public DeviceView overview(String deviceId) {
    String device = device(deviceId);
    Map<ConsentItem, String> current = consents.currentVersions();
    return new DeviceView(
        device,
        consents.senderName(),
        ConsentService.views(deviceItems(), current, devices.consents(device)));
  }

  /** 기기 동의·거부 반영 → 반영 뒤 상태. 같은 키·같은 본문 재전송은 다시 반영하지 않는다. */
  public DeviceView change(
      String deviceId, String requestId, String sourceSystem, List<Change> changes) {
    String device = device(deviceId);
    if (changes.isEmpty()) {
      throw ConsentService.reject(
          new Violation(ConsentRules.INVALID_REQUEST, "changes", "바꿀 항목이 없습니다."));
    }
    ConsentRules.checkDevice(changes)
        .ifPresent(
            v -> {
              throw ConsentService.reject(v);
            });
    ConsentRules.checkRequest(changes)
        .ifPresent(
            v -> {
              throw ConsentService.reject(v);
            });
    Map<String, Object> request = new LinkedHashMap<>();
    List<Map<String, Object>> list = new ArrayList<>();
    for (Change c : changes) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("consentType", c.item().name());
      m.put("version", c.version());
      m.put("agreed", c.agreed());
      list.add(m);
    }
    request.put("changes", list);
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, OPERATION, "device:" + device),
        request,
        Result.class,
        () -> apply(device, requestId, changes));
    return overview(device);
  }

  private Result apply(String device, String requestId, List<Change> changes) {
    Map<ConsentItem, String> current = consents.currentVersions();
    ConsentRules.checkVersions(changes, current)
        .ifPresent(
            v -> {
              throw ConsentService.reject(v);
            });
    Instant now = clock.instant();
    devices.lock(device);
    Map<ConsentItem, Stored> stored = devices.consents(device);
    int changed = 0;
    for (Change c : changes) {
      Stored s = stored.get(c.item());
      if (ConsentRules.unchanged(s, c)) {
        continue;
      }
      ConsentRules.Times t = ConsentRules.times(s, c, now);
      devices.save(device, c.item(), c.agreed(), c.version(), t.agreedAt(), t.withdrawnAt(), now);
      changed++;
    }
    return new Result(requestId, now.toString(), changed);
  }

  private static List<ConsentItem> deviceItems() {
    return Arrays.stream(ConsentItem.values()).filter(ConsentItem::device).toList();
  }

  private static String device(@Nullable String deviceId) {
    String d = deviceId == null ? "" : deviceId.strip();
    if (d.isEmpty() || d.length() > 128) {
      throw ConsentService.reject(
          new Violation(ConsentRules.INVALID_REQUEST, "X-Device-Id", "기기 ID 가 없거나 너무 깁니다."));
    }
    return d;
  }
}
