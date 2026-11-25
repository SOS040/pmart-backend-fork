package com.pmart.rider.application;

import com.pmart.common.web.PmartException;
import com.pmart.rider.domain.ProfileImageRules;
import com.pmart.rider.infrastructure.ProfileImageDownloader;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 라이더 소셜 프로필 사진의 우리 사본(2026-11-25 — 사용자 "프로필 사진도 해줘", customer-service ProfileImageService 와 같은
 * 구조). 제공자 주소를 앱에 그대로 주지 않는다.
 *
 * <ul>
 *   <li>RiderAuthenticated 반영이 커밋된 뒤, 원본 주소가 지난 사본의 원본과 다를 때만 트랜잭션 밖(가상 스레드)에서 받는다.
 *   <li>키 {@code profile-images/<riderId>/<sha256>} — 같은 키의 본문은 바뀌지 않는다.
 *   <li>받기 실패는 기존 사본 그대로(다음 로그인 때 다시). 허용 밖·이미지 아님은 다시 받지 않는다.
 *   <li>버킷이 설정되지 않았으면 사본 기능만 꺼진다.
 * </ul>
 */
@Service
public class RiderProfileImages {

  private static final Logger log = LoggerFactory.getLogger(RiderProfileImages.class);

  /** 앱이 받는 경로(게이트웨이 상대 경로, 본인 토큰). 사본이 바뀌면 v 가 바뀐다. */
  public static final String PATH = "/api/v1/riders/me/profile-image";

  private final JdbcTemplate jdbc;
  private final ObjectProvider<ProfileImageStorage> storage;
  private final ProfileImageDownloader downloader;
  private final ProfileImageProperties props;
  private final Clock clock;

  private final ExecutorService executor =
      ContextExecutorService.wrap(
          Executors.newVirtualThreadPerTaskExecutor(),
          () -> ContextSnapshotFactory.builder().build().captureAll());

  public RiderProfileImages(
      JdbcTemplate jdbc,
      ObjectProvider<ProfileImageStorage> storage,
      ProfileImageDownloader downloader,
      ProfileImageProperties props,
      Clock clock) {
    this.jdbc = jdbc;
    this.storage = storage;
    this.downloader = downloader;
    this.props = props;
    this.clock = clock;
  }

  @PreDestroy
  void close() {
    executor.shutdownNow();
  }

  /** 응답의 사진 주소. 사본이 없으면 null. */
  public static @Nullable String url(@Nullable String sha256) {
    return sha256 == null ? null : PATH + "?v=" + sha256.strip().substring(0, 12);
  }

  record Row(
      @Nullable String source,
      @Nullable String copiedFrom,
      @Nullable String key,
      @Nullable String contentType,
      @Nullable String sha256) {}

  Optional<Row> row(String riderId) {
    return jdbc
        .query(
            "SELECT picture_source_url, image_copied_from, image_key, image_content_type, image_sha256"
                + " FROM rider_provider_profile WHERE rider_id = ?",
            (rs, i) ->
                new Row(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5)),
            riderId)
        .stream()
        .findFirst();
  }

  /** 사건 반영 뒤 — 커밋되면 사본을 만든다. */
  public void copyAfterCommit(String riderId) {
    if (storage.getIfAvailable() == null) {
      return;
    }
    Runnable task = () -> copy(riderId);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              executor.execute(task);
            }
          });
    } else {
      executor.execute(task);
    }
  }

  /** 탈퇴 — 커밋 뒤 사본 파일을 지운다(행은 탈퇴 트랜잭션이 지움). */
  public void deleteAfterCommit(@Nullable String key) {
    ProfileImageStorage s = storage.getIfAvailable();
    if (s == null || key == null) {
      return;
    }
    Runnable task = () -> deleteQuietly(s, key);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              executor.execute(task);
            }
          });
    } else {
      executor.execute(task);
    }
  }

  void copy(String riderId) {
    ProfileImageStorage s = storage.getIfAvailable();
    Row row = row(riderId).orElse(null);
    if (s == null || row == null || row.source() == null) {
      return;
    }
    String source = row.source();
    if (source.equals(row.copiedFrom())) {
      return;
    }
    Optional<URI> uri =
        ProfileImageRules.fetchUri(
            source, props.allowedHostSuffixes(), props.allowHttp(), props.sizePx());
    if (uri.isEmpty()) {
      log.warn("라이더 프로필 사진 원본이 허용 범위 밖이라 받지 않음 {}", riderId);
      markSkipped(riderId, source);
      return;
    }
    byte[] body;
    try {
      body = downloader.download(uri.get()).orElse(null);
      URI original = body == null ? ProfileImageRules.original(uri.get(), source) : null;
      if (original != null) {
        body = downloader.download(original).orElse(null);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    } catch (Exception e) {
      log.warn("라이더 프로필 사진을 받지 못함 {} — 다음 로그인 때 다시: {}", riderId, e.toString());
      return;
    }
    if (body == null) {
      log.warn("라이더 프로필 사진 응답이 없거나 상한 초과 {} — 다음 로그인 때 다시", riderId);
      return;
    }
    String type = ProfileImageRules.imageType(body).orElse(null);
    if (type == null) {
      log.warn("라이더 프로필 사진 원본이 이미지가 아님 {}", riderId);
      markSkipped(riderId, source);
      return;
    }
    String sha = sha256(body);
    String key = "profile-images/" + riderId + "/" + sha;
    try {
      s.put(key, body, type);
    } catch (PmartException e) {
      log.warn("라이더 프로필 사진 저장소 장애 {} — 다음 로그인 때 다시", riderId);
      return;
    }
    String old = row.key();
    int n =
        jdbc.update(
            "UPDATE rider_provider_profile SET image_key = ?, image_content_type = ?, image_sha256 = ?,"
                + " image_copied_from = ?, image_updated_at = ? WHERE rider_id = ?"
                + " AND picture_source_url = ?",
            key,
            type,
            sha,
            source,
            Timestamp.from(clock.instant()),
            riderId,
            source);
    if (n == 0) {
      deleteQuietly(s, key); // 받는 사이 원본이 또 바뀜(새 원본 복사가 따로 돈다)
      return;
    }
    if (old != null && !old.equals(key)) {
      deleteQuietly(s, old);
    }
    log.info("라이더 프로필 사진 사본 갱신 {}", riderId);
  }

  private void markSkipped(String riderId, String source) {
    jdbc.update(
        "UPDATE rider_provider_profile SET image_copied_from = ? WHERE rider_id = ?"
            + " AND picture_source_url = ?",
        source,
        riderId,
        source);
  }

  /** 본인(라이더 토큰) — 가입 전에도. */
  public Image mine() {
    return open(RiderService.requireRider());
  }

  /** 사진 사본. 없으면 404. */
  public Image open(String riderId) {
    ProfileImageStorage s = storage.getIfAvailable();
    Row row = row(riderId).orElse(null);
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

  private static void deleteQuietly(ProfileImageStorage s, String key) {
    try {
      s.delete(key);
    } catch (RuntimeException e) {
      log.warn("라이더 프로필 사진 사본을 지우지 못함");
    }
  }

  static String sha256(byte[] b) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
