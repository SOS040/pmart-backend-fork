package com.pmart.mfc.inventory.domain;

/**
 * 재고 단위 행의 키와 수량 기호 기여(ADR-0040 결정 1, MFC-business-logic §3 상태 축). 품질과 업무 용도는 서로 다른 축이고, 예약은 단위 행의
 * 상태가 아니다.
 */
public final class StockUnit {

  private StockUnit() {}

  /** 품질 상태. */
  public enum Quality {
    PENDING_INSPECTION,
    NORMAL,
    QUARANTINED,
    DISPOSAL_PENDING
  }

  /** 업무 용도. */
  public enum Work {
    UNSTOWED,
    STORED,
    PICKED,
    PACKED,
    CANCEL_RETURN_PENDING
  }

  /** 단위 행 키(센터 안). */
  public record Key(String locationCode, String sku, String lotId, Quality quality, Work work) {

    /** Q 에 드는가: 격리·폐기 대기. */
    public boolean quarantined() {
      return quality == Quality.QUARANTINED || quality == Quality.DISPOSAL_PENDING;
    }

    /** E 에 드는가: 정상이고 적치 뒤(선반·피킹·포장). 검수 대기·적치 대기·취소 복귀 대기는 E 밖. */
    public boolean sellable() {
      return quality == Quality.NORMAL
          && (work == Work.STORED || work == Work.PICKED || work == Work.PACKED);
    }

    public Key with(Quality q, Work w) {
      return new Key(locationCode, sku, lotId, q, w);
    }

    public Key at(String location, Work w) {
      return new Key(location, sku, lotId, quality, w);
    }
  }

  /** 단위 행 하나의 증감. */
  public record Change(Key key, int delta) {

    public int dh() {
      return delta;
    }

    public int dq() {
      return key.quarantined() ? delta : 0;
    }

    public int de() {
      return key.sellable() ? delta : 0;
    }
  }
}
