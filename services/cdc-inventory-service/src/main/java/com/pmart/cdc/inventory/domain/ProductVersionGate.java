package com.pmart.cdc.inventory.domain;

/**
 * 투영 갱신 순서 규칙. 이벤트는 순서가 뒤바뀌어 올 수 있으므로(재시도·재처리) 저장된 상품 버전보다 오래된 이벤트는 버린다.
 *
 * <ul>
 *   <li>ProductVersionPublished(전체 스냅샷): 저장 버전 ≤ 들어온 버전이면 덮어쓴다. 같은 버전은 SkuIssued 로 먼저 만든 행을 채운다.
 *   <li>SkuIssued(식별만): 행이 없으면 만들고, 저장 버전 &lt; 들어온 버전일 때만 식별 열을 갱신한다(같은 버전의 스냅샷을 지우지 않음).
 * </ul>
 *
 * 같은 규칙을 저장소 SQL 의 {@code ON CONFLICT ... WHERE} 조건으로 적용한다(경합 시에도 원자적).
 */
public final class ProductVersionGate {

  private ProductVersionGate() {}

  public static boolean acceptsSnapshot(int storedVersion, int incomingVersion) {
    return storedVersion <= incomingVersion;
  }

  public static boolean acceptsIssued(int storedVersion, int incomingVersion) {
    return storedVersion < incomingVersion;
  }
}
