package com.pmart.rider.application;

import com.pmart.common.messaging.EventEnvelope;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 소셜 이름·닉네임 사본(2026-11-25 — 고객 customer-service 의 CustomerAuthenticated 반영과 같게, 사용자 "이름 닉네임 먼저해줘").
 * rider-auth {@code RiderAuthenticated}(platform.auth.v1, 바인딩 {@code authEvents-in-0}) → {@code
 * rider_provider_profile}. 인증 시각이 가장 최근인 사건만 덮고, 사건에 없는 값은 그대로 둔다. 가입 전에도 있다. 라이더 실명은 {@code
 * rider.name}(가입 입력) 그대로 — 이 값은 미리 채우기·표시용. 값은 로그 금지.
 */
@Service
public class ProviderProfiles {

  public static final Set<String> TYPES = Set.of("RiderAuthenticated");

  public record ProviderProfile(
      String provider,
      @Nullable String name,
      @Nullable String providerNickname,
      Instant at,
      @Nullable String imageSha256) {
    @Override
    public String toString() {
      return "ProviderProfile[" + provider + ", ***]";
    }
  }

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final RiderProfileImages images;

  public ProviderProfiles(JdbcTemplate jdbc, Clock clock, RiderProfileImages images) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.images = images;
  }

  /** inbox 트랜잭션 안. */
  public void on(EventEnvelope e) {
    if (!TYPES.contains(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    String riderId = text(p, "riderId");
    String provider = text(p, "provider");
    if (riderId == null || provider == null) {
      return;
    }
    Instant at =
        p.hasNonNull("authenticatedAt")
            ? Instant.parse(p.get("authenticatedAt").asString())
            : e.occurredAt();
    jdbc.update(
        "INSERT INTO rider_provider_profile (rider_id, provider, name, provider_nickname,"
            + " picture_source_url, authenticated_at, updated_at) VALUES (?,?,?,?,?,?,?)"
            + " ON CONFLICT (rider_id) DO UPDATE SET"
            + " provider = EXCLUDED.provider,"
            + " name = COALESCE(EXCLUDED.name, rider_provider_profile.name),"
            + " provider_nickname = COALESCE(EXCLUDED.provider_nickname,"
            + " rider_provider_profile.provider_nickname),"
            + " picture_source_url = COALESCE(EXCLUDED.picture_source_url,"
            + " rider_provider_profile.picture_source_url),"
            + " authenticated_at = EXCLUDED.authenticated_at, updated_at = EXCLUDED.updated_at"
            + " WHERE EXCLUDED.authenticated_at >= rider_provider_profile.authenticated_at",
        riderId,
        provider,
        clip(text(p, "name")),
        clip(text(p, "providerNickname")),
        pictureUrl(text(p, "pictureUrl")),
        Timestamp.from(at),
        Timestamp.from(clock.instant()));
    // 사진 사본은 커밋 뒤(원본이 바뀌었을 때만 받는다)
    images.copyAfterCommit(riderId);
  }

  private static @Nullable String pictureUrl(@Nullable String v) {
    return v == null || v.length() > 1000 ? null : v;
  }

  public Optional<ProviderProfile> find(String riderId) {
    return jdbc
        .query(
            "SELECT provider, name, provider_nickname, authenticated_at, image_sha256"
                + " FROM rider_provider_profile WHERE rider_id = ?",
            (rs, i) ->
                new ProviderProfile(
                    rs.getString("provider"),
                    rs.getString("name"),
                    rs.getString("provider_nickname"),
                    rs.getTimestamp("authenticated_at").toInstant(),
                    rs.getString("image_sha256")),
            riderId)
        .stream()
        .findFirst();
  }

  /** 본인(라이더 토큰) — 가입 전에도. */
  public Optional<ProviderProfile> mine() {
    return find(RiderService.requireRider());
  }

  /** 탈퇴 때(그 트랜잭션 안) — 사진 사본 파일은 커밋 뒤 지운다. */
  public void delete(String riderId) {
    String key =
        jdbc.query(
            "SELECT image_key FROM rider_provider_profile WHERE rider_id = ?",
            rs -> rs.next() ? rs.getString(1) : null,
            riderId);
    jdbc.update("DELETE FROM rider_provider_profile WHERE rider_id = ?", riderId);
    images.deleteAfterCommit(key);
  }

  private static @Nullable String text(JsonNode p, String field) {
    String v = p.hasNonNull(field) ? p.get(field).asString().strip() : null;
    return v == null || v.isEmpty() ? null : v;
  }

  private static @Nullable String clip(@Nullable String v) {
    return v == null ? null : v.length() > 100 ? v.substring(0, 100) : v;
  }
}
