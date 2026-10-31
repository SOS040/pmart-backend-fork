package com.pmart.customer.application;

import com.pmart.common.web.PmartException;
import com.pmart.customer.domain.ProfileImageRules;
import com.pmart.customer.infrastructure.CustomerStore;
import com.pmart.customer.infrastructure.CustomerStore.ProfileImageRow;
import com.pmart.customer.infrastructure.ProfileImageDownloader;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 제공자 프로필 사진의 우리 사본(ADR-0024). 제공자 URL 을 앱에 그대로 주지 않는다 — 바뀌거나 사라질 수 있고, 볼 때마다 제공자에 요청이 가기 때문이다.
 *
 * <ul>
 *   <li>로그인 사건 반영이 커밋된 뒤, 원본 URL 이 지난번 사본의 원본과 다를 때만 트랜잭션 밖에서 받는다.
 *   <li>사본 키는 내용 지문 기반({@code profile-images/<accountId>/<sha256>}) — 같은 키의 본문은 바뀌지 않는다.
 *   <li>받기 실패(네트워크·제공자 오류)는 기존 사본을 그대로 두고 다음 로그인 때 다시 받는다. 허용 밖 주소·이미지 아님은 다시 받지 않는다.
 *   <li>저장소 버킷이 설정되지 않았으면 사본 기능만 꺼진다(로그인·프로필 반영은 막지 않음).
 * </ul>
 */
@Service
public class ProfileImageService {

  private static final Logger log = LoggerFactory.getLogger(ProfileImageService.class);

  /** 앱이 받는 경로(게이트웨이 상대 경로, 본인 토큰 필요). 사본이 바뀌면 v 가 바뀌어 앱 캐시가 갱신된다. */
  public static final String PATH = "/api/v1/me/profile-image";

  private final CustomerStore store;
  private final ObjectProvider<ProfileImageStorage> storage;
  private final ProfileImageDownloader downloader;
  private final ProfileImageProperties props;
  private final Clock clock;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public ProfileImageService(
      CustomerStore store,
      ObjectProvider<ProfileImageStorage> storage,
      ProfileImageDownloader downloader,
      ProfileImageProperties props,
      Clock clock) {
    this.store = store;
    this.storage = storage;
    this.downloader = downloader;
    this.props = props;
    this.clock = clock;
  }

  @PreDestroy
  void close() {
    executor.shutdownNow();
  }

  /** 프로필 응답의 사진 주소. 사본이 없으면 null. */
  public static @Nullable String url(@Nullable String sha256) {
    return sha256 == null ? null : PATH + "?v=" + sha256.substring(0, 12);
  }

  /** 로그인 사건 반영 뒤 호출 — 커밋되면 사본을 만든다(트랜잭션이 없으면 바로 비동기로). */
  public void copyAfterCommit(String accountId) {
    if (storage.getIfAvailable() == null) {
      return;
    }
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              executor.execute(() -> copy(accountId));
            }
          });
    } else {
      executor.execute(() -> copy(accountId));
    }
  }

  /** 원본을 받아 사본으로 둔다. 실패해도 예외를 던지지 않는다(다음 로그인 때 다시). */
  void copy(String accountId) {
    ProfileImageStorage s = storage.getIfAvailable();
    ProfileImageRow row = store.profileImage(accountId).orElse(null);
    if (s == null || row == null || row.sourceUrl() == null) {
      return;
    }
    String source = row.sourceUrl();
    if (source.equals(row.copiedFrom())) {
      return;
    }
    Optional<URI> uri =
        ProfileImageRules.fetchUri(
            source, props.allowedHostSuffixes(), props.allowHttp(), props.sizePx());
    if (uri.isEmpty()) {
      log.warn("프로필 사진 원본이 허용 범위 밖이라 받지 않음 {}", accountId);
      store.markProfileImageSkipped(accountId, source, clock.instant());
      return;
    }
    byte[] body;
    try {
      body = downloader.download(uri.get()).orElse(null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    } catch (Exception e) {
      log.warn("프로필 사진을 받지 못함 {} — 다음 로그인 때 다시: {}", accountId, e.toString());
      return;
    }
    if (body == null) {
      log.warn("프로필 사진 응답이 없거나 상한 초과 {} — 다음 로그인 때 다시", accountId);
      return;
    }
    String type = ProfileImageRules.imageType(body).orElse(null);
    if (type == null) {
      log.warn("프로필 사진 원본이 이미지가 아님 {}", accountId);
      store.markProfileImageSkipped(accountId, source, clock.instant());
      return;
    }
    String sha = sha256(body);
    String key = "profile-images/" + accountId + "/" + sha;
    try {
      s.put(key, body, type);
    } catch (PmartException e) {
      log.warn("프로필 사진 저장소 장애 {} — 다음 로그인 때 다시", accountId);
      return;
    }
    Optional<Optional<String>> old =
        store.recordProfileImage(accountId, source, key, type, sha, clock.instant());
    if (old.isEmpty()) {
      // 받는 사이 원본이 또 바뀜 — 이 사본은 쓰이지 않는다(새 원본 복사가 따로 돈다)
      deleteQuietly(s, key, accountId);
      return;
    }
    old.get().filter(k -> !k.equals(key)).ifPresent(k -> deleteQuietly(s, k, accountId));
    log.info("프로필 사진 사본 갱신 {}", accountId);
  }

  /** 본인 사진 사본. 없으면 404. */
  public Image open(String accountId) {
    ProfileImageStorage s = storage.getIfAvailable();
    ProfileImageRow row = store.profileImage(accountId).orElse(null);
    if (s == null || row == null || row.key() == null || row.contentType() == null) {
      throw notFound();
    }
    try {
      return new Image(row.contentType(), row.sha256(), s.open(row.key()));
    } catch (NoSuchElementException e) {
      throw notFound();
    }
  }

  public record Image(
      String contentType, @Nullable String sha256, ProfileImageStorage.Stored body) {}

  private static PmartException notFound() {
    return PmartException.notFound("PROFILE_IMAGE_NOT_FOUND", "프로필 사진이 없습니다.");
  }

  private static void deleteQuietly(ProfileImageStorage s, String key, String accountId) {
    try {
      s.delete(key);
    } catch (RuntimeException e) {
      log.warn("지난 프로필 사진 사본을 지우지 못함 {}", accountId);
    }
  }

  private static String sha256(byte[] b) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
