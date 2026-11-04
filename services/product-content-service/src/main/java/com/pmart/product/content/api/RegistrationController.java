package com.pmart.product.content.api;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.product.content.api.generated.RegistrationApi;
import com.pmart.product.content.api.generated.model.ContentDraft;
import com.pmart.product.content.api.generated.model.ContentDraftInput;
import com.pmart.product.content.api.generated.model.ContentImage;
import com.pmart.product.content.api.generated.model.ContentImportBatch;
import com.pmart.product.content.api.generated.model.ContentSubmissionResult;
import com.pmart.product.content.api.generated.model.ImageRole;
import com.pmart.product.content.application.ContentDraftService;
import com.pmart.product.content.application.Operator;
import com.pmart.product.content.domain.ContentInput;
import com.pmart.product.content.infrastructure.DraftStore.BatchRow;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

/** 계약 RegistrationApi — 임시 백오피스 콘텐츠 등록. 업무 규칙은 application/domain 에 있고 여기서는 변환만 한다. */
@RestController
public class RegistrationController implements RegistrationApi {

  private final ContentDraftService drafts;
  private final RegistrationMapping mapping;
  private final ObjectMapper mapper;

  public RegistrationController(
      ContentDraftService drafts, RegistrationMapping mapping, ObjectMapper mapper) {
    this.drafts = drafts;
    this.mapping = mapping;
    this.mapper = mapper;
  }

  @Override
  public ResponseEntity<ContentDraft> createContentDraft(
      String idempotencyKey, String xSourceSystem, ContentDraftInput body) {
    var row = drafts.create(idempotencyKey, Operator.current(xSourceSystem), input(body)).value();
    return ResponseEntity.status(HttpStatus.CREATED).body(mapping.draft(drafts.get(row.draftId())));
  }

  @Override
  public ResponseEntity<ContentDraft> getContentDraft(String draftId) {
    Operator.require();
    return ResponseEntity.ok(mapping.draft(drafts.get(draftId)));
  }

  @Override
  public ResponseEntity<ContentDraft> updateContentDraft(
      String draftId,
      String idempotencyKey,
      String xSourceSystem,
      ContentDraftInput body,
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
    return ResponseEntity.ok(mapping.draft(drafts.get(row.draftId())));
  }

  @Override
  public ResponseEntity<ContentImage> uploadContentImage(
      String draftId,
      String idempotencyKey,
      String xSourceSystem,
      MultipartFile file,
      ImageRole role,
      @Nullable Integer sortOrder) {
    var row =
        drafts
            .uploadImage(
                idempotencyKey,
                Operator.current(xSourceSystem),
                draftId,
                com.pmart.product.content.domain.ImageRole.valueOf(role.getValue()),
                sortOrder,
                bytes(file))
            .value();
    return ResponseEntity.status(HttpStatus.CREATED).body(mapping.image(row));
  }

  @Override
  public ResponseEntity<ContentSubmissionResult> submitContentDraft(
      String draftId, String idempotencyKey, String xSourceSystem) {
    var s = drafts.submit(idempotencyKey, Operator.current(xSourceSystem), draftId).value();
    return ResponseEntity.ok(mapping.convert(s, ContentSubmissionResult.class));
  }

  @Override
  public ResponseEntity<ContentImportBatch> uploadContentImportBatch(
      String idempotencyKey, String xSourceSystem, MultipartFile file) {
    BatchRow b =
        drafts
            .uploadBatch(
                idempotencyKey,
                Operator.current(xSourceSystem),
                bundle(file),
                file.getOriginalFilename())
            .value();
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(mapping.batch(drafts.batch(b.batchId()), drafts.batchItems(b.batchId())));
  }

  @Override
  public ResponseEntity<ContentImportBatch> getContentImportBatch(String batchId) {
    Operator.require();
    return ResponseEntity.ok(mapping.batch(drafts.batch(batchId), drafts.batchItems(batchId)));
  }

  @Override
  public ResponseEntity<ContentSubmissionResult> submitContentImportBatch(
      String batchId, String idempotencyKey, String xSourceSystem) {
    var s = drafts.submitBatch(idempotencyKey, Operator.current(xSourceSystem), batchId).value();
    return ResponseEntity.ok(mapping.convert(s, ContentSubmissionResult.class));
  }

  private ContentInput input(ContentDraftInput body) {
    return mapper.convertValue(body, ContentInput.class);
  }

  /** 업로드 파일을 메모리에 올리지 않는다 — 해시는 스트림으로, 읽을 때 다시 연다(BR-48: getBytes() 로 큰 묶음 OOM). */
  private static com.pmart.product.content.application.UploadedBundle bundle(MultipartFile file) {
    try {
      return com.pmart.product.content.application.UploadedBundle.from(file::getInputStream);
    } catch (IOException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "업로드 파일을 읽을 수 없습니다.");
    }
  }

  /** 이미지 한 장(작음, 상한은 multipart 설정). */
  private static byte[] bytes(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "업로드 파일을 읽을 수 없습니다.");
    }
  }
}
