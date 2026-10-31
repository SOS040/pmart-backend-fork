package com.pmart.customer.infrastructure;

import com.pmart.customer.domain.AddressData;
import com.pmart.customer.domain.Serviceability;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 회원(customer)·주소·쇼핑 컨텍스트 저장(JDBC). */
@Repository
public class CustomerStore {

  public static final String OWNER_ACCOUNT = "ACCOUNT";
  public static final String OWNER_GUEST = "GUEST";

  public record ProfileRow(
      String accountId,
      String status,
      @Nullable String socialProvider,
      @Nullable String nickname,
      @Nullable String name,
      @Nullable String email,
      @Nullable Boolean emailVerified,
      @Nullable String profileImageSha256,
      Instant joinedAt,
      int version) {}

  /** 로그인 제공자가 준 프로필(CustomerAuthenticated, ADR-0024). 값은 scope 에 따라 없을 수 있다. 개인정보 — 로그 금지. */
  public record ProviderProfile(
      @Nullable String name,
      @Nullable String email,
      @Nullable Boolean emailVerified,
      @Nullable String pictureUrl) {}

  public record AddressRow(
      String addressId,
      String ownerType,
      String ownerId,
      AddressData data,
      Serviceability serviceability,
      int version,
      Instant createdAt,
      Instant updatedAt) {}

  public record ContextRow(
      String accountId,
      @Nullable String shoppingAddressId,
      @Nullable String mfcId,
      @Nullable String zoneId,
      boolean serviceable,
      int version,
      Instant changedAt) {}

  private final JdbcTemplate jdbc;

  public CustomerStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ---------- 프로필 ----------

  /**
   * CustomerAuthenticated 반영. 제공자는 처음 값을 유지하고 인증 시각은 더 늦은 쪽. 제공자 프로필(이름·이메일·사진)은 그 로그인이 지금까지 중 가장
   * 최근일 때만 덮어쓰고(늦게 도착한 옛 사건이 최신 값을 되돌리지 않게), 사건에 없는 값은 기존 값을 유지한다.
   */
  public void upsertFromAuthentication(
      String accountId, String provider, Instant at, ProviderProfile p, Instant now) {
    String latest =
        "(customer.last_authenticated_at IS NULL"
            + " OR EXCLUDED.last_authenticated_at >= customer.last_authenticated_at)";
    jdbc.update(
        "INSERT INTO customer (account_id, status, social_provider, joined_at,"
            + " last_authenticated_at, name, email, email_verified, profile_image_source_url, version,"
            + " updated_at) VALUES (?,'ACTIVE',?,?,?,?,?,?,?,0,?)"
            + " ON CONFLICT (account_id) DO UPDATE SET"
            + " social_provider = COALESCE(customer.social_provider, EXCLUDED.social_provider),"
            + " joined_at = LEAST(customer.joined_at, EXCLUDED.joined_at),"
            + " name = CASE WHEN "
            + latest
            + " THEN COALESCE(EXCLUDED.name, customer.name) ELSE customer.name END,"
            + " email = CASE WHEN "
            + latest
            + " THEN COALESCE(EXCLUDED.email, customer.email) ELSE customer.email END,"
            + " email_verified = CASE WHEN "
            + latest
            + " THEN COALESCE(EXCLUDED.email_verified, customer.email_verified)"
            + " ELSE customer.email_verified END,"
            + " profile_image_source_url = CASE WHEN "
            + latest
            + " THEN COALESCE(EXCLUDED.profile_image_source_url, customer.profile_image_source_url)"
            + " ELSE customer.profile_image_source_url END,"
            + " last_authenticated_at = GREATEST(customer.last_authenticated_at,"
            + " EXCLUDED.last_authenticated_at), updated_at = EXCLUDED.updated_at",
        accountId,
        provider,
        ts(at),
        ts(at),
        p.name(),
        p.email(),
        p.emailVerified(),
        p.pictureUrl(),
        ts(now));
  }

