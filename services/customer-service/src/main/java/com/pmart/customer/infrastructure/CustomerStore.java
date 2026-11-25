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
      @Nullable String providerNickname,
      @Nullable String email,
      @Nullable Boolean emailVerified,
      @Nullable String profileImageSha256,
      String photoSource,
      Instant joinedAt,
      int version,
      @Nullable String phone,
      boolean phoneVerified,
      @Nullable String customerNumber,
      String restriction,
      @Nullable Instant restrictionUntil,
      int restrictionVersion) {

    /** 0.16.0 CU-7 — 지금 효력의 제한(기한 지나면 NONE). */
    public String restrictionAt(Instant now) {
      return com.pmart.customer.domain.RestrictionRules.effective(
          restriction, restrictionUntil, now);
    }

    public @Nullable Instant restrictionUntilAt(Instant now) {
      return com.pmart.customer.domain.RestrictionRules.effectiveUntil(
          restriction, restrictionUntil, now);
    }
  }

  /** 로그인 제공자가 준 프로필(CustomerAuthenticated, ADR-0024). 값은 scope 에 따라 없을 수 있다. 개인정보 — 로그 금지. */
  public record ProviderProfile(
      @Nullable String name,
      @Nullable String email,
      @Nullable Boolean emailVerified,
      @Nullable String pictureUrl,
      @Nullable String providerNickname) {

    public ProviderProfile(
        @Nullable String name,
        @Nullable String email,
        @Nullable Boolean emailVerified,
        @Nullable String pictureUrl) {
      this(name, email, emailVerified, pictureUrl, null);
    }
  }

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
            + " last_authenticated_at, name, email, email_verified, profile_image_source_url,"
            + " provider_nickname, version, updated_at) VALUES (?,'ACTIVE',?,?,?,?,?,?,?,?,0,?)"
            + " ON CONFLICT (account_id) DO UPDATE SET"
            + " social_provider = COALESCE(customer.social_provider, EXCLUDED.social_provider),"
            + " joined_at = LEAST(customer.joined_at, EXCLUDED.joined_at),"
            + " name = CASE WHEN "
            + latest
            + " THEN COALESCE(EXCLUDED.name, customer.name) ELSE customer.name END,"
            + " provider_nickname = CASE WHEN "
            + latest
            + " THEN COALESCE(EXCLUDED.provider_nickname, customer.provider_nickname)"
            + " ELSE customer.provider_nickname END,"
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
        p.providerNickname(),
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

  /** 확인된 번호를 프로필 번호로(2026-11-20 ADR-0074 R6) — phone_verified, version +1. */
  public void setVerifiedPhone(String accountId, String phone, Instant at) {
    jdbc.update(
        "UPDATE customer SET phone = ?, phone_verified = true, version = version + 1, updated_at = ?"
            + " WHERE account_id = ?",
        phone,
        ts(at),
        accountId);
  }

  public Optional<ProfileRow> profile(String accountId) {
    return jdbc
        .query(PROFILE_SELECT + " WHERE account_id = ?", CustomerStore::profileRow, accountId)
        .stream()
        .findFirst();
  }

  private static final String PROFILE_SELECT =
      "SELECT account_id, status, social_provider, nickname, name, provider_nickname, email,"
          + " email_verified,"
          + " profile_image_sha256, profile_photo_source, joined_at, version, phone, phone_verified,"
          + " customer_number, restriction, restriction_until, restriction_version FROM customer";

  private static ProfileRow profileRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
    return new ProfileRow(
        rs.getString("account_id"),
        rs.getString("status"),
        rs.getString("social_provider"),
        rs.getString("nickname"),
        rs.getString("name"),
        rs.getString("provider_nickname"),
        rs.getString("email"),
        rs.getObject("email_verified", Boolean.class),
        rs.getString("profile_image_sha256"),
        rs.getString("profile_photo_source"),
        rs.getTimestamp("joined_at").toInstant(),
        rs.getInt("version"),
        rs.getString("phone"),
        rs.getBoolean("phone_verified"),
        rs.getString("customer_number"),
        rs.getString("restriction"),
        rs.getTimestamp("restriction_until") == null
            ? null
            : rs.getTimestamp("restriction_until").toInstant(),
        rs.getInt("restriction_version"));
  }

  /** 직원 찾기(2026-11-20 CU-4): 고객번호 완전 일치. */
  public List<ProfileRow> byCustomerNumber(String customerNumber) {
    return jdbc.query(
        PROFILE_SELECT + " WHERE customer_number = ?", CustomerStore::profileRow, customerNumber);
  }

  /** 직원 찾기(CU-4): 프로필 휴대폰 번호(숫자만) 완전 일치 — 가입 순. 번호는 로그 금지. */
  public List<ProfileRow> byPhone(String digits) {
    return jdbc.query(
        PROFILE_SELECT + " WHERE phone = ? ORDER BY joined_at LIMIT 20",
        CustomerStore::profileRow,
        digits);
  }

  /** 여러 고객 한 번에(CU-3 — bff 화면 표기). 없는 ID 는 빠진다, 요청 순서대로. */
  public List<ProfileRow> byIds(List<String> accountIds) {
    if (accountIds.isEmpty()) {
      return List.of();
    }
    List<ProfileRow> rows =
        jdbc.query(
            PROFILE_SELECT
                + " WHERE account_id IN ("
                + String.join(",", java.util.Collections.nCopies(accountIds.size(), "?"))
                + ")",
            CustomerStore::profileRow,
            accountIds.toArray());
    java.util.Map<String, ProfileRow> m = new java.util.HashMap<>();
    rows.forEach(r -> m.put(r.accountId(), r));
    return accountIds.stream().map(m::get).filter(java.util.Objects::nonNull).toList();
  }

  /** 프로필 변경 직렬화 — 같은 계정의 변경·탈퇴 신청이 겹치지 않게 행을 잠그고 상태·버전을 본다. */
  public Optional<ProfileRow> lockProfile(String accountId) {
    jdbc.queryForList("SELECT 1 FROM customer WHERE account_id = ? FOR UPDATE", accountId);
    return profile(accountId);
  }

  /** 닉네임 변경(버전 확인). 개인정보 — 값은 로그 금지. 바뀐 행 수(0 = 버전 충돌). */
  public int updateNickname(String accountId, int expectedVersion, String nickname, Instant now) {
    return jdbc.update(
        "UPDATE customer SET nickname = ?, version = version + 1, updated_at = ?"
            + " WHERE account_id = ? AND version = ?",
        nickname,
        ts(now),
        accountId,
        expectedVersion);
  }

  /**
   * 닉네임·휴대폰(확인 안 됨 — 2026-11-19 BR-32)을 한 번에 바꾼다(버전 +1). nickname null 이면 그대로. phone 은 값 그대로(null =
   * 지우기).
   */
  public int updateProfile(
      String accountId,
      int expectedVersion,
      @Nullable String nickname,
      boolean setPhone,
      @Nullable String phone,
      Instant now) {
    return jdbc.update(
        "UPDATE customer SET nickname = COALESCE(?, nickname),"
            + " phone = CASE WHEN ? THEN ? ELSE phone END,"
            + " phone_verified = CASE WHEN ? THEN false ELSE phone_verified END,"
            + " version = version + 1, updated_at = ? WHERE account_id = ? AND version = ?",
        nickname,
        setPhone,
        phone,
        setPhone,
        ts(now),
        accountId,
        expectedVersion);
  }

  /** 프로필 변경 결과(멱등 기록 request_record 의 결과). 본인(actor) 것만. */
  public Optional<String> profileChangeResult(String accountId, String requestId) {
    return jdbc
        .queryForList(
            "SELECT result::text FROM request_record"
                + " WHERE request_id = ? AND actor = ? AND operation = 'changeMyProfile'",
            String.class,
            requestId,
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
      @Nullable String sha256,
      String photoSource) {}

  public static final String PHOTO_PROVIDER = "PROVIDER";
  public static final String PHOTO_CUSTOM = "CUSTOM";
  public static final String PHOTO_NONE = "NONE";

  public Optional<ProfileImageRow> profileImage(String accountId) {
    return jdbc
        .query(
            "SELECT profile_image_source_url, profile_image_copied_from, profile_image_key,"
                + " profile_image_content_type, profile_image_sha256, profile_photo_source"
                + " FROM customer WHERE account_id = ?",
            (rs, i) ->
                new ProfileImageRow(
                    rs.getString("profile_image_source_url"),
                    rs.getString("profile_image_copied_from"),
                    rs.getString("profile_image_key"),
                    rs.getString("profile_image_content_type"),
                    rs.getString("profile_image_sha256"),
                    rs.getString("profile_photo_source")),
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
                + " WHERE c.account_id = ? AND c.profile_image_source_url = ?"
                + " AND c.profile_photo_source = 'PROVIDER' RETURNING o.old_key",
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

  /**
   * 사진 출처 바꾸기(BR-80). CUSTOM 이면 올린 사진을 지금 사진으로, PROVIDER·NONE 이면 지금 사진을 비운다(PROVIDER 는 copied_from
   * 도 비워 제공자 사진을 다시 받게). 바꾸기 전 사진 키(지울 것)를 돌려준다 — 행이 없으면 빈 값.
   */
  public Optional<Optional<String>> setProfilePhoto(
      String accountId,
      String source,
      @Nullable String key,
      @Nullable String contentType,
      @Nullable String sha256,
      Instant now) {
    List<Optional<String>> old =
        jdbc.query(
            "UPDATE customer c SET profile_photo_source = ?, profile_image_key = ?,"
                + " profile_image_content_type = ?, profile_image_sha256 = ?,"
                + " profile_image_copied_from = NULL, profile_image_updated_at = ?"
                + " FROM (SELECT profile_image_key AS old_key FROM customer WHERE account_id = ?"
                + " FOR UPDATE) o"
                + " WHERE c.account_id = ? RETURNING o.old_key",
            (rs, i) -> Optional.ofNullable(rs.getString("old_key")),
            source,
            key,
            contentType,
            sha256,
            ts(now),
            accountId,
            accountId);
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

  /** 첫 구매 혜택 사용 기록 되돌리기(그 주문이 쓴 기록만). 출고 전 전체 취소는 자격을 소진하지 않는다(OMS 175행). 지웠으면 true. */
  public boolean releaseFirstPurchaseUse(String accountId, String orderId) {
    return jdbc.update(
            "DELETE FROM first_purchase_used WHERE account_id = ? AND order_id = ?",
            accountId,
            orderId)
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
            + " entrance_note, lat, lng, geo_source, svc_state, svc_result, svc_mfc_id, svc_zone_id,"
            + " svc_mapping_version, svc_check_id, svc_as_of, version, created_at, updated_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?)",
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
        d.geoSource(),
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
            + " entrance_note = ?, lat = ?, lng = ?, geo_source = ?, svc_state = ?, svc_result = ?,"
            + " svc_mfc_id = ?,"
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
        d.geoSource(),
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
            rs.getObject("lng", Double.class),
            rs.getString("geo_source")),
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
