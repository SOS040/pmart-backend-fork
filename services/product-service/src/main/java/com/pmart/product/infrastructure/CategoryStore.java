package com.pmart.product.infrastructure;

import com.pmart.product.domain.CategoryTree;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 카테고리 트리·필터 버전 저장(JDBC). 버전 내용은 한 번 쓰면 바꾸지 않고 상태만 옮긴다. */
@Repository
public class CategoryStore {

  public record VersionRow(
      int version,
      String state,
      @Nullable String sourceNote,
      String draftHash,
      @Nullable String approvalId,
      @Nullable Instant effectiveFrom,
      String createdBy,
      Instant createdAt) {}

  public record NodeRow(
      String categoryId,
      @Nullable String parentId,
      String level,
      @Nullable String section,
      String name,
      String path,
      int sortOrder,
      boolean exposed) {}

  public record GroupRow(
      String filterGroupId,
      String name,
      String selectionMode,
      boolean common,
      List<String> options) {}

  private final JdbcTemplate jdbc;

  public CategoryStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public int nextVersion() {
    Integer v =
        jdbc.queryForObject(
            "SELECT COALESCE(MAX(version), 0) + 1 FROM category_tree_version", Integer.class);
    return v == null ? 1 : v;
  }

  public void insert(
      int version,
      CategoryTree.Parsed tree,
      @Nullable String note,
      String hash,
      String by,
      Instant at) {
    jdbc.update(
        "INSERT INTO category_tree_version (version, state, source_note, draft_hash, created_by, created_at)"
            + " VALUES (?, 'DRAFT', ?, ?, ?, ?)",
        version,
        note,
        hash,
        by,
        Timestamp.from(at));
    jdbc.batchUpdate(
        "INSERT INTO filter_group (version, filter_group_id, name, selection_mode, common) VALUES (?,?,?,?,?)",
        tree.filterGroups(),
        500,
        (ps, g) -> {
          ps.setInt(1, version);
          ps.setString(2, g.filterGroupId());
          ps.setString(3, g.name());
          ps.setString(4, g.selectionMode().name());
          ps.setBoolean(5, g.common());
        });
    List<Object[]> options =
        tree.filterGroups().stream()
            .flatMap(
                g ->
                    java.util.stream.IntStream.range(0, g.options().size())
                        .mapToObj(
                            i -> new Object[] {version, g.filterGroupId(), i, g.options().get(i)}))
            .toList();
    jdbc.batchUpdate(
        "INSERT INTO filter_option (version, filter_group_id, option_seq, name) VALUES (?,?,?,?)",
        options);
    jdbc.batchUpdate(
        "INSERT INTO category_node (version, category_id, parent_id, level, section, name, path, sort_order, exposed)"
            + " VALUES (?,?,?,?,?,?,?,?, true)",
        tree.nodes(),
        500,
        (ps, n) -> {
          ps.setInt(1, version);
          ps.setString(2, n.categoryId());
          ps.setString(3, n.parentId());
          ps.setString(4, n.level().name());
          ps.setString(5, n.section());
          ps.setString(6, n.name());
          ps.setString(7, n.path());
          ps.setInt(8, n.sortOrder());
        });
    jdbc.batchUpdate(
        "INSERT INTO category_filter (version, category_id, filter_group_id, sort_order) VALUES (?,?,?,?)",
        tree.nodeFilters(),
        500,
        (ps, f) -> {
          ps.setInt(1, version);
          ps.setString(2, f.categoryId());
          ps.setString(3, f.filterGroupId());
          ps.setInt(4, f.sortOrder());
        });
  }

  public Optional<VersionRow> find(int version) {
    return jdbc
        .query(
            "SELECT * FROM category_tree_version WHERE version = ?",
            (rs, n) -> version(rs),
            version)
        .stream()
        .findFirst();
  }

  public Optional<VersionRow> effective() {
    return jdbc
        .query(
            "SELECT * FROM category_tree_version WHERE state = 'EFFECTIVE'", (rs, n) -> version(rs))
        .stream()
        .findFirst();
  }

  /** 상태 이동(조건부). 바뀐 행이 없으면 false. */
  public boolean moveState(int version, String from, String to) {
    return jdbc.update(
            "UPDATE category_tree_version SET state = ? WHERE version = ? AND state = ?",
            to,
            version,
            from)
        == 1;
  }

