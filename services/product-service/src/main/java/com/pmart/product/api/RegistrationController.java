package com.pmart.product.api;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.product.api.generated.RegistrationApi;
import com.pmart.product.api.generated.model.CategoryAdditionRequest;
import com.pmart.product.api.generated.model.CategoryTreeDraftRequest;
import com.pmart.product.api.generated.model.CategoryTreeVersion;
import com.pmart.product.api.generated.model.IdMap;
import com.pmart.product.api.generated.model.ImportBatch;
import com.pmart.product.api.generated.model.ProductDraft;
import com.pmart.product.api.generated.model.ProductDraftInput;
import com.pmart.product.api.generated.model.SubmissionResult;
import com.pmart.product.application.CategoryTreeService;
import com.pmart.product.application.CategoryTreeService.AdditionCreated;
import com.pmart.product.application.CategoryTreeService.DraftCreated;
import com.pmart.product.application.Operator;
import com.pmart.product.application.ProductDraftService;
import com.pmart.product.domain.CategoryTree;
import com.pmart.product.infrastructure.CategoryStore.VersionRow;
import com.pmart.product.infrastructure.DraftStore.BatchRow;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 계약 RegistrationApi — 임시 백오피스 상품·카테고리 등록. 업무 규칙은 application 에 있고 여기서는 변환만 한다. */
@RestController
public class RegistrationController implements RegistrationApi {

  private final CategoryTreeService trees;
  private final ProductDraftService drafts;
  private final RegistrationMapping mapping;
  private final ObjectMapper mapper;

  public RegistrationController(
      CategoryTreeService trees,
      ProductDraftService drafts,
      RegistrationMapping mapping,
      ObjectMapper mapper) {
    this.trees = trees;
    this.drafts = drafts;
    this.mapping = mapping;
    this.mapper = mapper;
  }

  @Override
  public ResponseEntity<CategoryTreeVersion> createCategoryTreeVersion(
      String idempotencyKey, String xSourceSystem, CategoryTreeDraftRequest body) {
    JsonNode raw = mapper.valueToTree(body);
    List<CategoryTree.SourceNode> roots =
        mapper.convertValue(
            raw.get("categories"),
            mapper
                .getTypeFactory()
                .constructCollectionType(List.class, CategoryTree.SourceNode.class));
    List<CategoryTree.SourceFilter> filters =
        mapper.convertValue(
            raw.get("filters"),
            mapper
                .getTypeFactory()
                .constructCollectionType(List.class, CategoryTree.SourceFilter.class));
    DraftCreated c =
        trees
            .createDraft(
                idempotencyKey,
                Operator.current(xSourceSystem),
                body.getSourceNote(),
                roots,
                filters,
                raw)
            .value();
    return ResponseEntity.status(HttpStatus.CREATED).body(treeVersion(trees.version(c.version())));
  }

  /**
   * 카테고리 추가 — seed-v1 묶음(zip) 업로드(2026-11-03). 묶음의 categories.json 을 JSON 경로와 같은 요청으로 바꿔 같은 규칙으로
   * 처리한다.
   */
  @Override
  public ResponseEntity<CategoryTreeVersion> uploadCategoryTreeAddition(
      String idempotencyKey, String xSourceSystem, MultipartFile file) {
    CategoryAdditionRequest body =
        mapper.convertValue(trees.bundleCategories(upload(file)), CategoryAdditionRequest.class);
    if (body.getSourceNote() == null && file.getOriginalFilename() != null) {
      String name = file.getOriginalFilename();
      body.setSourceNote(name.length() > 500 ? name.substring(0, 500) : name);
    }
    return createCategoryTreeAddition(idempotencyKey, xSourceSystem, body);
  }

  @Override
  public ResponseEntity<CategoryTreeVersion> createCategoryTreeAddition(
      String idempotencyKey, String xSourceSystem, CategoryAdditionRequest body) {
    JsonNode raw = mapper.valueToTree(body);
    List<CategoryTree.Addition> additions = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    for (JsonNode c : raw.path("categories")) {
      List<String> names = strings(c.path("bmartPath"));
      if (!c.path("pmartCategoryIdLevels").isArray()) {
        skipped.add(String.join(" > ", names));
        continue;
      }
      additions.add(new CategoryTree.Addition(strings(c.get("pmartCategoryIdLevels")), names));
    }
    AdditionCreated a =
        trees
            .createAddition(
                idempotencyKey,
                Operator.current(xSourceSystem),
                body.getSourceNote(),
                additions,
                skipped,
                raw)
            .value();
    CategoryTreeVersion v = treeVersion(trees.version(a.version()));
    v.setBaseVersion(a.baseVersion());
    v.setAddedCategoryIds(a.addedCategoryIds());
    v.setSkippedPaths(a.skippedPaths());
    return ResponseEntity.status(HttpStatus.CREATED).body(v);
  }

  private static List<String> strings(JsonNode arr) {
    List<String> out = new ArrayList<>();
    arr.forEach(x -> out.add(x.isNull() ? null : x.asString()));
    return out;
  }

