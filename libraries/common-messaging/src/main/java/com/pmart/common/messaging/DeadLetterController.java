package com.pmart.common.messaging;

import com.pmart.common.messaging.DeadLetters.Detail;
import com.pmart.common.messaging.DeadLetters.Status;
import com.pmart.common.messaging.DeadLetters.Summary;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 미처리 메시지 내부 API (ADR-0030, 계약 {@code contracts/openapi/_internal-dead-letters.yaml}). 서비스마다 같은 경로로
 * 열린다. 내부 호출 경로라 게이트웨이를 거치지 않는다(ADR-0026).
 */
@RestController
@RequestMapping("/api/v1/internal/dead-letters")
public class DeadLetterController {

  /** 재처리·폐기 본문. actor 는 처리한 사람(기록용). */
  public record Action(@Nullable String actor, @Nullable String reason) {}

  private final DeadLetters deadLetters;

  public DeadLetterController(DeadLetters deadLetters) {
    this.deadLetters = deadLetters;
  }

  @GetMapping
  public Map<String, Object> list(
      @RequestParam(required = false) @Nullable String status,
      @RequestParam(required = false) @Nullable String topic,
      @RequestParam(defaultValue = "50") int limit) {
    List<Summary> items = deadLetters.list(status(status), topic, limit);
    return Map.of("items", items);
  }

  @GetMapping("/{id}")
  public Detail get(@PathVariable UUID id) {
    return deadLetters.find(id).orElseThrow(() -> notFound(id));
  }

  @PostMapping("/{id}/replays")
  public Summary replay(@PathVariable UUID id, @RequestBody(required = false) @Nullable Action a) {
    try {
      return deadLetters.replay(id, a == null ? null : a.actor());
    } catch (NoSuchElementException e) {
      throw notFound(id);
    } catch (DeadLetters.StateConflict e) {
      throw PmartException.invalidState(e.getMessage());
    } catch (IllegalStateException e) {
      throw PmartException.dependencyUnavailable(e.getMessage(), e);
    }
  }

  @PostMapping("/{id}/discards")
  public Summary discard(@PathVariable UUID id, @RequestBody(required = false) @Nullable Action a) {
    if (a == null || a.reason() == null || a.reason().isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "폐기 사유(reason)가 필요합니다.");
    }
    try {
      return deadLetters.discard(id, a.reason().strip(), a.actor());
    } catch (NoSuchElementException e) {
      throw notFound(id);
    } catch (DeadLetters.StateConflict e) {
      throw PmartException.invalidState(e.getMessage());
    }
  }

  private static @Nullable Status status(@Nullable String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Status.valueOf(raw.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "status 는 OPEN·REPLAYED·DISCARDED: " + raw);
    }
  }

  private static PmartException notFound(UUID id) {
    return PmartException.notFound("DEAD_LETTER_NOT_FOUND", "미처리 메시지가 없습니다: " + id);
  }
}
