package com.pmart.mfc.quality.api;

import com.pmart.common.web.CommandResponse;
import com.pmart.mfc.quality.api.generated.model.CreateQualityDecision202Response;
import com.pmart.mfc.quality.api.generated.model.QualityLine;
import com.pmart.mfc.quality.domain.QualityRules.Line;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** 요청·응답 변환 도움. */
final class Dtos {

  private Dtos() {}

  static CreateQualityDecision202Response command(CommandResponse c) {
    var d = new CreateQualityDecision202Response();
    d.setStatus(CreateQualityDecision202Response.StatusEnum.fromValue(c.status().name()));
    d.setRequestId(c.requestId());
    d.setResultId(c.resultId());
    d.setAppliedQty(c.appliedQty());
    d.setVersion(c.version());
    d.setRetryable(c.retryable());
    d.setNextAction(c.nextAction());
    d.setOccurredAt(time(c.occurredAt()));
    return d;
  }

  static List<Line> lines(@Nullable List<QualityLine> in) {
    if (in == null) {
      return List.of();
    }
    return in.stream()
        .map(
            l ->
                new Line(
                    l.getSku(),
                    l.getLotId(),
                    l.getLocationCode(),
                    l.getQty(),
                    l.getWorkState() == null ? null : l.getWorkState().getValue()))
        .toList();
  }

  static List<QualityLine> qualityLines(List<Line> in) {
    return in.stream()
        .map(
            l -> {
              var q = new QualityLine();
              q.setSku(l.sku());
              q.setLotId(l.lotId());
              q.setLocationCode(l.locationCode());
              q.setQty(l.qty());
              if (l.workState() != null) {
                q.setWorkState(QualityLine.WorkStateEnum.fromValue(l.workState()));
              }
              return q;
            })
        .toList();
  }

  static @Nullable OffsetDateTime time(@Nullable Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }
}
