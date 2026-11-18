package com.pmart.customer.application;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

/**
 * {@code pmart.customer.*}.
 *
 * @param addressLimit 주소록 최대 건수(정책값). 비우면 주소 추가는 CONFIG_REQUIRED
 * @param serviceabilityBaseUrl delivery-serviceability-service 주소(Service DNS)
 * @param consents 약관 동의(CU-1, 2026-11-17) — 항목별 현행 버전. 비우면 동의 조회·변경은 CONFIG_REQUIRED
 *     <p>실행 중 갱신(ADR-0033): {@code @RefreshScope} — Bus 갱신 뒤 다음 사용 때 새 값으로 다시 만든다(record 는 final 이라
 *     refresh 프록시를 못 만들어 클래스). 쓰는 쪽은 호출마다 읽는다. 주소(*BaseUrl)는 클라이언트를 만들 때 한 번 읽으므로 바꾸면 재시작. 약관 버전은
 *     실행 중 바꿔도 다음 조회부터 적용된다(다음 로그인에서 필수 항목을 다시 묻는다).
 */
@RefreshScope
@ConfigurationProperties(prefix = "pmart.customer")
public class CustomerProperties {

  private final @Nullable Integer addressLimit;
  private final String serviceabilityBaseUrl;
  private final Consents consents;

  public CustomerProperties(
      @Nullable Integer addressLimit, String serviceabilityBaseUrl, @Nullable Consents consents) {
    serviceabilityBaseUrl =
        serviceabilityBaseUrl == null || serviceabilityBaseUrl.isBlank()
            ? "http://delivery-serviceability-service:8401"
            : serviceabilityBaseUrl;
    this.addressLimit = addressLimit;
    this.serviceabilityBaseUrl = serviceabilityBaseUrl;
    this.consents = consents == null ? new Consents(null, null, null, null) : consents;
  }

  public @Nullable Integer addressLimit() {
    return addressLimit;
  }

  public String serviceabilityBaseUrl() {
    return serviceabilityBaseUrl;
  }

  public Consents consents() {
    return consents;
  }

  /**
   * @param versions 항목별 현행 약관 버전(문자열, 32자까지). 바꾸면 그 항목을 다시 받는다
   * @param purchaseGate 구매 가능 여부(내부 계정 상태 {@code purchasable})에 필수 동의를 넣을지 — <b>기능 스위치(정책값 아님)</b>.
   *     고객 앱이 동의 흐름을 반영하기 전에 켜면 기존 회원 전부가 주문을 못 하므로 LAN 은 {@code false} 로 둔다(2026-11-17). 켤 때는 auth
   *     {@code require-consent} 와 함께 {@code true}. 설정 저장소에는 늘 명시하고, 값이 없으면 꺼짐(지금까지의 동작 — 코드가 설정보다
   *     먼저 배포돼도 주문이 막히지 않게)
   */
  public record Consents(
      @Nullable Versions versions,
      @Nullable Boolean purchaseGate,
      @Nullable String senderName,
      java.util.@Nullable Map<String, String> documents) {
    public boolean purchaseGateOn() {
      return Boolean.TRUE.equals(purchaseGate);
    }

    /**
     * 약관 본문 주소(2026-11-19 BR-59 — {@code pmart.customer.consents.documents.<항목 키>}, 키는 versions 와
     * 같은 이름). 없으면 null(지어내지 않는다).
     */
    public @Nullable String document(String key) {
      if (documents == null) {
        return null;
      }
      String v = documents.get(key);
      return v == null || v.isBlank() ? null : v.strip();
    }

    /** 수신 동의 처리 결과 안내의 전송자 이름(2026-11-19 BR-59). 없으면 null. */
    public @Nullable String sender() {
      return senderName == null || senderName.isBlank() ? null : senderName.strip();
    }
  }

  /** {@code pmart.customer.consents.versions.*} — 계약 ConsentType 마다 하나. */
  public record Versions(
      @Nullable String termsOfService,
      @Nullable String privacyPolicy,
      @Nullable String ageOver14,
      @Nullable String marketing,
      @Nullable String locationBasedService) {}
}
