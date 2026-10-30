package com.pmart.auth.application;

import com.pmart.auth.infrastructure.OperatorStore;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 비회원 세션(typ=GUEST). 기기마다 새 비회원 주체(GST-…)를 만들고 세션·토큰 쌍을 준다. 개인정보를 받지 않는다.
 *
 * <ul>
 *   <li>세션은 X-Device-Id 에 묶인다(갱신도 같은 기기만 — SessionService).
 *   <li>같은 Idempotency-Key 재전송은 같은 비회원 주체로 새 세션을 연다(refresh token 원문은 저장하지 않아 재생할 수 없다).
 *   <li>비회원 토큰은 각 서비스가 guest-paths 로 연 API 에서만 통한다(common-security).
 * </ul>
 */
@Service
public class GuestSessionService {

  public static final String TYPE = "GUEST";

  private static final Logger log = LoggerFactory.getLogger(GuestSessionService.class);

  public record Started(String guestId, SessionTokens.Tokens tokens) {}

  private final OperatorStore sessions;
  private final SessionTokens tokens;
  private final GuestProperties props;
  private final RequestIdempotency idempotency;
  private final TransactionTemplate tx;
  private final Clock clock;

  public GuestSessionService(
      OperatorStore sessions,
      SessionTokens tokens,
      GuestProperties props,
      RequestIdempotency idempotency,
      TransactionTemplate tx,
      Clock clock) {
    this.sessions = sessions;
    this.tokens = tokens;
    this.props = props;
    this.idempotency = idempotency;
    this.tx = tx;
    this.clock = clock;
  }

  public Started start(String requestId, String sourceSystem, String deviceId) {
    if (deviceId == null || deviceId.isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "X-Device-Id 가 필요합니다.");
    }
    String guestId =
        idempotency
            .execute(
                new RequestKey(
                    sourceSystem, requestId, "startGuestSession", RequestIdempotency.ANONYMOUS),
                Map.of("deviceId", deviceId),
                String.class,
                () -> "GST-" + UUID.randomUUID())
            .value();
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    SessionTokens.Tokens issued =
        tx.execute(
            status -> {
              String sessionId = "SES-" + UUID.randomUUID();
              Instant expires = now.plus(props.sessionMaxAge());
              sessions.insertSession(
                  sessionId, guestId, TYPE, deviceId, sourceSystem, now, expires);
              return tokens.issue(
                  new SessionTokens.Principal(guestId, TYPE, List.of(), List.of()),
                  sessionId,
                  expires,
                  props.accessTokenTtl(),
                  props.refreshTokenTtl(),
                  now);
            });
    log.info("비회원 세션 시작 {}", guestId);
    return new Started(guestId, issued);
  }
}
