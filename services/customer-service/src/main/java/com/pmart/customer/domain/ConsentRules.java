package com.pmart.customer.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 약관 동의 규칙(CU-1, 2026-11-17). 현행 버전은 설정에서 오고, 고객이 고른 값(마지막 결정)과 비교한다.
 *
 * <ul>
 *   <li>필수 항목은 현행 버전에 동의(agreed=true)해야 충족이다. 버전이 바뀌면 다시 받는다.
 *   <li>선택 항목(마케팅)은 현행 버전으로 한 번 고르면(동의든 거부든) 다시 묻지 않는다. 거부는 구매 제한이 아니다.
 *   <li>변경은 현행 버전으로만. 필수 항목을 agreed=false 로 바꿀 수 없다(필수 동의 철회 = 탈퇴).
 * </ul>
 */
public final class ConsentRules {

  /** 계약 version maxLength. */
  public static final int VERSION_MAX = 32;

  private ConsentRules() {}

  /** 저장된 마지막 결정. */
  public record Stored(
      ConsentItem item,
      boolean agreed,
      String termsVersion,
      @Nullable Instant agreedAt,
      @Nullable Instant withdrawnAt,
      String channel,
      int version,
      Instant changedAt) {}

  /** 요청한 변경 한 건. */
  public record Change(ConsentItem item, String version, boolean agreed) {}

  /**
   * 현행 버전 기준 상태.
   *
   * @param missingRequired 현행 버전 동의가 없는 필수 항목 {@code TYPE:version}
   * @param undecidedOptional 현행 버전으로 아직 고르지 않은 선택 항목 {@code TYPE:version}
   */
  public record Status(List<String> missingRequired, List<String> undecidedOptional) {
    public boolean requiredOutdated() {
      return !missingRequired.isEmpty();
    }
  }

  /** 변경 거절 사유(계약 422·400 코드). */
  public record Violation(String code, String field, String message) {}

  public static final String VERSION_OUTDATED = "CONSENT_VERSION_OUTDATED";
  public static final String REQUIRED_CANNOT_BE_WITHDRAWN = "REQUIRED_CONSENT_CANNOT_BE_WITHDRAWN";
  public static final String INVALID_REQUEST = "VALIDATION_FAILED";

  /** 항목 문자열 {@code TYPE:version} — auth AuthResult·내부 상태 응답의 형식. */
  public static String ref(ConsentItem item, String version) {
    return item.name() + ":" + version;
  }

  public static Status status(Map<ConsentItem, String> current, Map<ConsentItem, Stored> stored) {
    List<String> missing = new ArrayList<>();
    List<String> undecided = new ArrayList<>();
    for (ConsentItem item : ConsentItem.values()) {
      String latest = current.get(item);
      if (latest == null) {
        throw new IllegalArgumentException("현행 버전 없음: " + item);
      }
      Stored s = stored.get(item);
      boolean decided = s != null && s.termsVersion().equals(latest);
      if (item.required()) {
        if (!decided || !s.agreed()) {
          missing.add(ref(item, latest));
        }
      } else if (item.requiredFor() != null) {
        continue; // 기능 필수(위치기반서비스 — 2026-11-19)는 로그인 동의 단계에서 묻지 않는다
      } else if (!decided) {
        undecided.add(ref(item, latest));
      }
    }
    return new Status(List.copyOf(missing), List.copyOf(undecided));
  }

  /**
   * 요청만 보고 하는 검사: 같은 항목 두 번(400), 필수 항목 agreed=false(422). 서버 상태와 무관해 멱등 처리 밖에서 먼저 본다. 문제가 없으면 빈 값.
   */
  public static Optional<Violation> checkRequest(List<Change> changes) {
    Set<ConsentItem> seen = EnumSet.noneOf(ConsentItem.class);
    for (Change c : changes) {
      if (!seen.add(c.item())) {
        return Optional.of(new Violation(INVALID_REQUEST, "changes", c.item() + " 항목을 두 번 보냈습니다."));
      }
    }
    for (Change c : changes) {
      if (c.item().required() && !c.agreed()) {
        return Optional.of(
            new Violation(
                REQUIRED_CANNOT_BE_WITHDRAWN,
                c.item().name(),
                c.item() + " 은 필수 항목이라 동의하지 않을 수 없습니다(철회는 탈퇴로)."));
      }
    }
    return Optional.empty();
  }

