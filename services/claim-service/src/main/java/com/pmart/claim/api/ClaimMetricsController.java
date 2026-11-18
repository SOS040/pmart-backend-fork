package com.pmart.claim.api;

import com.pmart.claim.api.generated.ClaimMetricsApi;
import com.pmart.claim.api.generated.model.RefundMetrics;
import com.pmart.claim.api.generated.model.RefundMetricsByReasonInner;
import com.pmart.claim.application.RefundMetricsService;
import java.time.LocalDate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 ClaimMetricsApi — 직원용 기간 환불 집계(0.5.0, CL-3). 경로 {@code /claims/metrics/refunds} 는 변수가 없어
 * {@code /claims/{claimId}/refunds} 보다 먼저 맞는다(Spring 은 변수 없는 경로를 앞세운다). 권한·기간은 {@link
 * RefundMetricsService}.
 */
@RestController
public class ClaimMetricsController implements ClaimMetricsApi {

  private final RefundMetricsService service;

  public ClaimMetricsController(RefundMetricsService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<RefundMetrics> getRefundMetrics(LocalDate from, LocalDate to) {
    RefundMetricsService.Result r = service.refunds(from, to);
    return ResponseEntity.ok(
        new RefundMetrics()
            .from(from)
            .to(to)
            .totalAmount(r.totalAmount())
            .totalCount(r.totalCount())
            .byReason(
                r.byBucket().stream()
                    .map(
                        l ->
                            new RefundMetricsByReasonInner()
                                .reason(
                                    RefundMetricsByReasonInner.ReasonEnum.fromValue(
                                        l.bucket().name()))
                                .amount(l.amount())
                                .count(l.count()))
                    .toList()));
  }
}
