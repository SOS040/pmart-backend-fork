package com.pmart.product.application;

import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.Outcome;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.product.domain.CategoryTree;
import com.pmart.product.domain.Hashes;
import com.pmart.product.infrastructure.CategoryStore;
import com.pmart.product.infrastructure.CategoryStore.GroupRow;
import com.pmart.product.infrastructure.CategoryStore.NodeRow;
import com.pmart.product.infrastructure.CategoryStore.VersionRow;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 카테고리 트리·필터 버전(ADR-0015). 초안 등록 → approval-service PRODUCT 승인(targetRef {@code
 * categoryTreeVersion:{v}}) → 발효. 발효 버전은 하나이고 이전 발효 버전은 SUPERSEDED 로 남는다.
 */
@Service
public class CategoryTreeService {

  public static final String TARGET_PREFIX = "categoryTreeVersion:";

  /** 초안 등록 결과. 응답에 그대로 쓰고 재생(멱등)에도 쓴다. */
  public record DraftCreated(
      int version,
      String draftHash,
      int nodeCount,
      int filterGroupCount,
      Map<String, Integer> countsByLevel) {}

  private final CategoryStore store;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final ObjectMapper mapper;
  private final Clock clock;

  public CategoryTreeService(
      CategoryStore store,
      RequestIdempotency idempotency,
      EventPublisher events,
      ObjectMapper mapper,
      Clock clock) {
    this.store = store;
    this.idempotency = idempotency;
    this.events = events;
    this.mapper = mapper;
    this.clock = clock;
  }

  public Outcome<DraftCreated> createDraft(
      String requestId,
      Operator op,
      @Nullable String note,
      List<CategoryTree.SourceNode> roots,
      List<CategoryTree.SourceFilter> filters,
      Object requestBody) {
    return idempotency.execute(
        op.key(requestId, "createCategoryTreeVersion"),
        requestBody,
        DraftCreated.class,
        () -> {
          CategoryTree.Parsed tree;
          try {
            tree = CategoryTree.parse(roots, filters);
          } catch (CategoryTree.InvalidTreeException e) {
            throw new PmartException(ErrorCode.VALIDATION_FAILED, e.getMessage());
          }
          int version = store.nextVersion();
          String hash = Hashes.sha256(mapper.writeValueAsString(tree));
          store.insert(version, tree, note, hash, op.id(), clock.instant());
          Map<String, Integer> counts = new LinkedHashMap<>();
          tree.countsByLevel().forEach((k, v) -> counts.put(k.name(), v));
          return new DraftCreated(
              version, hash, tree.nodes().size(), tree.filterGroups().size(), counts);
        });
  }

  public VersionRow version(int version) {
    return store
        .find(version)
        .orElseThrow(
            () ->
                PmartException.notFound(
                    "CATEGORY_TREE_VERSION_NOT_FOUND", "카테고리 트리 버전이 없습니다: " + version));
  }

  public Map<String, Integer> countsByLevel(int version) {
    return store.countsByLevel(version);
  }

  public int filterGroupCount(int version) {
    return store.filterGroupCount(version);
  }

  /** 발효 버전(없으면 CATEGORY_TREE_NOT_EFFECTIVE). */
  public VersionRow effective() {
    return store
        .effective()
        .orElseThrow(
            () ->
                PmartException.notFound(
                    "CATEGORY_TREE_NOT_EFFECTIVE", "발효된 카테고리 트리가 없습니다. 트리를 먼저 승인하세요."));
  }

  /** 조회 대상 버전: 지정 버전(발효·과거 발효만) 또는 현재 발효 버전. */
  public VersionRow readable(@Nullable Integer version) {
    if (version == null) {
      return effective();
    }
    VersionRow v = version(version);
    if (!"EFFECTIVE".equals(v.state()) && !"SUPERSEDED".equals(v.state())) {
      throw PmartException.notFound("CATEGORY_TREE_NOT_EFFECTIVE", "발효된 적 없는 버전입니다: " + version);
    }
    return v;
  }

  public List<NodeRow> nodes(int version) {
    return store.nodes(version);
  }

  public List<GroupRow> filters(int version, String categoryId) {
    if (!store.nodeExists(version, categoryId)) {
      throw PmartException.notFound("CATEGORY_NOT_FOUND", "카테고리가 없습니다: " + categoryId);
    }
    return store.filtersOf(version, categoryId);
  }

  /** approval-service 에 그대로 보낼 변경 요청 본문. */
  public Map<String, Object> approvalRequest(int version, String hash, int nodeCount) {
    String ref = TARGET_PREFIX + version;
    return Map.of(
        "type",
        "PRODUCT",
        "targetService",
        "product-service",
        "targetRef",
        ref,
        "allowedActions",
        List.of("APPLY_CATEGORY_TREE"),
        "summary",
        "카테고리 트리 버전 " + version + " 발효 (노드 " + nodeCount + "개)",
        "lines",
        List.of(
            Map.of(
                "lineKey", ref,
                "targetRef", ref,
                "attributes", Map.of("version", version, "draftHash", hash))));
  }

  /**
   * 승인 결과 반영(ApprovalApplier 가 inbox 트랜잭션 안에서 호출). 해시가 다르면 반영하지 않고 사유를 돌려준다.
   *
   * @return 반영 결과 코드(APPLIED / REJECTED / SCOPE_MISMATCH / IGNORED)
   */
  String applyDecision(
      int version, String decision, String approvalId, @Nullable String approvedHash, Instant at) {
    VersionRow v = store.find(version).orElse(null);
    if (v == null || !"DRAFT".equals(v.state())) {
      return "IGNORED";
    }
    if (!"APPROVED".equals(decision)) {
      store.moveState(version, "DRAFT", "SUBMITTED");
      store.markRejected(version, approvalId);
      return "REJECTED";
    }
    if (approvedHash == null || !approvedHash.equals(v.draftHash())) {
      return "SCOPE_MISMATCH";
    }
    store.makeEffective(version, approvalId, at);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("categoryId", "category-tree");
    payload.put("parentCategoryId", null);
    payload.put("state", "EFFECTIVE");
    payload.put("version", version);
    payload.put("scope", "TREE");
    payload.put("treeVersion", version);
    payload.put("nodeCount", store.nodes(version).size());
    payload.put("filterGroupCount", store.filterGroupCount(version));
    payload.put("effectiveAt", at.toString());
    payload.put("approvalRef", Map.of("approvalId", approvalId));
    events.publish("CategoryChanged", 1, "Category", "category-tree", version, null, payload);
    return "APPLIED";
  }
}
