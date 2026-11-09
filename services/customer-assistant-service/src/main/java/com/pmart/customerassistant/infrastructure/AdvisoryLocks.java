package com.pmart.customerassistant.infrastructure;

/**
 * PostgreSQL advisory lock 키. 두 잠금은 키가 다르고 목적도 다르다.
 *
 * <ul>
 *   <li>{@link #INGEST}: <b>세션</b> 잠금. 색인 작업 전체 동안 한 연결이 쥔다 — 동시 색인 차단. 질의는 이 잠금을 보지 않으므로 재색인 중에도
 *       기존 ACTIVE 스냅숏으로 답한다.
 *   <li>{@link #SNAPSHOT}: <b>트랜잭션</b> 잠금. 짧게만 쥔다 — 요청의 스냅숏 고정(공유) vs 활성 전환·스냅숏 삭제(배타).
 * </ul>
 */
public final class AdvisoryLocks {

  /** "AIIN" */
  public static final long INGEST = 0x4149494EL;

  /** "AISN" */
  public static final long SNAPSHOT = 0x4149534EL;

  private AdvisoryLocks() {}
}
