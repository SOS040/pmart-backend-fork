package com.pmart.customerassistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.TestJwt;
import com.pmart.customerassistant.infrastructure.ingest.KnowledgeManifest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * FAQ 목록·검색(0.2.0, 2026-11-18 사용자 "A해줘"): 색인한 같은 안내 문서·같은 ACTIVE 스냅숏에서 나오고, 모델 없이 부분 문자열로 찾고, 회원
 * 고객만 본다.
 */
class FaqIT extends AssistantITBase {

  static final List<String> FAQ_FILES =
      List.of(
          "docs/business/customer-faq/01-help.md", "docs/business/customer-faq/02-cancel-test.md");

  @BeforeEach
  void copyFaqFixtures() throws IOException {
    for (String f : FAQ_FILES) {
      Path target = repo.resolve(f);
      Files.createDirectories(target.getParent());
      try (InputStream in = new ClassPathResource("fixture/" + f).getInputStream()) {
        Files.copy(in, target);
      }
    }
  }

  static KnowledgeManifest faqManifest() {
    try (InputStream in = new ClassPathResource("fixture/faq-manifest.yml").getInputStream()) {
      return KnowledgeManifest.load(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  Http faqs(String token, String query) {
    return get(token, "/api/v1/customer-assistant/faqs" + query, "customer-app");
  }

  /** 검색어 그대로 — RestClient 가 uri 문자열을 인코딩한다(미리 인코딩하면 두 번). 시험 값에 & = + 는 없다. */
  static String enc(String s) {
    return s;
  }

  @Test
  void listsSectionsOfTheActiveSnapshotAsFaqItems() {
    String me = customer("CU-faq-1");
    // 색인 전, 그리고 FAQ 문서가 없는 색인 → 422(빈 목록으로 숨기지 않는다)
    assertThat(faqs(me, "").status()).isEqualTo(422);
    assertThat(faqs(me, "").text("code")).isEqualTo("CONFIG_REQUIRED");
    ingest();
    assertThat(faqs(me, "").status()).isEqualTo(422);

    var first = ingestor.ingest(repo, faqManifest());
    Http all = faqs(me, "");
    assertThat(all.status()).as(all.body().toString()).isEqualTo(200);
    var items = all.body().path("items");
    // 문서 순 → 절 순, 업무 문서(policy.md)·절 밖 첫머리는 FAQ 가 아니다
    assertThat(items.findValuesAsString("question"))
        .containsExactly("채팅 상담은 어떻게 하나요?", "Wi-Fi 가 끊겨요", "주문을 취소하고 싶어요", "봉투값도 돌려받나요?");
    assertThat(items.findValuesAsString("category"))
        .containsExactly("help", "help", "cancel-test", "cancel-test");
    assertThat(items.path(0).path("categoryTitle").asString()).isEqualTo("고객센터 이용 안내");
    String chat = items.path(0).path("answer").asString();
    assertThat(chat)
        .contains("**고객센터 → 채팅 상담**")
        .contains("(‘주문 취소 시험’)")
        .doesNotContain(".md")
        .doesNotContain("[설계안]")
        .doesNotContain("절 밖");
    // ### 이하·표는 답에 남는다
    assertThat(items.path(2).path("answer").asString()).contains("### 취소 단계").contains("| 취소 요청 |");
    assertThat(all.body().path("categories").findValuesAsString("category"))
        .containsExactly("help", "cancel-test");
    assertThat(all.body().path("categories").findValuesAsString("title"))
        .containsExactly("고객센터 이용 안내", "주문 취소 시험");
    assertThat(all.body().path("categories").path(0).path("count").asInt()).isEqualTo(2);

    // 한 항목 다시 보기, 재색인해도 같은 ID(같은 질문)
    String id = items.path(3).path("faqId").asString();
    assertThat(id).startsWith("cancel-test-");
    Http one = get(me, "/api/v1/customer-assistant/faqs/" + id, "customer-app");
    assertThat(one.status()).isEqualTo(200);
    assertThat(one.text("question")).isEqualTo("봉투값도 돌려받나요?");
    var second = ingestor.ingest(repo, faqManifest());
    assertThat(second.snapshotId()).isNotEqualTo(first.snapshotId());
    assertThat(faqs(me, "").body().path("items").path(3).path("faqId").asString()).isEqualTo(id);
    // 이전 스냅숏 행은 그대로(RETIRED) — 목록에는 ACTIVE 것만
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM faq_entry WHERE snapshot_id = ?",
                Integer.class,
                first.snapshotId()))
        .isEqualTo(4);
    assertThat(faqs(me, "").body().path("items")).hasSize(4);
    assertThat(get(me, "/api/v1/customer-assistant/faqs/help-0000000000", "customer-app").status())
        .isEqualTo(404);
  }

  @Test
  void searchesQuestionAndAnswerBySubstringIgnoringCaseAndMarkup() {
    String me = customer("CU-faq-2");
    ingestor.ingest(repo, faqManifest());
    // 질문에서
    assertThat(faqs(me, "?q=" + enc("봉투값")).body().path("items").findValuesAsString("question"))
        .containsExactly("봉투값도 돌려받나요?");
    // 대소문자 무시
    assertThat(faqs(me, "?q=" + enc("wi-fi")).body().path("items").findValuesAsString("question"))
        .containsExactly("Wi-Fi 가 끊겨요");
    // 답에서, 굵게 표시를 넘어(**주문 내역**에서)
    assertThat(
            faqs(me, "?q=" + enc(" 주문 내역에서 ")).body().path("items").findValuesAsString("question"))
        .containsExactly("주문을 취소하고 싶어요");
    // % _ 는 글자 그대로(LIKE 와일드카드가 아님)
    assertThat(faqs(me, "?q=" + enc("%")).body().path("items").findValuesAsString("question"))
        .containsExactly("Wi-Fi 가 끊겨요");
    assertThat(faqs(me, "?q=" + enc("_")).body().path("items")).isEmpty();
    // 분류 거르기 + 검색어
    assertThat(faqs(me, "?category=cancel-test").body().path("items")).hasSize(2);
    assertThat(faqs(me, "?category=cancel-test&q=" + enc("채팅")).body().path("items")).isEmpty();
    assertThat(faqs(me, "?category=nothing").body().path("items")).isEmpty();
    // 분류 목록은 거르기와 상관없이 전체
    assertThat(
            faqs(me, "?q=" + enc("봉투값")).body().path("categories").findValuesAsString("category"))
        .containsExactly("help", "cancel-test");
    // 형식이 틀린 분류·너무 긴 검색어 → 400
    assertThat(faqs(me, "?category=" + enc("Cancel!")).status()).isEqualTo(400);
    assertThat(faqs(me, "?q=" + enc("가".repeat(101))).status()).isEqualTo(400);
    // 모델·임베딩을 부르지 않는다(임베딩이 죽어도 된다)
    embedding.failing.set(true);
    assertThat(faqs(me, "?q=" + enc("봉투값")).status()).isEqualTo(200);
  }

  @Test
  void onlyLoggedInCustomers() {
    ingestor.ingest(repo, faqManifest());
    String guest = TestJwt.token("GS-1", "GUEST", List.of(), List.of(), null);
    String op = TestJwt.operator("op-1", List.of("OPERATOR_ADMIN"), List.of());
    String rider = TestJwt.rider("RD-1");
    for (String t : List.of(guest, op, rider)) {
      assertThat(faqs(t, "").status()).isEqualTo(403);
    }
    String id =
        faqs(customer("CU-faq-3"), "").body().path("items").path(0).path("faqId").asString();
    assertThat(get(guest, "/api/v1/customer-assistant/faqs/" + id, "customer-app").status())
        .isEqualTo(403);
  }
}