  /**
   * 현행 버전 검사(422 CONSENT_VERSION_OUTDATED). 설정(현행 버전)에 따라 달라지므로 멱등 처리 안에서 본다 — 이미 반영된 요청의 재시도가 그 사이
   * 바뀐 버전 때문에 거절되지 않게.
   */
  public static Optional<Violation> checkVersions(
      List<Change> changes, Map<ConsentItem, String> current) {
    for (Change c : changes) {
      String latest = current.get(c.item());
      if (!c.version().equals(latest)) {
        return Optional.of(
            new Violation(
                VERSION_OUTDATED,
                c.item().name(),
                c.item() + " 약관의 현행 버전은 " + latest + " 입니다(보낸 버전 " + c.version() + "). 다시 조회하세요."));
      }
    }
    return Optional.empty();
  }

  /**
   * 로그인 동의 판단에 쓸 결정(2026-11-19 BR-59): 계정에 현행 버전 결정이 없는 기기 항목은 그 기기의 현행 버전 결정을 결정한 것으로 본다(옮기기 전에도
   * 다시 묻지 않게). 계정 값이 있으면 계정 값. 쓰지 않는다 — 옮기기는 {@link #toMigrate}.
   */
  public static Map<ConsentItem, Stored> withDevice(
      Map<ConsentItem, String> current,
      Map<ConsentItem, Stored> account,
      Map<ConsentItem, Stored> device) {
    Map<ConsentItem, Stored> out = new java.util.EnumMap<>(ConsentItem.class);
    out.putAll(account);
    for (Map.Entry<ConsentItem, Stored> d : device.entrySet()) {
      ConsentItem item = d.getKey();
      String latest = current.get(item);
      Stored a = account.get(item);
      if (!item.device() || latest == null || !d.getValue().termsVersion().equals(latest)) {
        continue;
      }
      if (a == null || !a.termsVersion().equals(latest)) {
        out.put(item, d.getValue());
      }
    }
    return out;
  }

  /** 계정으로 옮길 기기 결정 — 기기 항목이고, 현행 버전이며, 계정에 현행 버전 결정이 없는 것(2026-11-19 BR-59 — 계정 값이 이긴다). */
  public static List<Change> toMigrate(
      Map<ConsentItem, String> current,
      Map<ConsentItem, Stored> account,
      Map<ConsentItem, Stored> device) {
    List<Change> out = new ArrayList<>();
    for (ConsentItem item : ConsentItem.values()) {
      Stored d = device.get(item);
      String latest = current.get(item);
      if (d == null || !item.device() || latest == null || !d.termsVersion().equals(latest)) {
        continue;
      }
      Stored a = account.get(item);
      if (a == null || !a.termsVersion().equals(latest)) {
        out.add(new Change(item, latest, d.agreed()));
      }
    }
    return List.copyOf(out);
  }

  /** 기기 동의로 받는 항목만(2026-11-19 BR-59). 아니면 400 사유. */
  public static Optional<Violation> checkDevice(List<Change> changes) {
    for (Change c : changes) {
      if (!c.item().device()) {
        return Optional.of(
            new Violation(
                INVALID_REQUEST, c.item().name(), c.item() + " 은 로그인 전에 받지 않습니다(필수 약관은 가입 때)."));
      }
    }
    return Optional.empty();
  }

  /** 저장된 결정과 같은 값·같은 버전이면 바꿀 것이 없다. */
  public static boolean unchanged(@Nullable Stored stored, Change change) {
    return stored != null
        && stored.agreed() == change.agreed()
        && stored.termsVersion().equals(change.version());
  }

  /**
   * 바뀐 뒤 동의·철회 시각. 동의 → agreedAt=지금, withdrawnAt 없음. 동의 중이던 것을 끄면 withdrawnAt=지금(agreedAt 은 마지막 동의
   * 시각 그대로). 동의한 적 없이 거부하면 둘 다 그대로(거부는 철회가 아님).
   */
  public static Times times(@Nullable Stored stored, Change change, Instant now) {
    if (change.agreed()) {
      return new Times(now, null);
    }
    if (stored == null) {
      return new Times(null, null);
    }
    return new Times(stored.agreedAt(), stored.agreed() ? now : stored.withdrawnAt());
  }

  /** 바뀐 뒤 마지막 동의 시각·철회 시각. */
  public record Times(@Nullable Instant agreedAt, @Nullable Instant withdrawnAt) {}
}