  /** 이벤트보다 API 가 먼저 와도 프로필이 있도록(가입 시각은 이벤트가 오면 더 이른 쪽으로). */
  public void ensureProfile(String accountId, Instant now) {
    jdbc.update(
        "INSERT INTO customer (account_id, status, joined_at, version, updated_at)"
            + " VALUES (?,'ACTIVE',?,0,?) ON CONFLICT (account_id) DO NOTHING",
        accountId,
        ts(now),
        ts(now));
  }

  public Optional<ProfileRow> profile(String accountId) {
    return jdbc
        .query(
            "SELECT account_id, status, social_provider, nickname, name, email, email_verified,"
                + " profile_image_sha256, joined_at, version FROM customer WHERE account_id = ?",
            (rs, i) ->
                new ProfileRow(
                    rs.getString("account_id"),
                    rs.getString("status"),
                    rs.getString("social_provider"),
                    rs.getString("nickname"),
                    rs.getString("name"),
                    rs.getString("email"),
                    rs.getObject("email_verified", Boolean.class),
                    rs.getString("profile_image_sha256"),
                    rs.getTimestamp("joined_at").toInstant(),
                    rs.getInt("version")),
            accountId)
        .stream()
        .findFirst();
  }

  // ---------- 프로필 사진 사본 (ADR-0024) ----------

  /** 받을 원본·저장된 사본. 개인정보(원본 URL)는 로그 금지. */
  public record ProfileImageRow(
      @Nullable String sourceUrl,
      @Nullable String copiedFrom,
      @Nullable String key,
      @Nullable String contentType,
      @Nullable String sha256) {}

  public Optional<ProfileImageRow> profileImage(String accountId) {
    return jdbc
        .query(
            "SELECT profile_image_source_url, profile_image_copied_from, profile_image_key,"
                + " profile_image_content_type, profile_image_sha256 FROM customer WHERE account_id = ?",
            (rs, i) ->
                new ProfileImageRow(
                    rs.getString("profile_image_source_url"),
                    rs.getString("profile_image_copied_from"),
                    rs.getString("profile_image_key"),
                    rs.getString("profile_image_content_type"),
                    rs.getString("profile_image_sha256")),
            accountId)
        .stream()
        .findFirst();
  }

  /**
   * 사본 반영. 받는 동안 원본이 바뀌지 않았을 때만(= 지금 원본이 copiedFrom) 바꾸고 이전 사본 키를 돌려준다. 바뀌었으면 빈 값 — 새 원본은 다음 복사가
   * 처리한다.
   */
  public Optional<Optional<String>> recordProfileImage(
      String accountId,
      String copiedFrom,
      String key,
      String contentType,
      String sha256,
      Instant now) {
    List<Optional<String>> old =
        jdbc.query(
            "UPDATE customer c SET profile_image_key = ?, profile_image_content_type = ?,"
                + " profile_image_sha256 = ?, profile_image_copied_from = ?,"
                + " profile_image_updated_at = ?"
                + " FROM (SELECT profile_image_key AS old_key FROM customer WHERE account_id = ?"
                + " FOR UPDATE) o"
                + " WHERE c.account_id = ? AND c.profile_image_source_url = ? RETURNING o.old_key",
            (rs, i) -> Optional.ofNullable(rs.getString("old_key")),
            key,
            contentType,
            sha256,
            copiedFrom,
            ts(now),
            accountId,
            accountId,
            copiedFrom);
    return old.stream().findFirst();
  }

  /** 받을 수 없는 원본(허용 밖·이미지 아님)은 다시 시도하지 않도록 표시만 한다(사본은 그대로). */
  public void markProfileImageSkipped(String accountId, String sourceUrl, Instant now) {
    jdbc.update(
        "UPDATE customer SET profile_image_copied_from = ?, profile_image_updated_at = ?"
            + " WHERE account_id = ? AND profile_image_source_url = ?",
        sourceUrl,
        ts(now),
        accountId,
        sourceUrl);
  }

