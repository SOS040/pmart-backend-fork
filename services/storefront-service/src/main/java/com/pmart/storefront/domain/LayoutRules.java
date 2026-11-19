package com.pmart.storefront.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 진열 편성 규칙(2026-11-20 ADR-0075 ② — BO-05 일반 배너·상품 테마). 제출 때 검사하고, 게시된 본문으로 노출(기간)을 판단한다.
 *
 * <ul>
 *   <li>상품은 진열할 수 있어야 한다(게시 콘텐츠·상품 버전·판매중지 아님) — PRODUCT_NOT_PUBLISHED
 *   <li>배너 이미지는 올린 것이어야 한다 — IMAGE_NOT_FOUND. 링크: SECTION = 같은 초안의 테마, PRODUCT = 진열 가능 상품, CATEGORY
 *       = 값 있음, NONE = 비움 — INVALID_LINK
 *   <li>기간: startsAt &lt; endsAt — INVALID_PERIOD
 *   <li>1차 범위: 할인·N% off·쿠폰·특가·증정·1+1·세일, 근거 없는 최저가·신선 보장 문구("100% 국내산" 같은 %는 막지 않는다) —
 *       PROMOTION_NOT_ALLOWED(BackOffice BO-05 §3)
 *   <li>배너·테마가 하나도 없는 본문도 게시할 수 있다(철회 = 뺀 새 버전 — ADR-0075 ⑧)
 * </ul>
 */
public final class LayoutRules {

  /** 1차 범위 밖 문구(할인·쿠폰·특가·증정) + 근거 없는 약속. 대소문자·띄어쓰기 무시. */
  private static final Pattern PROMOTION =
      Pattern.compile(
          "할인|\\d+\\s*%\\s*off|쿠폰|특가|증정|사은품|1\\+1|2\\+1|최저가|신선\\s*보장|세일|\\bsale\\b",
          Pattern.CASE_INSENSITIVE);

  public static final List<String> PLACEMENTS = List.of("HOME_TOP", "HOME_MIDDLE");

  public record Banner(
      String bannerId,
      String placement,
      String imageId,
      String altText,
      String linkType,
      @Nullable String linkRef,
      int sortOrder,
      @Nullable Instant startsAt,
      @Nullable Instant endsAt) {}

  public record Theme(
      String sectionId,
      String kind,
      String title,
      @Nullable String subtitle,
      List<String> productIds,
      int sortOrder,
      @Nullable Instant startsAt,
      @Nullable Instant endsAt) {
    public Theme {
      productIds = List.copyOf(productIds);
    }
  }

  public record Body(List<Banner> banners, List<Theme> themes, @Nullable String note) {
    public Body {
      banners = banners == null ? List.of() : List.copyOf(banners);
      themes = themes == null ? List.of() : List.copyOf(themes);
    }

    public static Body empty() {
      return new Body(List.of(), List.of(), null);
    }
  }

  /** 검사에서 걸린 것(필드, 사유). */
  public record Problem(String field, String reason) {}

  private LayoutRules() {}

  /**
   * 제출 검사.
   *
   * @param displayable 진열할 수 있는 상품인가
   * @param imageExists 올린 배너 이미지인가
   */
  public static List<Problem> check(
      Body body, Predicate<String> displayable, Predicate<String> imageExists) {
    List<Problem> out = new ArrayList<>();
    Set<String> sections = new HashSet<>();
    for (Theme t : body.themes()) {
      sections.add(t.sectionId());
    }
    Set<String> bannerIds = new HashSet<>();
    for (int i = 0; i < body.banners().size(); i++) {
      Banner b = body.banners().get(i);
      String at = "banners[" + i + "]";
      if (!bannerIds.add(b.bannerId())) {
        out.add(new Problem(at + ".bannerId", "DUPLICATE_ID"));
      }
      if (!PLACEMENTS.contains(b.placement())) {
        out.add(new Problem(at + ".placement", "INVALID_PLACEMENT"));
      }
      if (!imageExists.test(b.imageId())) {
        out.add(new Problem(at + ".imageId", "IMAGE_NOT_FOUND"));
      }
      if (!linkOk(b, sections, displayable)) {
        out.add(new Problem(at + ".linkRef", "INVALID_LINK"));
      }
      if (!periodOk(b.startsAt(), b.endsAt())) {
        out.add(new Problem(at + ".endsAt", "INVALID_PERIOD"));
      }
      if (promotional(b.altText())) {
        out.add(new Problem(at + ".altText", "PROMOTION_NOT_ALLOWED"));
      }
    }
    Set<String> seenSections = new HashSet<>();
    for (int i = 0; i < body.themes().size(); i++) {
      Theme t = body.themes().get(i);
      String at = "themes[" + i + "]";
      if (!seenSections.add(t.sectionId())) {
        out.add(new Problem(at + ".sectionId", "DUPLICATE_ID"));
      }
      if (!periodOk(t.startsAt(), t.endsAt())) {
        out.add(new Problem(at + ".endsAt", "INVALID_PERIOD"));
      }
      if (promotional(t.title()) || promotional(t.subtitle())) {
        out.add(new Problem(at + ".title", "PROMOTION_NOT_ALLOWED"));
      }
      Set<String> seenProducts = new HashSet<>();
      for (String p : t.productIds()) {
        if (!seenProducts.add(p)) {
          out.add(new Problem(at + ".productIds", "DUPLICATE_PRODUCT"));
        } else if (!displayable.test(p)) {
          out.add(new Problem(p, "PRODUCT_NOT_PUBLISHED"));
        }
      }
    }
    return out;
  }

  public static boolean promotional(@Nullable String text) {
    return text != null && PROMOTION.matcher(text.replaceAll("\\s+", " ")).find();
  }

  private static boolean linkOk(Banner b, Set<String> sections, Predicate<String> displayable) {
    String ref = b.linkRef() == null ? "" : b.linkRef().strip();
    return switch (b.linkType()) {
      case "NONE" -> ref.isEmpty();
      case "SECTION" -> sections.contains(ref);
      case "PRODUCT" -> !ref.isEmpty() && displayable.test(ref);
      case "CATEGORY" -> !ref.isEmpty();
      default -> false;
    };
  }

  static boolean periodOk(@Nullable Instant startsAt, @Nullable Instant endsAt) {
    return startsAt == null || endsAt == null || startsAt.isBefore(endsAt);
  }

  /** 지금 보이는가(기간 안). */
  public static boolean active(@Nullable Instant startsAt, @Nullable Instant endsAt, Instant now) {
    return (startsAt == null || !now.isBefore(startsAt))
        && (endsAt == null || now.isBefore(endsAt));
  }

  /** 잠금 해시(본문 JSON 그대로 SHA-256). */
  public static String hash(String canonicalJson) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 이미지 형식(파일 앞부분) — JPEG·PNG·WEBP, 아니면 null. */
  public static @Nullable String imageType(byte[] b) {
    if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
      return "image/jpeg";
    }
    if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
      return "image/png";
    }
    if (b.length >= 12
        && b[0] == 'R'
        && b[1] == 'I'
        && b[2] == 'F'
        && b[3] == 'F'
        && b[8] == 'W'
        && b[9] == 'E'
        && b[10] == 'B'
        && b[11] == 'P') {
      return "image/webp";
    }
    return null;
  }

  public static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
}