  /** 기존 발효 버전을 SUPERSEDED 로 내리고 이 버전을 발효한다(같은 트랜잭션). */
  public void makeEffective(int version, String approvalId, Instant at) {
    jdbc.update("UPDATE category_tree_version SET state = 'SUPERSEDED' WHERE state = 'EFFECTIVE'");
    jdbc.update(
        "UPDATE category_tree_version SET state = 'EFFECTIVE', approval_id = ?, effective_from = ? WHERE version = ?",
        approvalId,
        Timestamp.from(at),
        version);
  }

  public void markRejected(int version, String approvalId) {
    jdbc.update(
        "UPDATE category_tree_version SET state = 'REJECTED', approval_id = ? WHERE version = ? AND state = 'SUBMITTED'",
        approvalId,
        version);
  }

  public List<NodeRow> nodes(int version) {
    return jdbc.query(
        "SELECT * FROM category_node WHERE version = ? ORDER BY level, parent_id NULLS FIRST, sort_order",
        (rs, n) ->
            new NodeRow(
                rs.getString("category_id"),
                rs.getString("parent_id"),
                rs.getString("level"),
                rs.getString("section"),
                rs.getString("name"),
                rs.getString("path"),
                rs.getInt("sort_order"),
                rs.getBoolean("exposed")),
        version);
  }

  public Map<String, Integer> countsByLevel(int version) {
    Map<String, Integer> out = new java.util.LinkedHashMap<>();
    for (CategoryTree.Level l : CategoryTree.Level.values()) {
      out.put(l.name(), 0);
    }
    jdbc.query(
        "SELECT level, COUNT(*) c FROM category_node WHERE version = ? GROUP BY level",
        rs -> {
          out.put(rs.getString("level"), rs.getInt("c"));
        },
        version);
    return out;
  }

  public int filterGroupCount(int version) {
    Integer c =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM filter_group WHERE version = ?", Integer.class, version);
    return c == null ? 0 : c;
  }

  public boolean nodeExists(int version, String categoryId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM category_node WHERE version = ? AND category_id = ?)",
            Boolean.class,
            version,
            categoryId));
  }

  public Optional<String> nodeByPath(int version, String path) {
    return jdbc
        .queryForList(
            "SELECT category_id FROM category_node WHERE version = ? AND path = ?",
            String.class,
            version,
            path)
        .stream()
        .findFirst();
  }

  public boolean optionExists(int version, String groupId, String option) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM filter_option WHERE version = ? AND filter_group_id = ? AND name = ?)",
            Boolean.class,
            version,
            groupId,
            option));
  }

  /** 노드의 필터 패널(화면 순서). */
  public List<GroupRow> filtersOf(int version, String categoryId) {
    List<GroupRow> groups =
        jdbc.query(
            "SELECT g.filter_group_id, g.name, g.selection_mode, g.common FROM category_filter cf"
                + " JOIN filter_group g ON g.version = cf.version AND g.filter_group_id = cf.filter_group_id"
                + " WHERE cf.version = ? AND cf.category_id = ? ORDER BY cf.sort_order",
            (rs, n) ->
                new GroupRow(
                    rs.getString("filter_group_id"),
                    rs.getString("name"),
                    rs.getString("selection_mode"),
                    rs.getBoolean("common"),
                    List.of()),
            version,
            categoryId);
    return groups.stream()
        .map(
            g ->
                new GroupRow(
                    g.filterGroupId(),
                    g.name(),
                    g.selectionMode(),
                    g.common(),
                    jdbc.queryForList(
                        "SELECT name FROM filter_option WHERE version = ? AND filter_group_id = ? ORDER BY option_seq",
                        String.class,
                        version,
                        g.filterGroupId())))
        .toList();
  }

  private static VersionRow version(ResultSet rs) throws SQLException {
    Timestamp eff = rs.getTimestamp("effective_from");
    return new VersionRow(
        rs.getInt("version"),
        rs.getString("state"),
        rs.getString("source_note"),
        rs.getString("draft_hash"),
        rs.getString("approval_id"),
        eff == null ? null : eff.toInstant(),
        rs.getString("created_by"),
        rs.getTimestamp("created_at").toInstant());
  }
}
