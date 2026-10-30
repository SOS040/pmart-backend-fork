package com.pmart.pricing.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.pricing.domain.PriceRules;
import com.pmart.pricing.domain.PriceRules.LineProblem;
import com.pmart.pricing.domain.PriceVersion;
import com.pmart.pricing.infrastructure.PriceStore;
import com.pmart.pricing.infrastructure.PriceStore.ApprovalLineRow;
import com.pmart.pricing.infrastructure.PriceStore.NewVersion;
import com.pmart.pricing.infrastructure.SkuProjectionStore;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * ApprovalDecided(PRICE, targetService=pricing-service) 반영. inbox 트랜잭션 안에서 호출된다(ADR-0009).
 *
 * <p>승인 행 형식(가격 변경 요청을 approval-service 에 만들 때): {@code lineKey}=SKU, {@code requestedAmount}=기본가,
 * {@code attributes}={@code {sku, basePrice, effectiveFrom?}}. 행의 효력 시각이 없으면 승인 효력 시각, 그것도 없으면 반영
 * 시각이다. 행마다 독립 반영하고(무효 SKU·0원·기간 겹침은 그 행만 거절), 결과는 price_approval(_line) 에 남겨 같은 승인을 두 번 적용하지 않는다.
 */
@Service
public class PriceApprovalApplier {

  private static final Logger log = LoggerFactory.getLogger(PriceApprovalApplier.class);

  private final PriceStore prices;
  private final SkuProjectionStore skus;
  private final PriceActivator activator;
  private final Clock clock;

  public PriceApprovalApplier(
      PriceStore prices, SkuProjectionStore skus, PriceActivator activator, Clock clock) {
    this.prices = prices;
    this.skus = skus;
    this.activator = activator;
    this.clock = clock;
  }

  public void handle(EventEnvelope envelope) {
    if (!"ApprovalDecided".equals(envelope.eventType())) {
      return;
    }
    JsonNode p = envelope.payload();
    if (!"PRICE".equals(p.path("type").asString())
        || !"pricing-service".equals(p.path("targetRef").path("targetService").asString())) {
      return;
    }
    String approvalId = p.path("approvalId").asString();
    if (prices.approvalReceived(approvalId)) {
      return;
    }
    String decision = p.path("decision").asString();
    String changeRequestId = text(p, "changeRequestId");
    Instant at = clock.instant();
    Instant approvalEffective = instant(p, "effectiveFrom");
    String approverId = text(p, "approverId");
    Integer dossierVersion =
        p.hasNonNull("dossierVersion") ? p.get("dossierVersion").asInt() : null;

    int applied = 0;
    int rejected = 0;
    Set<String> seen = new HashSet<>();
    for (JsonNode line : p.path("lines")) {
      JsonNode attrs = line.path("attributes");
      String sku = attrs.hasNonNull("sku") ? attrs.get("sku").asString() : text(line, "lineId");
      Long basePrice = price(attrs, line);
      Instant requested = instant(attrs, "effectiveFrom");
      if (requested == null) {
        requested = approvalEffective;
      }
      String key = sku == null ? "" : sku.length() > 64 ? sku.substring(0, 64) : sku;
      Optional<String> problem;
      Integer version = null;
      if (!"APPROVED".equals(decision)
          || !"APPROVED".equals(line.path("decision").asString(decision))) {
        problem = Optional.of("APPROVAL_REJECTED");
      } else if (attrs.hasNonNull("basePrice")
          && line.hasNonNull("amount")
          && attrs.get("basePrice").asLong() != line.get("amount").asLong()) {
        // 승인자가 본 금액(amount)과 반영할 금액(attributes)이 다르면 반영하지 않는다
        problem = Optional.of("APPROVAL_SCOPE_MISMATCH");
      } else if (!seen.add(key)) {
        problem = Optional.of(LineProblem.DUPLICATE_SKU.name());
      } else {
        problem =
            PriceRules.checkLine(sku, basePrice, sku != null && skus.exists(sku)).map(Enum::name);
        if (problem.isEmpty()) {
          List<PriceVersion> existing = prices.versions(key, true);
          Instant effectiveFrom = PriceRules.effectiveFrom(requested, at);
          if (PriceRules.scheduleConflict(existing, effectiveFrom)) {
            problem = Optional.of(LineProblem.PRICE_SCHEDULE_CONFLICT.name());
          } else {
            version = existing.isEmpty() ? 1 : existing.getLast().version() + 1;
            Long previous =
                PriceRules.effectiveAt(existing, effectiveFrom)
                    .map(PriceVersion::basePrice)
                    .orElse(null);
            prices.insert(
                new NewVersion(
                    key,
                    version,
                    basePrice,
                    previous,
                    effectiveFrom,
                    requested,
                    approvalId,
                    changeRequestId,
                    approverId,
                    dossierVersion,
                    at));
            if (!effectiveFrom.isAfter(at)) {
              activator.activate(
                  new PriceVersion(
                      key,
                      version,
                      basePrice,
                      previous,
                      effectiveFrom,
                      null,
                      PriceVersion.State.SCHEDULED,
                      approvalId));
            }
          }
        }
      }
      if (problem.isPresent()) {
        rejected++;
      } else {
        applied++;
      }
      // 같은 SKU 가 두 번 오면 첫 행만 남긴다(기본 키)
      if (!problem.equals(Optional.of(LineProblem.DUPLICATE_SKU.name()))) {
        prices.insertApprovalLine(
            new ApprovalLineRow(
                approvalId,
                key,
                basePrice,
                requested,
                problem.isPresent() ? "REJECTED" : "APPLIED",
                problem.orElse(null),
                version));
      }
    }
    String result = applied == 0 ? "REJECTED" : rejected == 0 ? "APPLIED" : "PARTIAL";
    prices.insertApproval(approvalId, changeRequestId, decision, result, at);
    log.info(
        "price approval {} → {} (applied {}, rejected {})", approvalId, result, applied, rejected);
  }

  private static @Nullable Long price(JsonNode attrs, JsonNode line) {
    if (attrs.hasNonNull("basePrice")) {
      return attrs.get("basePrice").asLong();
    }
    return line.hasNonNull("amount") ? line.get("amount").asLong() : null;
  }

  private static @Nullable String text(JsonNode n, String field) {
    return n.hasNonNull(field) ? n.get(field).asString() : null;
  }

  private static @Nullable Instant instant(JsonNode n, String field) {
    // 계약 date-time 은 오프셋을 포함할 수 있다(+09:00 등)
    return n.hasNonNull(field) ? OffsetDateTime.parse(n.get(field).asString()).toInstant() : null;
  }
}
