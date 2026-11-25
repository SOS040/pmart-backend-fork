package com.pmart.cdc.outbound.infrastructure;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 화면 번호(0.21.0, V14) 일괄 조회 — 시스템 ID → 화면 번호. 번호는 DB 열 기본값이 정한다. */
@Repository
public class DisplayNumberStore {

  /** 번호가 붙는 것. 글자는 ADR-0070 덧붙임(2026-11-26). */
  public enum Kind {
    TRF("transfer", "trf_id", "trf_number", "T"),
    WAVE("wave", "wave_id", "wave_number", "W"),
    TASK("task", "task_id", "task_number", "K"),
    DEPARTURE("departure", "departure_id", "departure_number", "S");

    final String table;
    final String idCol;
    final String numCol;
    public final String prefix;

    Kind(String table, String idCol, String numCol, String prefix) {
      this.table = table;
      this.idCol = idCol;
      this.numCol = numCol;
      this.prefix = prefix;
    }
  }

  private final JdbcTemplate jdbc;

  public DisplayNumberStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Map<String, String> numbers(Kind kind, Collection<String> ids) {
    Map<String, String> out = new HashMap<>();
    if (ids.isEmpty()) {
      return out;
    }
    jdbc.query(
        "SELECT "
            + kind.idCol
            + ", "
            + kind.numCol
            + " FROM "
            + kind.table
            + " WHERE "
            + kind.idCol
            + " = ANY (?)",
        rs -> {
          out.put(rs.getString(1), rs.getString(2));
        },
        (Object) ids.stream().distinct().toArray(String[]::new));
    return out;
  }

  /**
   * 찾기 칸 값 → 저장된 화면 번호 모양. {@code T482913}·{@code t482913}·{@code 482913} 을 같은 번호로 본다. 숫자가 아니면
   * null(아무것도 맞지 않게).
   */
  public static @Nullable String normalize(Kind kind, @Nullable String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String v = raw.strip().toUpperCase(java.util.Locale.ROOT);
    if (v.startsWith(kind.prefix)) {
      v = v.substring(1);
    }
    return v.matches("\\d{1,12}") ? kind.prefix + v : "-";
  }
}
