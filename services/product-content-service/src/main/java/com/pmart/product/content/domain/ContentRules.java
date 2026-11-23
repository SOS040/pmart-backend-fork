package com.pmart.product.content.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** 콘텐츠 초안 규칙: 이미지 자리(role·sortOrder), 제출 조건(상품명·대표 이미지·상품 연결), 내용 해시와 저장소 키 규칙. 상태 저장은 하지 않는다. */
public final class ContentRules {

  /** 이미지 1장 상한(롱이미지 조각 1장은 수 MB). */
  public static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;

  /** 초안 1건의 이미지 상한. */
  public static final int MAX_IMAGES_PER_DRAFT = 100;

  private static final Pattern KEY_SEGMENT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$");

  private ContentRules() {}

  /** 옵션 표시 가용(계약 SaleOption.availability). carried 가 null 이면 취급 여부도 모른다. */
  public record Availability(@Nullable Boolean carried, String state) {
    public static final Availability UNKNOWN = new Availability(null, "UNKNOWN");
  }

  /**
   * 담당 센터 가용(A = E − R) → 상품 화면 표시. 취급 OFF → carried=false(UNKNOWN), A 불명 → UNKNOWN(0 아님), A ≤ 0 →
   * SOLD_OUT, 그 밖 AVAILABLE. "N개 남음"(LOW) 노출 임계값은 운영 설정이라(Commerce 6.11) 쓰지 않는다.
   */
  public static Availability availability(@Nullable Integer available, boolean handlingEnabled) {
    if (!handlingEnabled) {
      return new Availability(false, "UNKNOWN");
    }
    if (available == null) {
      return new Availability(true, "UNKNOWN");
    }
    return new Availability(true, available <= 0 ? "SOLD_OUT" : "AVAILABLE");
  }

  /** 저장된(또는 올릴) 이미지의 자리와 내용. */
  public record ImageSlot(ImageRole role, int sortOrder, String sha256) {}

  /** 이미지 자리 검사. 문제가 있으면 사유. */
  public static Optional<String> slotProblem(@Nullable ImageRole role, int sortOrder) {
    if (role == null) {
      return Optional.of("role 이 필요합니다(MAIN/DESCRIPTION/DETAIL).");
    }
    if (sortOrder < 0) {
      return Optional.of("sortOrder 는 0 이상이어야 합니다.");
    }
    if (role == ImageRole.MAIN && sortOrder != 0) {
      return Optional.of("대표 이미지(MAIN)는 1장이며 sortOrder 0 입니다.");
    }
    return Optional.empty();
  }

  /** 이미지 파일 검사(크기·형식). 문제가 있으면 사유. */
  public static Optional<String> fileProblem(byte[] bytes) {
    if (bytes.length == 0) {
      return Optional.of("빈 파일입니다.");
    }
    if (bytes.length > MAX_IMAGE_BYTES) {
      return Optional.of("이미지가 너무 큽니다(최대 " + MAX_IMAGE_BYTES / (1024 * 1024) + "MB).");
    }
    if (ImageFormat.detect(bytes).isEmpty()) {
      return Optional.of("JPEG·PNG·WebP 만 올릴 수 있습니다.");
    }
    return Optional.empty();
  }

  /** 지금 제출하면 막히는 사유. 비어 있으면 제출 가능. */
  public static List<ContentProblem> submissionProblems(
      @Nullable String name, @Nullable String productId, List<ImageSlot> images) {
    List<ContentProblem> out = new ArrayList<>();
    if (productId == null || productId.isBlank()) {
      out.add(
          new ContentProblem(
              ContentProblem.PRODUCT_NOT_LINKED,
              "productId",
              "상품 승인(ProductVersionPublished) 전입니다."));
    }
    if (name == null || name.isBlank()) {
      out.add(new ContentProblem(ContentProblem.NAME_REQUIRED, "name", "고객용 상품명이 필요합니다."));
    }
    if (images.stream().noneMatch(i -> i.role() == ImageRole.MAIN)) {
      out.add(
          new ContentProblem(ContentProblem.MAIN_IMAGE_REQUIRED, "images", "대표 이미지(MAIN)가 필요합니다."));
    }
    return out;
  }

