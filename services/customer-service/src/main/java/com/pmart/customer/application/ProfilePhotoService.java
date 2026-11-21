package com.pmart.customer.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.customer.domain.ProfileImageRules;
import com.pmart.customer.domain.ProfileRules;
import com.pmart.customer.infrastructure.CustomerStore;
import com.pmart.customer.infrastructure.CustomerStore.ProfileRow;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 고객이 고르는 프로필 사진(2026-11-21 BR-80, 계약 customer 0.15.0).
 *
 * <ul>
 *   <li>{@link #upload}: 직접 올린 사진(CUSTOM) — JPEG·PNG·WebP(파일 앞부분), {@link #MAX_BYTES} 이하. 키는 내용
 *       지문이라 같은 사진은 같은 키.
 *   <li>{@link #clear}: 기본 이미지(NONE) — 지금 사진을 지운다.
 *   <li>{@link #restoreProvider}: 로그인 계정 사진(PROVIDER) — 지금 사진을 지우고 커밋 뒤 제공자 사진을 다시 받는다.
 *   <li>CUSTOM·NONE 이면 로그인해도 제공자 사진으로 덮지 않는다({@link ProfileImageService#copy} 가 건너뜀).
 *   <li>탈퇴 신청 이후는 409 INVALID_STATE. 같은 Idempotency-Key 재전송은 처음 결과. 사진·주소는 로그에 남기지 않는다.
 * </ul>
 */
@Service
public class ProfilePhotoService {

  private static final Logger log = LoggerFactory.getLogger(ProfilePhotoService.class);

  /** 올리는 사진 상한(계약 0.15.0). 앱은 512px 정사각형 JPEG 정도로 보낸다. */
  public static final int MAX_BYTES = 2 * 1024 * 1024;

  static final Set<String> UPLOAD_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

  /** 멱등 기록에 남기는 결과(계약 CommandResponse). */
  public record Result(String requestId, int version, String occurredAt) {}

  private final CustomerStore store;
  private final ObjectProvider<ProfileImageStorage> storage;
  private final ProfileImageService images;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public ProfilePhotoService(
      CustomerStore store,
      ObjectProvider<ProfileImageStorage> storage,
      ProfileImageService images,
      RequestIdempotency idempotency,
      Clock clock) {
    this.store = store;
    this.storage = storage;
    this.images = images;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public Result upload(String accountId, String requestId, String sourceSystem, byte[] body) {
    if (body.length == 0 || body.length > MAX_BYTES) {
      throw invalid("사진은 2MB 이하여야 합니다.");
    }
    String type =
        ProfileImageRules.imageType(body)
            .filter(UPLOAD_TYPES::contains)
            .orElseThrow(() -> invalid("JPEG·PNG·WebP 사진만 올릴 수 있습니다."));
    String sha = ProfileImageService.sha256(body);
    ProfileImageStorage s = requireStorage();
    store.ensureProfile(accountId, clock.instant());
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("photo", sha);
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "uploadMyProfileImage", accountId),
            request,
            Result.class,
            () -> {
              ProfileRow p = changeable(accountId);
              String key = "profile-images/" + accountId + "/" + sha;
              s.put(key, body, type);
              Instant now = clock.instant();
              Optional<String> old =
                  store
                      .setProfilePhoto(accountId, CustomerStore.PHOTO_CUSTOM, key, type, sha, now)
                      .orElseThrow(this::noAccount);
              old.filter(k -> !k.equals(key))
                  .ifPresent(k -> ProfileImageService.deleteQuietly(s, k, accountId));
              log.info("프로필 사진 직접 올림 {}", accountId);
              return new Result(requestId, p.version(), now.toString());
            })
        .value();
  }

  public Result clear(String accountId, String requestId, String sourceSystem) {
    return reset(
        accountId, requestId, sourceSystem, CustomerStore.PHOTO_NONE, "clearMyProfileImage");
  }

  public Result restoreProvider(String accountId, String requestId, String sourceSystem) {
    return reset(
        accountId,
        requestId,
        sourceSystem,
        CustomerStore.PHOTO_PROVIDER,
        "restoreMyProviderProfileImage");
  }

  private Result reset(
      String accountId, String requestId, String sourceSystem, String source, String operation) {
    store.ensureProfile(accountId, clock.instant());
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, operation, accountId),
            Map.of("photoSource", source),
            Result.class,
            () -> {
              ProfileRow p = changeable(accountId);
              Instant now = clock.instant();
              Optional<String> old =
                  store
                      .setProfilePhoto(accountId, source, null, null, null, now)
                      .orElseThrow(this::noAccount);
              ProfileImageStorage s = storage.getIfAvailable();
              if (s != null) {
                old.ifPresent(k -> ProfileImageService.deleteQuietly(s, k, accountId));
              }
              if (CustomerStore.PHOTO_PROVIDER.equals(source)) {
                images.copyAfterCommit(accountId);
              }
              log.info("프로필 사진 출처 {} {}", source, accountId);
              return new Result(requestId, p.version(), now.toString());
            })
        .value();
  }

  private ProfileRow changeable(String accountId) {
    ProfileRow p = store.lockProfile(accountId).orElseThrow(this::noAccount);
    if (!ProfileRules.changeable(p.status())) {
      throw PmartException.invalidState("탈퇴 신청 이후에는 프로필 사진을 바꿀 수 없습니다.");
    }
    return p;
  }

  private ProfileImageStorage requireStorage() {
    ProfileImageStorage s = storage.getIfAvailable();
    if (s == null) {
      throw PmartException.dependencyUnavailable(
          "프로필 사진 저장소를 쓸 수 없습니다.", new IllegalStateException("storage not configured"));
    }
    return s;
  }

  private PmartException noAccount() {
    return PmartException.notFound("ACCOUNT_NOT_FOUND", "고객 계정이 없습니다.");
  }

  private static PmartException invalid(String reason) {
    return new PmartException(
        ErrorCode.VALIDATION_FAILED, reason, List.of(new PmartException.Detail("file", reason)));
  }
}
