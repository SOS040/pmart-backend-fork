package com.pmart.gateway.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import java.net.URI;
import org.springframework.cloud.gateway.filter.headers.observation.DefaultGatewayObservationConvention;
import org.springframework.cloud.gateway.filter.headers.observation.GatewayContext;
import org.springframework.cloud.gateway.filter.headers.observation.GatewayObservationConvention;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 게이트웨이 추적(span)의 {@code http.uri} 에 조회 조건(? 뒤)을 남기지 않는다(2026-11-19 — 고객 앱 질문 "좌표를 URL 에 담아 보내 서버
 * 기록에 현재 위치가 남는다", 사용자 "응 진행해줘").
 *
 * <p>기본값(DefaultGatewayObservationConvention)은 요청 URI 전체를 싣는다 — {@code
 * /address-reverse-geocodes?lat=&lng=} 의 고객 좌표, 상담원 주문 찾기의 전화번호 같은 개인정보가 Zipkin(→ 로그 저장소)에 남았다.
 * 경로까지만(스킴·호스트·경로) 남기고 조회 조건·조각(#)은 뺀다. 다른 값(낮은 카드널리티 — 메서드·상태·경로 ID)은 기본 그대로.
 */
@Configuration(proxyBeanMethods = false)
public class TraceUriWithoutQuery {

  @Bean
  GatewayObservationConvention pathOnlyGatewayObservationConvention() {
    return new PathOnly();
  }

  static final String URI_KEY = "http.uri";

  /** 기본 규칙에서 {@code http.uri} 만 경로까지로. */
  static class PathOnly extends DefaultGatewayObservationConvention {
    @Override
    public KeyValues getHighCardinalityKeyValues(GatewayContext context) {
      // 키 이름은 기본 규칙(GatewayDocumentedObservation.HighCardinalityKeys.URI — 공개 아님)과 같은 "http.uri"
      return KeyValues.of(KeyValue.of(URI_KEY, withoutQuery(context.getRequest().getURI())));
    }
  }

  /** 스킴·호스트·포트·경로만(원래 인코딩 그대로). */
  static String withoutQuery(URI uri) {
    String s = uri.toString();
    int cut = s.length();
    int q = s.indexOf('?');
    int h = s.indexOf('#');
    if (q >= 0) {
      cut = q;
    }
    if (h >= 0 && h < cut) {
      cut = h;
    }
    return s.substring(0, cut);
  }
}
