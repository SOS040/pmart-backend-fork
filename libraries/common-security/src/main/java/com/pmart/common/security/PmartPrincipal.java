package com.pmart.common.security;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * JWT 에서 뽑은 호출 주체. 개인정보가 아닌 식별자·역할·센터 범위만.
 *
 * <p>클레임 규약(auth-service 발급): {@code sub}=주체 ID, {@code typ}=CUSTOMER|RIDER|OPERATOR|SERVICE,
 * {@code roles}=[...], {@code centers}=[센터 코드...](운영자·현장 직원의 접근 범위), {@code src}=서비스 토큰의 서비스명.
 *
 * @param subject 주체 ID (accountId / riderId / operatorId / 서비스명)
 * @param type 주체 유형
 * @param roles 역할 (ROLE_ 접두사 없는 원래 이름)
 * @param centerScope 접근 가능한 센터 코드. 비어 있으면 제한 없음이 아니라 "센터 범위 없음"이다.
 */
public record PmartPrincipal(
    String subject,
    PrincipalType type,
    List<String> roles,
    List<String> centerScope,
    @Nullable String sourceSystem) {

  public enum PrincipalType {
    CUSTOMER,
    RIDER,
    OPERATOR,
    SERVICE,
    /**
     * 비회원 세션(auth-service guest-sessions). 기본은 모든 API 거절 — 서비스가 pmart.security.guest-paths 로 연 경로만.
     */
    GUEST,
    UNKNOWN
  }

  public static final String CLAIM_TYPE = "typ";
  public static final String CLAIM_ROLES = "roles";
  public static final String CLAIM_CENTERS = "centers";
  public static final String CLAIM_SOURCE = "src";

  public boolean hasRole(String role) {
    return roles.contains(role);
  }

  public boolean canAccessCenter(String centerId) {
    return centerScope.contains(centerId);
  }

  public boolean isService() {
    return type == PrincipalType.SERVICE;
  }

  public static PmartPrincipal fromJwt(Jwt jwt) {
    String typ = jwt.getClaimAsString(CLAIM_TYPE);
    PrincipalType type;
    try {
      type = typ == null ? PrincipalType.UNKNOWN : PrincipalType.valueOf(typ);
    } catch (IllegalArgumentException e) {
      type = PrincipalType.UNKNOWN;
    }
    List<String> roles = jwt.getClaimAsStringList(CLAIM_ROLES);
    List<String> centers = jwt.getClaimAsStringList(CLAIM_CENTERS);
    return new PmartPrincipal(
        jwt.getSubject(),
        type,
        roles == null ? List.of() : List.copyOf(roles),
        centers == null ? List.of() : List.copyOf(centers),
        jwt.getClaimAsString(CLAIM_SOURCE));
  }

  /** 현재 요청의 주체. 인증되지 않았거나 JWT 가 아니면 empty. */
  public static Optional<PmartPrincipal> current() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth instanceof JwtAuthenticationToken token) {
      return Optional.of(fromJwt(token.getToken()));
    }
    return Optional.empty();
  }

  /** 현재 요청의 원본 bearer 토큰(서비스 간 전파용). */
  public static Optional<String> currentTokenValue() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth instanceof JwtAuthenticationToken token) {
      return Optional.of(token.getToken().getTokenValue());
    }
    return Optional.empty();
  }
}
