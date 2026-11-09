package com.pmart.customerassistant.application;

import com.pmart.customerassistant.domain.RetrievedChunk;
import com.pmart.customerassistant.infrastructure.SnapshotStore.SnapshotRow;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 저장되는 인용(customer_answer.citations jsonb). 계약 Citation 필드 + <b>청크별 accessScope</b> — 스냅숏이 정리된 뒤에도
 * 현재 권한을 재검사할 수 있게(3차 검토 3).
 */
public record CitationRecord(
    String chunkId,
    String documentId,
    String path,
    @Nullable String section,
    String authority,
    String lifecycleStatus,
    @Nullable String supersededBy,
    List<String> proposedSupersededBy,
    @Nullable String implRecordDate,
    @Nullable String implNote,
    @Nullable String documentVersion,
    @Nullable String effectiveDate,
    String snapshotId,
    @Nullable String sourceRevision,
    boolean sourceDirty,
    String indexedAt,
    String accessScope,
    String excerpt) {

  public static CitationRecord of(RetrievedChunk c, SnapshotRow s) {
    return new CitationRecord(
        c.chunkId(),
        c.documentId(),
        c.path(),
        c.section(),
        c.authority().name(),
        c.lifecycle().name(),
        c.supersededBy(),
        c.proposedSupersededBy(),
        c.implRecordDate(),
        c.implNote(),
        c.documentVersion(),
        c.effectiveDate(),
        s.snapshotId(),
        s.sourceRevision(),
        s.sourceDirty(),
        String.valueOf(s.activatedAt() == null ? s.createdAt() : s.activatedAt()),
        c.accessScope(),
        c.content());
  }
}
