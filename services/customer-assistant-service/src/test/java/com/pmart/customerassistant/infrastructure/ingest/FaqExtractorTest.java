package com.pmart.customerassistant.infrastructure.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.customerassistant.infrastructure.ingest.FaqExtractor.FaqEntry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 고객 안내 문서 → FAQ 항목(2026-11-18). */
class FaqExtractorTest {

  static final String DOC =
      """
      # 주문 취소 [확정 정책]

      첫머리 — 항목 아님.

      ## 주문을 취소하고 싶어요 [확정 정책]

      - 받으신 뒤 **반품**으로 도와드려요([08](08-return-exchange.md)).
      - 모르는 문서는 [여기](99-none.md#a) 글자만.

      ### 단계

      | 단계 | 뜻 |
      |---|---|
      | 요청 | 접수 |

      ```
      ## 코드 블록 안 제목은 절이 아님
      ```

      ## 빈 절 [설계안]

      ## 결제가 확인 중이에요 [채택 설계]

      - [채택 설계] 다시 결제하지 마세요.
      """;

  @Test
  void splitsSecondLevelSectionsIntoQuestionAndAnswer() {
    List<FaqEntry> e =
        FaqExtractor.entries(
            "docs/business/customer-faq/02-cancel.md",
            DOC,
            2,
            f -> f.equals("08-return-exchange.md") ? "반품·교환" : null);
    assertThat(e).extracting(FaqEntry::question).containsExactly("주문을 취소하고 싶어요", "결제가 확인 중이에요");
    assertThat(e).extracting(FaqEntry::category).containsOnly("cancel");
    assertThat(e).extracting(FaqEntry::categoryTitle).containsOnly("주문 취소");
    assertThat(e).extracting(FaqEntry::itemOrder).containsExactly(1, 3);
    String a = e.get(0).answer();
    assertThat(a)
        .contains("**반품**으로 도와드려요(‘반품·교환’).")
        .contains("모르는 문서는 여기 글자만.")
        .contains("### 단계")
        .contains("| 요청 | 접수 |")
        .contains("## 코드 블록 안 제목은 절이 아님")
        .doesNotContain(".md")
        .doesNotContain("첫머리");
    assertThat(e.get(1).answer()).isEqualTo("- 다시 결제하지 마세요.");
    assertThat(e.get(0).searchText()).contains("반품으로 도와드려요").doesNotContain("**");
    assertThat(e.get(0).faqId()).matches("cancel-[0-9a-f]{10}");
    // 같은 질문이면 같은 ID(재색인)
    assertThat(FaqExtractor.entries("x/02-cancel.md", DOC, 9, f -> null).get(0).faqId())
        .isEqualTo(e.get(0).faqId());
  }

  @Test
  void categoryComesFromFileNameAndMustBeASlug() {
    assertThat(FaqExtractor.category("docs/business/customer-faq/03-delivery-status.md"))
        .isEqualTo("delivery-status");
    assertThat(FaqExtractor.category("a/Help.md")).isEqualTo("help");
    assertThatThrownBy(() -> FaqExtractor.category("a/01-배송.md"))
        .isInstanceOf(KnowledgeIngestor.IngestFailedException.class);
  }

  @Test
  void duplicateQuestionsInOneDocumentGetDistinctIds() {
    var e =
        FaqExtractor.entries("d/01-x.md", "# X\n\n## 같은 질문\n\n가\n\n## 같은 질문\n\n나\n", 1, f -> null);
    assertThat(e).extracting(FaqEntry::faqId).doesNotHaveDuplicates().hasSize(2);
  }

  /** 실제 고객 안내 문서(본 manifest): 모든 문서가 항목을 내고, ID 가 겹치지 않고, 태그·파일 경로가 남지 않는다. */
  @Test
  void realCustomerFaqDocumentsParse() throws IOException {
    KnowledgeManifest manifest;
    try (InputStream in = new ClassPathResource("knowledge/manifest.yml").getInputStream()) {
      manifest = KnowledgeManifest.load(in);
    }
    Path repo = Path.of("../..").toAbsolutePath().normalize();
    Map<String, byte[]> bytes = new HashMap<>();
    for (var d : manifest.documents()) {
      bytes.put(d.path(), Files.readAllBytes(repo.resolve(d.path())));
    }
    List<FaqEntry> all = FaqExtractor.extract(manifest, bytes);
    // FAQ 는 domain customer-faq 문서에서만(customer_app 은 AI 답변 근거 전용 — 2026-11-20)
    var faqDocs =
        manifest.documents().stream()
            .filter(d -> FaqExtractor.FAQ_DOMAIN.equals(d.domain()))
            .map(d -> d.path())
            .toList();
    assertThat(faqDocs).isNotEmpty();
    assertThat(all).extracting(FaqEntry::documentId).containsAll(faqDocs).allMatch(faqDocs::contains);
    assertThat(all).extracting(FaqEntry::faqId).doesNotHaveDuplicates();
    assertThat(all)
        .allSatisfy(
            f -> {
              assertThat(f.question()).doesNotContain("[").isNotBlank();
              assertThat(f.answer()).doesNotContain(".md)").doesNotContain("[확정 정책]").isNotBlank();
              assertThat(f.category()).matches("[a-z0-9-]+");
            });
    assertThat(all.getFirst().categoryOrder()).isEqualTo(1);
    assertThat(new String(bytes.get(all.getFirst().documentId()), StandardCharsets.UTF_8))
        .contains(all.getFirst().question());
  }
}
