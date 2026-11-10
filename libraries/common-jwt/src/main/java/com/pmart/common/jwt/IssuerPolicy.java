package com.pmart.common.jwt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

/**
 * 믿는 발급자 하나의 규칙(ADR-0054). 발급자 표({@code pmart.security.issuers})의 한 줄.
 *
 * <ul>
 *   <li>사용자 종류는 <b>발급자가 정한다</b>: 토큰의 {@code typ} 가 있으면 그 발급자의 {@code user-types} 안이어야 하고, 없으면 그
 *       발급자가 낼 수 있는 종류가 하나일 때만 그 종류(예: Keycloak 직원 토큰 → OPERATOR). 그 밖은 무효 토큰(401).
 *   <li>주체 ID·역할·센터는 발급자마다 클레임 이름이 다를 수 있다(Keycloak: {@code pmart_operator_id}, {@code
 *       realm_access.roles}). 점(.)으로 중첩 클레임을 가리킨다.
 *   <li>{@code aud} 는 {@code audiences} 가 있을 때만 확인한다(하나라도 겹치면 통과).
 * </ul>
 *
 * 서비스(common-security, 서블릿)와 게이트웨이(WebFlux)가 이 규칙 하나를 쓴다.
 */
public final class IssuerPolicy {

  public static final String CLAIM_TYPE = "typ";
  public static final String CLAIM_ROLES = "roles";
  public static final String CLAIM_CENTERS = "centers";
  public static final String CLAIM_SOURCE = "src";

  /** 규약상 사용자 종류(common-security {@code PmartPrincipal.PrincipalType} 와 같은 이름). */
  public static final Set<String> USER_TYPES =
      Set.of("CUSTOMER", "RIDER", "OPERATOR", "SERVICE", "GUEST");

  /** 이 발급자가 낼 수 없는 종류를 주장하거나, 종류를 정할 수 없을 때의 오류 코드. */
  public static final String ERROR_TYPE_NOT_ALLOWED = "user_type_not_allowed_for_issuer";

  /** 발급자 규칙으로 읽은 호출 주체 값. */
  public record Claims(
      String subject,
      String userType,
      List<String> roles,
      List<String> centers,
      @Nullable String source) {}

  private final String id;
  private final String issuerUri;
  private final @Nullable String jwkSetUri;
  private final List<String> audiences;
  private final Set<String> userTypes;
  private final String subjectClaim;
  private final String rolesClaim;
  private final String centersClaim;

  public IssuerPolicy(IssuerSettings p) {
    String name = p.getId().isBlank() ? p.getIssuerUri() : p.getId();
    if (p.getIssuerUri() == null || p.getIssuerUri().isBlank()) {
      throw new IllegalStateException("pmart.security.issuers[" + name + "].issuer-uri 가 비었습니다.");
    }
    if (p.getUserTypes().isEmpty()) {
      throw new IllegalStateException("pmart.security.issuers[" + name + "].user-types 가 비었습니다.");
    }
    for (String t : p.getUserTypes()) {
      if (!USER_TYPES.contains(t)) {
        throw new IllegalStateException("pmart.security.issuers[" + name + "] 모르는 사용자 종류: " + t);
      }
    }
    this.id = name;
    this.issuerUri = p.getIssuerUri();
    this.jwkSetUri =
        p.getJwkSetUri() == null || p.getJwkSetUri().isBlank() ? null : p.getJwkSetUri();
    this.audiences = List.copyOf(p.getAudiences());
    this.userTypes = Set.copyOf(p.getUserTypes());
    this.subjectClaim = p.getSubjectClaim();
    this.rolesClaim = p.getRolesClaim();
    this.centersClaim = p.getCentersClaim();
  }

  /** 발급자 표 → {@code iss} 별 규칙. 같은 {@code issuer-uri} 가 두 번이면 설정 오류. */
  public static Map<String, IssuerPolicy> table(List<IssuerSettings> settings) {
    Map<String, IssuerPolicy> m = new LinkedHashMap<>();
    for (IssuerSettings s : settings) {
      IssuerPolicy p = new IssuerPolicy(s);
      if (m.put(p.issuerUri(), p) != null) {
        throw new IllegalStateException("발급자 표에 같은 issuer-uri 가 두 번 있습니다: " + p.issuerUri());
      }
    }
    return Map.copyOf(m);
  }

  public String id() {
    return id;
  }

  public String issuerUri() {
    return issuerUri;
  }

  /** 비어 있으면 앱의 기본 디코더로 서명을 확인한다. */
  public @Nullable String jwkSetUri() {
    return jwkSetUri;
  }

  public Set<String> userTypes() {
    return userTypes;
  }

  /** 서명 확인 뒤 이 발급자 규칙으로 보는 검증(만료·iss·aud·종류 짝·주체 있음). */
  public OAuth2TokenValidator<Jwt> validator() {
    List<OAuth2TokenValidator<Jwt>> list = new ArrayList<>();
    list.add(new JwtTimestampValidator());
    list.add(new JwtIssuerValidator(issuerUri));
    if (!audiences.isEmpty()) {
      list.add(
          new JwtClaimValidator<List<String>>(
              "aud", aud -> aud != null && aud.stream().anyMatch(audiences::contains)));
    }
    list.add(
        jwt ->
            resolveType(jwt) == null
                ? OAuth2TokenValidatorResult.failure(
                    new OAuth2Error(ERROR_TYPE_NOT_ALLOWED, "이 발급자가 낼 수 없는 사용자 종류입니다: " + id, null))
                : OAuth2TokenValidatorResult.success());
    list.add(
        jwt -> {
          String s = subject(jwt);
          return s == null || s.isBlank()
              ? OAuth2TokenValidatorResult.failure(
                  new OAuth2Error("invalid_token", "주체 클레임이 없습니다: " + subjectClaim, null))
              : OAuth2TokenValidatorResult.success();
        });
    return new DelegatingOAuth2TokenValidator<>(list);
  }

  /** 발급자 규칙에 맞는 사용자 종류, 맞지 않으면 null. */
  public @Nullable String resolveType(Jwt jwt) {
    String typ = jwt.getClaimAsString(CLAIM_TYPE);
    if (typ == null || typ.isBlank()) {
      return userTypes.size() == 1 ? userTypes.iterator().next() : null;
    }
    return userTypes.contains(typ) ? typ : null;
  }

  public @Nullable String subject(Jwt jwt) {
    Object v = claim(jwt.getClaims(), subjectClaim);
    return v == null ? null : v.toString();
  }

  /** 이 발급자 규칙으로 읽은 주체. {@link #validator()} 를 통과한 토큰에만 부른다. */
  public Claims claims(Jwt jwt) {
    String type = resolveType(jwt);
    String subject = subject(jwt);
    if (type == null || subject == null) {
      throw new IllegalStateException("검증되지 않은 토큰입니다: " + id);
    }
    return new Claims(
        subject,
        type,
        strings(claim(jwt.getClaims(), rolesClaim)),
        strings(claim(jwt.getClaims(), centersClaim)),
        jwt.getClaimAsString(CLAIM_SOURCE));
  }

  static @Nullable Object claim(Map<String, Object> claims, String path) {
    Object cur = claims;
    for (String part : path.split("\\.")) {
      if (!(cur instanceof Map<?, ?> m)) {
        return null;
      }
      cur = m.get(part);
    }
    return cur;
  }

  static List<String> strings(@Nullable Object v) {
    if (v instanceof Collection<?> c) {
      return c.stream().map(String::valueOf).toList();
    }
    if (v instanceof String s && !s.isBlank()) {
      return List.of(s);
    }
    return List.of();
  }
}
