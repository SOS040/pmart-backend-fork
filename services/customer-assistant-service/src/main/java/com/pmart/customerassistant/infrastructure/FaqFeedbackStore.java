package com.pmart.customerassistant.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** FAQ 답 의견(V3 {@code faq_feedback}, 계약 0.4.0 BR-91) — 한 고객 한 FAQ 하나(마지막 값). */
@Repository
public class FaqFeedbackStore {

  public record Stat(String faqId, int helpful, int notHelpful, Instant lastAt) {}

  private final JdbcTemplate jdbc;

  public FaqFeedbackStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void upsert(String accountId, String faqId, boolean helpful, Instant now) {
    jdbc.update(
        "INSERT INTO faq_feedback (account_id, faq_id, helpful, created_at, updated_at)"
            + " VALUES (?,?,?,?,?) ON CONFLICT (account_id, faq_id)"
            + " DO UPDATE SET helpful = EXCLUDED.helpful, updated_at = EXCLUDED.updated_at",
        accountId,
        faqId,
        helpful,
        Timestamp.from(now),
        Timestamp.from(now));
  }

  public Map<String, Boolean> of(String accountId, Collection<String> faqIds) {
    Map<String, Boolean> out = new HashMap<>();
    if (faqIds.isEmpty()) {
      return out;
    }
    List<Object> args = new ArrayList<>();
    args.add(accountId);
    args.addAll(faqIds);
    jdbc.query(
        "SELECT faq_id, helpful FROM faq_feedback WHERE account_id = ? AND faq_id IN ("
            + String.join(",", java.util.Collections.nCopies(faqIds.size(), "?"))
            + ")",
        rs -> {
          out.put(rs.getString(1), rs.getBoolean(2));
        },
        args.toArray());
    return out;
  }

  public List<Stat> stats() {
    return jdbc.query(
        "SELECT faq_id, count(*) FILTER (WHERE helpful) AS h, count(*) FILTER (WHERE NOT helpful) AS n,"
            + " max(updated_at) AS last_at FROM faq_feedback GROUP BY faq_id"
            + " ORDER BY n DESC, h DESC, faq_id",
        (rs, i) ->
            new Stat(
                rs.getString("faq_id"),
                rs.getInt("h"),
                rs.getInt("n"),
                rs.getTimestamp("last_at").toInstant()));
  }
}
