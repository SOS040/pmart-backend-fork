package com.pmart.cdc.outbound.application;

/**
 * 송장(TRF) 라벨 스캔 값(요청 CO-1, 2026-11-26) — 라벨 스캔 형식 {@code {유형}:{id}:{버전}}(포장 {@code PKG:} 와 같은 규칙).
 * MFC PDA 는 trfId 만 비교한다(버전은 인쇄 때 값).
 */
public final class TransferLabels {

  private TransferLabels() {}

  public static String value(String trfId, long trfVersion) {
    return "TRF:" + trfId + ":" + trfVersion;
  }
}
