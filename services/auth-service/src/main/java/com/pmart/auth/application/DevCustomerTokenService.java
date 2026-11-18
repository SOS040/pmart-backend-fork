package com.pmart.auth.application;

import com.pmart.auth.infrastructure.CustomerStore;
import com.pmart.auth.infrastructure.SessionStore;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [LAN 시험 전용] 개발용 고객 토큰(2026-11-19, MFC 시뮬레이터 R-21 — 사용자 "응 먼저 회신남기고 제안대로해줘", 계약 0.3.0). {@code
 * pmart.auth.dev-customer-token.enabled=true} 일 때만 빈이 생긴다(경로도 — 운영 설정에는 없다).
 *
 * <ul>
 *   <li>새 계정을 만들지 않는다. 허용 목록의 기존 고객(auth {@code customer_account} 에 있고 ACTIVE)만.
 *   <li>짧은 세션(ttl, 기본 15분)을 열고 access token 만 준다. 세션 행(계정·기기·X-Source-System·시각)이 발급 기록이다.
 *   <li>토큰은 로그에 남기지 않는다.
 * </ul>
 */
@Service
@ConditionalOnProperty(
    prefix = "pmart.auth.dev-customer-token",
    name = "enabled",
    havingValue = "true")
@EnableConfigurationProperties(DevCustomerTokenService.Props.class)
public class DevCustomerTokenService {

  private static final Logger log = LoggerFactory.getLogger(DevCustomerTokenService.class);
  private static final String TYPE = "CUSTOMER";

  /**
   * {@code pmart.auth.dev-customer-token.*}.
   *
   * @param allowedAccounts 토큰을 줄 수 있는 기존 고객 계정 ID(시험 고객·PC 1 지정 고객). 비면 모두 403
   * @param ttl 세션·토큰 수명(기본 15분)
   */
  @ConfigurationProperties(prefix = "pmart.auth.dev-customer-token")
  public record Props(
      boolean enabled, @Nullable List<String> allowedAccounts, @Nullable Duration ttl) {
    public Duration lifetime() {
      return ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofMinutes(15) : ttl;
    }

    public boolean allowed(String accountId) {
      return allowedAccounts != null && allowedAccounts.contains(accountId);
    }
  }

  public record Issued(String accountId, String accessToken, Instant expiresAt, String sessionId) {}

  private final Props props;
  private final CustomerStore customers;
  private final SessionStore sessions;
  private final SessionTokens tokens;
  private final TransactionTemplate tx;
  private final Clock clock;

  public DevCustomerTokenService(
      Props props,
      CustomerStore customers,
      SessionStore sessions,
      SessionTokens tokens,
      TransactionTemplate tx,
      Clock clock) {
    this.props = props;
    this.customers = customers;
    this.sessions = sessions;
    this.tokens = tokens;
    this.tx = tx;
    this.clock = clock;
  }

  public Issued issue(String accountId, String deviceId, String sourceSystem) {
    String id = accountId == null ? "" : accountId.strip();
    if (id.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "accountId 가 없습니다.");
    }
    if (!props.allowed(id)) {
      log.warn("개발용 고객 토큰 거절(허용 목록 밖) source={}", sourceSystem);
      throw new PmartException(
          "DEV_CUSTOMER_NOT_ALLOWED",
          HttpStatus.FORBIDDEN,
          "개발용 토큰을 줄 수 없는 고객입니다(허용 목록 밖).",
          false,
          List.of());
    }
    String state =
        customers
            .accountState(id)
            .orElseThrow(() -> PmartException.notFound("ACCOUNT_NOT_FOUND", "고객 계정이 없습니다."));
    if (!"ACTIVE".equals(state)) {
      throw new PmartException(
          "ACCOUNT_NOT_AVAILABLE",
          HttpStatus.FORBIDDEN,
          "쓸 수 없는 계정입니다(" + state + ").",
          false,
          List.of());
    }
    Duration ttl = props.lifetime();
    Issued out =
        Objects.requireNonNull(
            tx.execute(
                s -> {
                  Instant now = clock.instant();
                  String sessionId = "SES-" + UUID.randomUUID();
                  Instant expires = now.plus(ttl);
                  sessions.insertSession(sessionId, id, TYPE, deviceId, sourceSystem, now, expires);
                  SessionTokens.Tokens t =
                      tokens.issue(
                          new SessionTokens.Principal(id, TYPE, List.of(), List.of()),
                          sessionId,
                          expires,
                          ttl,
                          ttl,
                          now);
                  return new Issued(id, t.accessToken(), t.accessTokenExpiresAt(), sessionId);
                }));
    log.info("개발용 고객 토큰 발급 account={} session={} source={}", id, out.sessionId(), sourceSystem);
    return out;
  }
}
