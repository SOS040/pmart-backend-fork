package com.pmart.promotion.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.promotion.domain.PromotionRules;
import com.pmart.promotion.infrastructure.CouponGrantStore;
import com.pmart.promotion.infrastructure.PromotionStore;
import com.pmart.promotion.infrastructure.PromotionStore.PromotionRow;
import java.time.Clock;
import java.time.Instant;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 가입 축하 쿠폰(ADR-0082 결정 4 SIGNUP, 2026-11-21 5단계). customer-service CustomerIdentityVerified(stage
 * SIGNUP — 로그인 단계 휴대폰 확인 = 가입)를 받아 지금 진행 중인 SIGNUP 쿠폰 행사마다 한 장씩 준다. 같은 번호(signupKey — 번호의 HMAC)에는
 * 다시 주지 않는다(재가입 포함 — 키는 탈퇴 뒤에도 남기고, 지급 후 {@code signup-grant-retention}(5년, 처리방침)이 지나면 지운다). 행사가
 * 하나도 없거나 모두 한도·소진이면 키를 잡지 않는다. inbox 트랜잭션 안.
 */
@Service
public class SignupCoupons {

  private static final Logger log = LoggerFactory.getLogger(SignupCoupons.class);
  private static final Pattern KEY = Pattern.compile("[0-9a-f]{64}");

  private final CouponGrantStore grants;
  private final PromotionStore promotions;
  private final CouponService coupons;
  private final Clock clock;
  private final PromotionProperties props;

  public SignupCoupons(
      CouponGrantStore grants,
      PromotionStore promotions,
      CouponService coupons,
      Clock clock,
      PromotionProperties props) {
    this.props = props;
    this.grants = grants;
    this.promotions = promotions;
    this.coupons = coupons;
    this.clock = clock;
  }

  /** 하루 한 번(04:50 Asia/Seoul) 보관 기간 지난 지급 키 파기. 설정이 없으면 지우지 않는다(기본값으로 대신하지 않음). */
  @org.springframework.scheduling.annotation.Scheduled(cron = "0 50 4 * * *", zone = "Asia/Seoul")
  public void purgeExpiredKeys() {
    java.time.Duration keep = props.signupGrantRetention();
    if (keep == null) {
      return;
    }
    int n = grants.purgeSignupGrants(clock.instant().minus(keep));
    if (n > 0) {
      log.info("보관 기간 지난 가입 쿠폰 지급 키 {}건 파기", n);
    }
  }

  public static boolean wanted(EventEnvelope e) {
    return "CustomerIdentityVerified".equals(e.eventType())
        && "SIGNUP".equals(e.payload().path("stage").asString(null));
  }

  /** 준 쿠폰 수. */
  public int apply(EventEnvelope e) {
    JsonNode p = e.payload();
    String accountId = p.path("accountId").asString(null);
    String key = p.path("signupKey").asString(null);
    if (accountId == null || key == null || !KEY.matcher(key).matches()) {
      log.warn("가입 확인 사건 {} 에 계정·가입 키가 없다 — 가입 쿠폰 없음", e.eventId());
      return 0;
    }
    Instant now = clock.instant();
    if (!grants.claimSignup(key, accountId, now)) {
      return 0; // 이 번호는 이미 가입 쿠폰을 받았다
    }
    int issued = 0;
    for (String id : grants.runningCouponPromotions("SIGNUP", now)) {
      PromotionRow promo = promotions.lock(id).orElse(null);
      if (promo == null
          || !"RUNNING"
              .equals(
                  PromotionRules.phase(
                      promo.state(), promo.startsAt(), promo.endsAt(), promo.endedAt(), now))) {
        continue;
      }
      String r = coupons.issue(promo, accountId, "SIGNUP", "system", "가입 축하");
      if (!r.startsWith("!")) {
        issued++;
      }
    }
    if (issued == 0) {
      grants.releaseSignup(key); // 줄 행사가 없었다 — 키를 잡아 두지 않는다
    }
    return issued;
  }
}
