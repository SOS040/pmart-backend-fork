package com.pmart.hr.application;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.hr.*} — 직원 등록·계정 발급(ADR-0056). 값을 바꾸면 재시작한다.
 *
 * @param mailDomain 사내 메일 도메인(pmart.com) — 주소 = 메일 ID @ 도메인, Keycloak 로그인 아이디도 이 주소(D2)
 * @param reservedMailIds 직원에게 줄 수 없는 메일 ID(postmaster·no-reply 등)
 * @param zone 날짜 판정(사번 연도·계약 종료일) 시간대
 * @param keycloak Keycloak Admin API(서비스 계정 클라이언트 hr-provisioner)
 * @param mailProvisioner 사서함 프로비저닝 사이드카
 * @param provisioning 계정 발급 재시도
 * @param employeeAuthBaseUrl employee-auth-service 주소(권한 요청 검사 — 역할 목록·지금 권한 내부 조회, 2026-11-17
 *     HR-2). 기본 {@code http://employee-auth-service:8511}(LAN 은 Eureka 가 이름을 푼다), local 은 설정 저장소에
 *     {@code http://localhost:8511}
 */
@ConfigurationProperties(prefix = "pmart.hr")
public record HrProperties(
    String mailDomain,
    List<String> reservedMailIds,
    ZoneId zone,
    Keycloak keycloak,
    MailProvisioner mailProvisioner,
    Provisioning provisioning,
    String employeeAuthBaseUrl) {

  public HrProperties {
    mailDomain = mailDomain == null || mailDomain.isBlank() ? "pmart.com" : mailDomain;
    reservedMailIds =
        reservedMailIds == null || reservedMailIds.isEmpty()
            ? List.of(
                "postmaster",
                "no-reply",
                "noreply",
                "abuse",
                "root",
                "hostmaster",
                "webmaster",
                "mailer-daemon",
                "security")
            : List.copyOf(reservedMailIds);
    zone = zone == null ? ZoneId.of("Asia/Seoul") : zone;
    keycloak = keycloak == null ? new Keycloak(null, null, null, null, null, null) : keycloak;
    mailProvisioner = mailProvisioner == null ? new MailProvisioner(null, null) : mailProvisioner;
    provisioning = provisioning == null ? new Provisioning(0, null, null, null) : provisioning;
    employeeAuthBaseUrl =
        employeeAuthBaseUrl == null || employeeAuthBaseUrl.isBlank()
            ? "http://employee-auth-service:8511"
            : employeeAuthBaseUrl.replaceAll("/+$", "");
  }

  /**
   * @param adminBaseUrl Keycloak 서버 주소(Admin API·토큰 — 내부 주소, 게이트웨이 /iam 은 관리 경로를 막는다)
   * @param realm 직원 Realm(pmart-staff)
   * @param clientId 서비스 계정 클라이언트(hr-provisioner)
   * @param clientSecret 비밀(설정 {cipher}, 로그 금지)
   * @param timeout 호출 연결·응답 한도
   * @param inviteLifespan 비밀번호 설정 링크 유효 시간
   */
  public record Keycloak(
      @Nullable String adminBaseUrl,
      @Nullable String realm,
      @Nullable String clientId,
      @Nullable String clientSecret,
      @Nullable Duration timeout,
      @Nullable Duration inviteLifespan) {
    public Keycloak {
      realm = realm == null || realm.isBlank() ? "pmart-staff" : realm;
      clientId = clientId == null || clientId.isBlank() ? "hr-provisioner" : clientId;
      timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
      inviteLifespan = inviteLifespan == null ? Duration.ofDays(3) : inviteLifespan;
    }

    public boolean configured() {
      return adminBaseUrl != null
          && !adminBaseUrl.isBlank()
          && clientSecret != null
          && !clientSecret.isBlank();
    }
  }

  /**
   * @param baseUrl mail-provisioner 주소(PC 5 내부, LAN 포트 없음)
   * @param timeout 호출 연결·응답 한도
   */
  public record MailProvisioner(@Nullable String baseUrl, @Nullable Duration timeout) {
    public MailProvisioner {
      timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
    }

    public boolean configured() {
      return baseUrl != null && !baseUrl.isBlank();
    }
  }

  /**
   * @param maxAttempts 이 횟수만큼 실패하면 FAILED(보상)
   * @param firstBackoff 첫 재시도 간격(실패마다 두 배)
   * @param maxBackoff 재시도 간격 상한
   * @param pollInterval 작업기 주기
   */
  public record Provisioning(
      int maxAttempts,
      @Nullable Duration firstBackoff,
      @Nullable Duration maxBackoff,
      @Nullable Duration pollInterval) {
    public Provisioning {
      maxAttempts = maxAttempts <= 0 ? 8 : maxAttempts;
      firstBackoff = firstBackoff == null ? Duration.ofSeconds(5) : firstBackoff;
      maxBackoff = maxBackoff == null ? Duration.ofMinutes(10) : maxBackoff;
      pollInterval = pollInterval == null ? Duration.ofSeconds(5) : pollInterval;
    }

    /** n 번째 실패 뒤 기다릴 시간. */
    public Duration backoff(int failures) {
      long seconds =
          Math.min(
              Math.max(1, firstBackoff.toSeconds()) << Math.min(failures - 1, 16),
              maxBackoff.toSeconds());
      return Duration.ofSeconds(seconds);
    }
  }
}