  @Override
  public ResponseEntity<CategoryTreeVersion> getCategoryTreeVersion(Integer version) {
    Operator.require();
    return ResponseEntity.ok(treeVersion(trees.version(version)));
  }

  @Override
  public ResponseEntity<ProductDraft> createProductDraft(
      String idempotencyKey, String xSourceSystem, ProductDraftInput body) {
    var row = drafts.create(idempotencyKey, Operator.current(xSourceSystem), input(body)).value();
    return ResponseEntity.status(HttpStatus.CREATED).body(mapping.draft(drafts.get(row.draftId())));
  }

  /** 상품 정보 수정 시작(2026-11-17): 현재 승인 버전으로 채운 targetProductId 초안. */
  @Override
  public ResponseEntity<ProductDraft> createProductEditDraft(
      String productId, String idempotencyKey, String xSourceSystem) {
    var row = drafts.createEdit(idempotencyKey, Operator.current(xSourceSystem), productId).value();
    return ResponseEntity.status(HttpStatus.CREATED).body(mapping.draft(drafts.get(row.draftId())));
  }

  @Override
  public ResponseEntity<ProductDraft> getProductDraft(String draftId) {
    Operator.require();
    return ResponseEntity.ok(mapping.draft(drafts.get(draftId)));
  }

  @Override
  public ResponseEntity<ProductDraft> updateProductDraft(
      String draftId,
      String idempotencyKey,
      String xSourceSystem,
      ProductDraftInput body,
      @Nullable Integer xExpectedVersion) {
    var row =
        drafts
            .update(
                idempotencyKey,
                Operator.current(xSourceSystem),
                draftId,
                xExpectedVersion,
                input(body))
            .value();
    return ResponseEntity.ok(mapping.draft(row));
  }

  @Override
  public ResponseEntity<SubmissionResult> submitProductDraft(
      String draftId, String idempotencyKey, String xSourceSystem) {
    var s = drafts.submit(idempotencyKey, Operator.current(xSourceSystem), draftId).value();
    return ResponseEntity.ok(mapping.convert(s, SubmissionResult.class));
  }

  @Override
  public ResponseEntity<ImportBatch> uploadProductImportBatch(
      String idempotencyKey, String xSourceSystem, MultipartFile file) {
    BatchRow b =
        drafts
            .uploadBatch(
                idempotencyKey,
                Operator.current(xSourceSystem),
                upload(file),
                file.getOriginalFilename())
            .value();
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(mapping.batch(drafts.batch(b.batchId()), drafts.batchDrafts(b.batchId())));
  }

  @Override
  public ResponseEntity<ImportBatch> getProductImportBatch(String batchId) {
    Operator.require();
    return ResponseEntity.ok(mapping.batch(drafts.batch(batchId), drafts.batchDrafts(batchId)));
  }

  @Override
  public ResponseEntity<SubmissionResult> submitProductImportBatch(
      String batchId, String idempotencyKey, String xSourceSystem) {
    var s = drafts.submitBatch(idempotencyKey, Operator.current(xSourceSystem), batchId).value();
    return ResponseEntity.ok(mapping.convert(s, SubmissionResult.class));
  }

  @Override
  public ResponseEntity<IdMap> getProductImportIdMap(String batchId) {
    Operator.require();
    BatchRow b = drafts.batch(batchId);
    Map<String, Object> m = new LinkedHashMap<>();
    List<Map<String, Object>> entries = drafts.idMap(batchId);
    m.put("format", "seed-v1");
    m.put("batchId", batchId);
    m.put("sourceSystem", b.sourceSystem());
    m.put("entries", entries);
    m.put("products", entries);
    m.put("note", "PMart filled pmartProductId/sku after loading (null = not issued yet)");
    return ResponseEntity.ok(mapping.convert(m, IdMap.class));
  }

  private JsonNode input(ProductDraftInput body) {
    return mapper.valueToTree(body);
  }

  private CategoryTreeVersion treeVersion(VersionRow v) {
    int nodes =
        trees.countsByLevel(v.version()).values().stream().mapToInt(Integer::intValue).sum();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("version", v.version());
    m.put("state", v.state());
    m.put("nodeCount", nodes);
    m.put("filterGroupCount", trees.filterGroupCount(v.version()));
    m.put("countsByLevel", trees.countsByLevel(v.version()));
    m.put("approvalId", v.approvalId());
    m.put("effectiveFrom", v.effectiveFrom() == null ? null : v.effectiveFrom().toString());
    if ("DRAFT".equals(v.state())) {
      m.put("approvalRequest", trees.approvalRequest(v.version(), v.draftHash(), nodes));
    }
    return mapping.convert(m, CategoryTreeVersion.class);
  }

  /** 업로드 파일을 메모리에 올리지 않는다 — 해시는 스트림으로, 읽을 때 다시 연다(BR-48: getBytes() 로 100MB+ 묶음 OOM). */
  private static com.pmart.product.application.UploadedBundle upload(MultipartFile file) {
    try {
      return com.pmart.product.application.UploadedBundle.from(file::getInputStream);
    } catch (IOException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "업로드 파일을 읽을 수 없습니다.");
    }
  }
}
