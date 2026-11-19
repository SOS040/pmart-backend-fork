package com.pmart.search.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 자모 나누기(2026-11-20 ADR-0073 오타 교정). */
class HangulTest {

  @Test
  void decomposesSyllablesToJamo() {
    assertThat(Hangul.jamo("두부")).isEqualTo("ㄷㅜㅂㅜ");
    assertThat(Hangul.jamo("두뷰")).isEqualTo("ㄷㅜㅂㅠ");
    assertThat(Hangul.jamo("닭")).isEqualTo("ㄷㅏㄺ");
    assertThat(Hangul.jamo("우유1L")).isEqualTo("ㅇㅜㅇㅠ1L");
  }

  @Test
  void onlyAllHangulWordsAreCorrected() {
    assertThat(Hangul.isHangulWord("두부")).isTrue();
    assertThat(Hangul.isHangulWord("ㄷㅂ")).isFalse();
    assertThat(Hangul.isHangulWord("우유1L")).isFalse();
    assertThat(Hangul.isHangulWord("")).isFalse();
    assertThat(Hangul.fuzziness("ㄷㅜㅂㅜ")).isEqualTo(1);
    assertThat(Hangul.fuzziness("ㅅㅐㅇㅋㅡㄹㅣㅁ")).isEqualTo(2);
  }
}
