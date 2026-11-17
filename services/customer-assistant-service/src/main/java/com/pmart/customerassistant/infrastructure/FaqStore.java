package com.pmart.customerassistant.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** FAQ 항목 읽기(2026-11-18, V2) — ACTIVE 스냅숏 것만(AI 답변이 검색하는 그 스냅숏). 쓰기는 색인({@link SnapshotStore}). */
@Repository
public class FaqStore {

  public record FaqRow(
      String faqId, String category, String categoryTitle, String question, String answer) {}

  public record CategoryRow(String category, String title, int count) {}

  private static final String SNAPSHOT = "snapshot_id = ?";

  private final JdbcTemplate jdbc;

  public FaqStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * FAQ 항목이 있는 ACTIVE 스냅숏. 없거나(색인 전) ACTIVE 에 FAQ 가 없으면(이 표 전의 색인) 빈 값. 한 응답 안의 조회는 모두 이 ID 로 — 그사이
   * 재색인으로 스냅숏이 바뀌어도 섞이지 않는다.
   */
  public Optional<String> activeSnapshotWithFaqs() {
    return jdbc
        .queryForList(
            "SELECT s.snapshot_id FROM index_snapshot s WHERE s.state = 'ACTIVE'"
                + " AND EXISTS (SELECT 1 FROM faq_entry f WHERE f.snapshot_id = s.snapshot_id)",
            String.class)
        .stream()
        .findFirst();
  }

  /**
   * 분류·검색어로 거른 항목(분류 순 → 문서 안 순서).
   *
   * @param needle 이미 소문자로 바꾼 검색어(부분 문자열, null 이면 거르지 않음)
   */
  public List<FaqRow> list(String snapshotId, @Nullable String category, @Nullable String needle) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT faq_id, category, category_title, question, answer FROM faq_entry WHERE "
                + SNAPSHOT);
    List<Object> args = new ArrayList<>();
    args.add(snapshotId);
    if (category != null) {
      sql.append(" AND category = ?");
      args.add(category);
    }
    if (needle != null) {
      // LIKE 대신 strpos — 검색어의 % _ \ 를 특수 문자로 보지 않는다
      sql.append(" AND strpos(search_text, ?) > 0");
      args.add(needle);
    }
    sql.append(" ORDER BY category_order, item_order");
    return jdbc.query(sql.toString(), FaqStore::row, args.toArray());
  }

  public Optional<FaqRow> find(String snapshotId, String faqId) {
    return jdbc
        .query(
            "SELECT faq_id, category, category_title, question, answer FROM faq_entry WHERE "
                + SNAPSHOT
                + " AND faq_id = ?",
            FaqStore::row,
            snapshotId,
            faqId)
        .stream()
        .findFirst();
  }

  /** 분류(분류 순)와 분류마다 전체 항목 수 — 검색어와 상관없이. */
  public List<CategoryRow> categories(String snapshotId) {
    return jdbc.query(
        "SELECT category, category_title, count(*) FROM faq_entry WHERE "
            + SNAPSHOT
            + " GROUP BY category, category_title, category_order ORDER BY category_order",
        (rs, i) -> new CategoryRow(rs.getString(1), rs.getString(2), rs.getInt(3)),
        snapshotId);
  }

  private static FaqRow row(ResultSet rs, int i) throws SQLException {
    return new FaqRow(
        rs.getString("faq_id"),
        rs.getString("category"),
        rs.getString("category_title"),
        rs.getString("question"),
        rs.getString("answer"));
  }
}