  /** 내용 해시: 고객에게 보일 값(명칭·설명·안내·고시·옵션 JSON)과 이미지(자리+sha256). 상품 연결은 넣지 않는다 — 재적재 시 "바뀐 것 없음" 판단용. */
  public static String contentHash(
      @Nullable String name,
      @Nullable String description,
      @Nullable String storageGuide,
      @Nullable String returnGuide,
      String noticesJson,
      String optionsJson,
      List<ImageSlot> images) {
    return contentHash(
        name, description, storageGuide, returnGuide, noticesJson, optionsJson, images, null);
  }

  /** 표시사항 값(0.6.0 label_facts)이 있으면 해시에 더한다 — 없으면 0.6.0 이전과 같은 해시(이미 있는 초안이 "바뀜"으로 보이지 않게). */
  public static String contentHash(
      @Nullable String name,
      @Nullable String description,
      @Nullable String storageGuide,
      @Nullable String returnGuide,
      String noticesJson,
      String optionsJson,
      List<ImageSlot> images,
      @Nullable String labelFactsJson) {
    List<ImageSlot> sorted = new ArrayList<>(images);
    sorted.sort(
        Comparator.comparing((ImageSlot i) -> i.role().ordinal())
            .thenComparingInt(ImageSlot::sortOrder));
    StringBuilder sig = new StringBuilder();
    for (ImageSlot i : sorted) {
      sig.append(i.role()).append(':').append(i.sortOrder()).append(':').append(i.sha256());
      sig.append(';');
    }
    return labelFactsJson == null
        ? sha256(
            nz(name),
            nz(description),
            nz(storageGuide),
            nz(returnGuide),
            noticesJson,
            optionsJson,
            sig.toString())
        : sha256(
            nz(name),
            nz(description),
            nz(storageGuide),
            nz(returnGuide),
            noticesJson,
            optionsJson,
            sig.toString(),
            labelFactsJson);
  }

  /** 제출 시 잠그는 해시: 내용 + 연결 상품. 승인 행의 draftHash 와 대조한다. */
  public static String draftHash(String contentHash, String productId) {
    return sha256(contentHash, productId);
  }

  /**
   * 저장소 키 {@code products/{소유자}/{role}/{sha256}.{ext}}. 소유자는 연결된 productId, 연결 전이면 {@code
   * draft-{draftId}}. 내용 주소라 같은 키의 본문은 바뀌지 않는다.
   */
  public static String objectKey(String owner, ImageRole role, String sha256, ImageFormat format) {
    if (!KEY_SEGMENT.matcher(owner).matches()) {
      throw new IllegalArgumentException("저장소 키에 쓸 수 없는 소유자: " + owner);
    }
    if (!sha256.matches("^[0-9a-f]{64}$")) {
      throw new IllegalArgumentException("sha256 hex 가 아닙니다");
    }
    return "products/"
        + owner
        + "/"
        + role.name().toLowerCase(java.util.Locale.ROOT)
        + "/"
        + sha256
        + "."
        + format.extension();
  }

  public static String sha256(byte[] bytes) {
    return HexFormat.of().formatHex(digest().digest(bytes));
  }

  public static String sha256(String... parts) {
    MessageDigest md = digest();
    for (String p : parts) {
      md.update(p.getBytes(StandardCharsets.UTF_8));
      md.update((byte) 0);
    }
    return HexFormat.of().formatHex(md.digest());
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);
    }
  }

  private static String nz(@Nullable String s) {
    return s == null ? "" : s;
  }

  /** 단위 가격의 기준 양(2026-11-19 BR-19 — "100g당"). */
  public static final int UNIT_PRICE_BASIS = 100;

  /**
   * 단위 가격(BR-19): 가격 × 100 ÷ 내용량, 원 단위 반올림(0.5 올림). 내용량이 0 이하면 null(계산하지 않는다).
   *
   * @param amount 판매 단위 전체에 든 양(g·mL)
   */
  public static @org.jspecify.annotations.Nullable Long unitPrice(long price, int amount) {
    if (amount <= 0 || price < 0) {
      return null;
    }
    return (price * UNIT_PRICE_BASIS * 2 + amount) / (2L * amount);
  }
}
