package com.pmart.customerassistant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TableTextTest {

  @Test
  void tableRowsBecomeLabeledLinesAndOtherTextStays() {
    String md =
        """
        ## 배달팁은 얼마인가요?

        | 배송 방식 | 배달팁 |
        |---|---|
        | 한집배달 | 4,000원 |
        | 알뜰배달·예약배달 | **3,000원** |

        - 첫 주문이면 무료예요.""";
    assertThat(TableText.linearize(md))
        .isEqualTo(
            """
            ## 배달팁은 얼마인가요?

            - 배송 방식: 한집배달, 배달팁: 4,000원
            - 배송 방식: 알뜰배달·예약배달, 배달팁: 3,000원

            - 첫 주문이면 무료예요.""");
  }

  @Test
  void pipeTextWithoutSeparatorIsNotATable() {
    assertThat(TableText.linearize("| 그냥 문장 |\n다음 줄")).isEqualTo("| 그냥 문장 |\n다음 줄");
  }
}
