package com.pmart.product.content.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.product.content.domain.OriginRules.OriginType;
import java.util.List;
import org.junit.jupiter.api.Test;

/** LAN 게시 콘텐츠의 실제 "원산지:" 줄 모양(2026-11-22). */
class OriginRulesTest {

  @Test
  void allDomesticValuesAreDomestic() {
    assertThat(OriginRules.classify("국내산")).isEqualTo(OriginType.DOMESTIC);
    assertThat(OriginRules.classify("쌀: 국내산 100%")).isEqualTo(OriginType.DOMESTIC);
    assertThat(OriginRules.classify("멥쌀·흑미·찹쌀: 국산")).isEqualTo(OriginType.DOMESTIC);
    assertThat(OriginRules.classify("원산지: 한국")).isEqualTo(OriginType.DOMESTIC);
    assertThat(OriginRules.classify("닭고기: 국내산, 양파: 국산")).isEqualTo(OriginType.DOMESTIC);
    assertThat(OriginRules.classify("제주")).isEqualTo(OriginType.DOMESTIC);
  }

  @Test
  void anyForeignValueIsImported() {
    assertThat(OriginRules.classify("쌀: 외국산, 찰현미·곤드레: 국산")).isEqualTo(OriginType.IMPORTED);
    assertThat(OriginRules.classify("배추·고추·쌀: 국산, 돼지고기(삼겹살): 브라질산")).isEqualTo(OriginType.IMPORTED);
    assertThat(OriginRules.classify("프랑스 (제조원: Häagen-Dazs Arras, France)"))
        .isEqualTo(OriginType.IMPORTED);
    assertThat(OriginRules.classify("미국")).isEqualTo(OriginType.IMPORTED);
    assertThat(OriginRules.classify("밀: 미국산·호주산, 팜유: 말레이시아산")).isEqualTo(OriginType.IMPORTED);
    assertThat(OriginRules.classify("유크림: 외국산(미국, 이탈리아, 스페인 등)")).isEqualTo(OriginType.IMPORTED);
    assertThat(OriginRules.classify("가다랑어: 원양산, 카놀라유: 외국산(캐나다, 호주)"))
        .isEqualTo(OriginType.IMPORTED);
  }

  @Test
  void undecidableIsNull() {
    assertThat(OriginRules.classify("가다랑어: 원양산")).isNull();
    assertThat(OriginRules.classify("Made in USA")).isNull();
  }

  @Test
  void readsFirstOriginNoticeAndStripsRepeatedPrefix() {
    var o = OriginRules.of(List.of("용량: 180g", "원산지: 원산지: 대만", "원산지: 국산")).orElseThrow();
    assertThat(o.text()).isEqualTo("대만");
    assertThat(o.type()).isEqualTo(OriginType.IMPORTED);
    assertThat(OriginRules.of(List.of("용량: 180g"))).isEmpty();
    assertThat(OriginRules.of(List.of("원산지: "))).isEmpty();
  }
}