  /** 첫 구매 혜택을 이미 쓴 계정인가(대조값). */
  public boolean firstPurchaseUsed(String accountId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM first_purchase_used WHERE account_id = ?",
            Integer.class,
            accountId);
    return n != null && n > 0;
  }

  /** 첫 구매 혜택 사용 기록. 이미 있으면 그대로(먼저 쓴 주문을 남긴다). */
  public boolean markFirstPurchaseUsed(String accountId, String orderId, Instant usedAt) {
    return jdbc.update(
            "INSERT INTO first_purchase_used (account_id, order_id, used_at) VALUES (?,?,?)"
                + " ON CONFLICT (account_id) DO NOTHING",
            accountId,
            orderId,
            Timestamp.from(usedAt))
        > 0;
  }

  // ---------- 주소 ----------

  public int countAddresses(String ownerType, String ownerId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM customer_address WHERE owner_type = ? AND owner_id = ?",
            Integer.class,
            ownerType,
            ownerId);
    return n == null ? 0 : n;
  }

  public void insertAddress(AddressRow a) {
    AddressData d = a.data();
    Serviceability s = a.serviceability();
    jdbc.update(
        "INSERT INTO customer_address (address_id, owner_type, owner_id, label, road_address,"
            + " detail_address, postal_code, receiver_name, receiver_phone, entrance_method,"
            + " entrance_note, lat, lng, svc_state, svc_result, svc_mfc_id, svc_zone_id,"
            + " svc_mapping_version, svc_check_id, svc_as_of, version, created_at, updated_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?)",
        a.addressId(),
        a.ownerType(),
        a.ownerId(),
        d.label(),
        d.roadAddress(),
        d.detailAddress(),
        d.postalCode(),
        d.receiverName(),
        d.receiverPhone(),
        d.entranceMethod(),
        d.entranceNote(),
        d.lat(),
        d.lng(),
        s.state(),
        s.result(),
        s.mfcId(),
        s.zoneId(),
        s.mappingVersion(),
        s.checkId(),
        ts(s.asOf()),
        ts(a.createdAt()),
        ts(a.updatedAt()));
  }

  /** 입력·판정 갱신. 버전이 다르면 0(낙관적 잠금). */
  public int updateAddress(
      String addressId, int expectedVersion, AddressData d, Serviceability s, Instant now) {
    return jdbc.update(
        "UPDATE customer_address SET label = ?, road_address = ?, detail_address = ?,"
            + " postal_code = ?, receiver_name = ?, receiver_phone = ?, entrance_method = ?,"
            + " entrance_note = ?, lat = ?, lng = ?, svc_state = ?, svc_result = ?, svc_mfc_id = ?,"
            + " svc_zone_id = ?, svc_mapping_version = ?, svc_check_id = ?, svc_as_of = ?,"
            + " version = version + 1, updated_at = ? WHERE address_id = ? AND version = ?",
        d.label(),
        d.roadAddress(),
        d.detailAddress(),
        d.postalCode(),
        d.receiverName(),
        d.receiverPhone(),
        d.entranceMethod(),
        d.entranceNote(),
        d.lat(),
        d.lng(),
        s.state(),
        s.result(),
        s.mfcId(),
        s.zoneId(),
        s.mappingVersion(),
        s.checkId(),
        ts(s.asOf()),
        ts(now),
        addressId,
        expectedVersion);
  }

  /** 판정 사본만 갱신(쇼핑 주소 선택 때 재판정). 고객 입력 버전은 올리지 않는다. */
  public void updateServiceability(String addressId, Serviceability s) {
    jdbc.update(
        "UPDATE customer_address SET svc_state = ?, svc_result = ?, svc_mfc_id = ?,"
            + " svc_zone_id = ?, svc_mapping_version = ?, svc_check_id = ?, svc_as_of = ?"
            + " WHERE address_id = ?",
        s.state(),
        s.result(),
        s.mfcId(),
        s.zoneId(),
        s.mappingVersion(),
        s.checkId(),
        ts(s.asOf()),
        addressId);
  }

  public Optional<AddressRow> address(String addressId) {
    return jdbc
        .query(
            "SELECT * FROM customer_address WHERE address_id = ?",
            (rs, i) -> addressRow(rs),
            addressId)
        .stream()
        .findFirst();
  }

  public List<AddressRow> addresses(String ownerType, String ownerId) {
    return jdbc.query(
        "SELECT * FROM customer_address WHERE owner_type = ? AND owner_id = ?"
            + " ORDER BY created_at, address_id",
        (rs, i) -> addressRow(rs),
        ownerType,
        ownerId);
  }

  public void deleteAddress(String addressId) {
    jdbc.update("DELETE FROM customer_address WHERE address_id = ?", addressId);
  }

  // ---------- 쇼핑 컨텍스트 ----------

  public Optional<ContextRow> context(String accountId) {
    return jdbc
        .query(
            "SELECT * FROM shopping_context WHERE account_id = ?",
            (rs, i) -> contextRow(rs),
            accountId)
        .stream()
        .findFirst();
  }

  /** 행이 없으면 만들고 잠근다(같은 계정의 동시 변경 직렬화). */
  public ContextRow lockContext(String accountId, Instant now) {
    jdbc.update(
        "INSERT INTO shopping_context (account_id, context_version, changed_at) VALUES (?,0,?)"
            + " ON CONFLICT (account_id) DO NOTHING",
        accountId,
        ts(now));
    ContextRow row =
        jdbc.queryForObject(
            "SELECT * FROM shopping_context WHERE account_id = ? FOR UPDATE",
            (rs, i) -> contextRow(rs),
            accountId);
    if (row == null) {
      throw new IllegalStateException("shopping_context 행을 잠그지 못했다");
    }
    return row;
  }

  public void saveContext(ContextRow c) {
    jdbc.update(
        "UPDATE shopping_context SET shopping_address_id = ?, mfc_id = ?, zone_id = ?,"
            + " serviceable = ?, context_version = ?, changed_at = ? WHERE account_id = ?",
        c.shoppingAddressId(),
        c.mfcId(),
        c.zoneId(),
        c.serviceable(),
        c.version(),
        ts(c.changedAt()),
        c.accountId());
  }

  private static AddressRow addressRow(ResultSet rs) throws SQLException {
    Instant updatedAt = rs.getTimestamp("updated_at").toInstant();
    Timestamp asOf = rs.getTimestamp("svc_as_of");
    return new AddressRow(
        rs.getString("address_id"),
        rs.getString("owner_type"),
        rs.getString("owner_id"),
        new AddressData(
            rs.getString("label"),
            rs.getString("road_address"),
            rs.getString("detail_address"),
            rs.getString("postal_code"),
            rs.getString("receiver_name"),
            rs.getString("receiver_phone"),
            rs.getString("entrance_method"),
            rs.getString("entrance_note"),
            rs.getObject("lat", Double.class),
            rs.getObject("lng", Double.class)),
        new Serviceability(
            rs.getString("svc_state"),
            rs.getString("svc_result"),
            rs.getString("svc_mfc_id"),
            rs.getString("svc_zone_id"),
            rs.getObject("svc_mapping_version", Integer.class),
            rs.getString("svc_check_id"),
            asOf == null ? updatedAt : asOf.toInstant()),
        rs.getInt("version"),
        rs.getTimestamp("created_at").toInstant(),
        updatedAt);
  }

  private static ContextRow contextRow(ResultSet rs) throws SQLException {
    return new ContextRow(
        rs.getString("account_id"),
        rs.getString("shopping_address_id"),
        rs.getString("mfc_id"),
        rs.getString("zone_id"),
        rs.getBoolean("serviceable"),
        rs.getInt("context_version"),
        rs.getTimestamp("changed_at").toInstant());
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
