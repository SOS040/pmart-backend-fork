package com.pmart.pricing.api;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.pricing.api.generated.RegistrationApi;
import com.pmart.pricing.api.generated.model.PriceApprovalRequestBody;
import com.pmart.pricing.api.generated.model.PriceImportBatch;
import com.pmart.pricing.api.generated.model.PriceImportLineUpdate;
import com.pmart.pricing.api.generated.model.PriceImportSubmissionRequest;
import com.pmart.pricing.api.generated.model.PriceImportSubmissionResult;
import com.pmart.pricing.application.PriceImportService;
import java.io.IOException;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

/** 계약 RegistrationApi — 가격 일괄 등록. 업무 규칙은 application 에 있고 여기서는 변환만 한다. */
@RestController
public class PriceImportController implements RegistrationApi {

  private final PriceImportService imports;
  private final ObjectMapper mapper;

  public PriceImportController(PriceImportService imports, ObjectMapper mapper) {
    this.imports = imports;
    this.mapper = mapper;
  }

  @Override
  public ResponseEntity<PriceImportBatch> uploadPriceImportBatch(
      String idempotencyKey, String xSourceSystem, MultipartFile file) {
    // 업로드 파일을 메모리에 올리지 않는다 — 해시는 스트림으로, 읽을 때 다시 연다(BR-48: getBytes() 로 큰 묶음 OOM)
    com.pmart.pricing.application.UploadedBundle bundle;
    try {
      bundle = com.pmart.pricing.application.UploadedBundle.from(file::getInputStream);
    } catch (IOException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "업로드 파일을 읽을 수 없습니다.");
    }
    String batchId =
        imports.upload(idempotencyKey, xSourceSystem, bundle, file.getOriginalFilename());
    return ResponseEntity.status(HttpStatus.CREATED).body(batch(batchId));
  }

  @Override
  public ResponseEntity<PriceImportBatch> getPriceImportBatch(String batchId) {
    return ResponseEntity.ok(batch(batchId));
  }

  @Override
  public ResponseEntity<PriceImportBatch> updatePriceImportLine(
      String batchId,
      String seedProductId,
      String idempotencyKey,
      String xSourceSystem,
      PriceImportLineUpdate body,
      @Nullable Integer xExpectedVersion) {
    imports.updateLine(
        idempotencyKey,
        xSourceSystem,
        batchId,
        seedProductId,
        body.getBasePrice() == null ? null : body.getBasePrice().longValue(),
        Boolean.TRUE.equals(body.getExcluded()),
        xExpectedVersion);
    return ResponseEntity.ok(batch(batchId));
  }

  @Override
  public ResponseEntity<PriceImportBatch> refreshPriceImportBatch(
      String batchId, String idempotencyKey, String xSourceSystem) {
    imports.refresh(idempotencyKey, xSourceSystem, batchId);
    return ResponseEntity.ok(batch(batchId));
  }

  @Override
  public ResponseEntity<PriceImportSubmissionResult> submitPriceImportBatch(
      String batchId,
      String idempotencyKey,
      String xSourceSystem,
      @Nullable Integer xExpectedVersion,
      @Nullable PriceImportSubmissionRequest body) {
    Instant effectiveFrom =
        body == null || body.getEffectiveFrom() == null
            ? null
            : body.getEffectiveFrom().toInstant();
    PriceImportService.Submitted s =
        imports.submit(idempotencyKey, xSourceSystem, batchId, effectiveFrom, xExpectedVersion);
    return ResponseEntity.ok(
        new PriceImportSubmissionResult()
            .batch(batch(s.batchId()))
            .approvalRequest(
                mapper.convertValue(s.approvalRequest(), PriceApprovalRequestBody.class)));
  }

  private PriceImportBatch batch(String batchId) {
    return mapper.convertValue(imports.view(batchId), PriceImportBatch.class);
  }
}
