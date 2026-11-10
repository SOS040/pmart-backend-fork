package com.pmart.common.jwt;

import java.util.List;

/**
 * 발급자 표({@code pmart.security.issuers})의 한 줄(ADR-0054). 서비스는 common-security 가, 게이트웨이는 자기 설정 클래스가 이
 * 형태로 받는다 — 같은 설정 키를 쓴다.
 */
public class IssuerSettings {

  /** 이름(customer, staff, rider 등) — 로그·주체 표시용. */
  private String id = "";

  /** 토큰 {@code iss} 와 정확히 같아야 한다. */
  private String issuerUri = "";

  /** 서명 공개키 주소. 비우면 앱의 기본 디코더를 쓴다(auth-service 자신, 시험 키 등). */
  private String jwkSetUri = "";

  /** 비어 있지 않으면 토큰 {@code aud} 가 이 중 하나를 포함해야 한다. */
  private List<String> audiences = List.of();

  /** 이 발급자가 낼 수 있는 사용자 종류({@link IssuerPolicy#USER_TYPES}). */
  private List<String> userTypes = List.of();

  /**
   * 사용자 종류 클레임. 비우면 토큰의 클레임을 보지 않고 발급자가 낼 수 있는 종류(하나여야 함)로 정한다. Keycloak 은 {@code typ} 에 "Bearer" 를
   * 넣으므로 직원 발급자는 비운다(2026-11-10 실제 토큰으로 확인).
   */
  private String typeClaim = IssuerPolicy.CLAIM_TYPE;

  /** 주체 ID 클레임(점으로 중첩). Keycloak 직원은 {@code pmart_operator_id}. */
  private String subjectClaim = "sub";

  /** 역할 클레임. Keycloak 은 {@code realm_access.roles}. */
  private String rolesClaim = IssuerPolicy.CLAIM_ROLES;

  /** 센터 범위 클레임. */
  private String centersClaim = IssuerPolicy.CLAIM_CENTERS;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getIssuerUri() {
    return issuerUri;
  }

  public void setIssuerUri(String issuerUri) {
    this.issuerUri = issuerUri;
  }

  public String getJwkSetUri() {
    return jwkSetUri;
  }

  public void setJwkSetUri(String jwkSetUri) {
    this.jwkSetUri = jwkSetUri;
  }

  public List<String> getAudiences() {
    return audiences;
  }

  public void setAudiences(List<String> audiences) {
    this.audiences = audiences;
  }

  public List<String> getUserTypes() {
    return userTypes;
  }

  public void setUserTypes(List<String> userTypes) {
    this.userTypes = userTypes;
  }

  public String getTypeClaim() {
    return typeClaim;
  }

  public void setTypeClaim(String typeClaim) {
    this.typeClaim = typeClaim;
  }

  public String getSubjectClaim() {
    return subjectClaim;
  }

  public void setSubjectClaim(String subjectClaim) {
    this.subjectClaim = subjectClaim;
  }

  public String getRolesClaim() {
    return rolesClaim;
  }

  public void setRolesClaim(String rolesClaim) {
    this.rolesClaim = rolesClaim;
  }

  public String getCentersClaim() {
    return centersClaim;
  }

  public void setCentersClaim(String centersClaim) {
    this.centersClaim = centersClaim;
  }
}
