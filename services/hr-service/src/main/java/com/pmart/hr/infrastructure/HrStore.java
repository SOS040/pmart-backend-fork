package com.pmart.hr.infrastructure;

import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 직원·사번 순번·계정 발급 진행·요청 권한·변경 기록 저장(JDBC). 비밀번호는 없다. */
@Repository
public class HrStore {

  public record EmployeeRow(
      String operatorId,
      String employeeNo,
      String name,
      String mailId,
      String mailAddress,
      String employmentType,
      String affiliationType,
      String affiliationCode,
      LocalDate startDate,
      @Nullable LocalDate endDate,
      String status,
      @Nullable String statusReason,
      @Nullable String keycloakUserId,
      String iamSync,
      int iamSyncAttempts,
      @Nullable String iamSyncError,
      long version,
      Instant createdAt,
      Instant updatedAt,
      String registeredBy) {}

  public record ProvisioningRow(
      String operatorId,
      String step,
      int attempts,
      @Nullable String lastErrorCode,
      @Nullable Instant nextAttemptAt,
      Instant updatedAt) {}

  public record GrantRow(
      String role,
      List<String> centers,
      @Nullable LocalDate validFrom,
      @Nullable LocalDate validTo,
      @Nullable String reason,
      String submission,
      long grantId,
      @Nullable String approvalRef) {}

  public static final String HQ_DEPARTMENT = "HQ_DEPARTMENT";
  public static final String CENTER = "CENTER";

  private static final String DEPARTMENTS =
      "SELECT d.code, d.name, count(e.operator_id) AS n FROM hq_department d"
          + " LEFT JOIN employee e ON e.affiliation_type = 'HQ_DEPARTMENT'"
          + " AND e.affiliation_code = d.code AND e.status <> 'LEFT'";

  /** 본사 부서(코드·표시 이름·퇴사하지 않은 소속 직원 수). */
  public record DepartmentRow(String code, @Nullable String name, int employeeCount) {}

  private final JdbcTemplate jdbc;

  public HrStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ---------- 사번 ----------

  /** 그 해의 다음 순번(원자적). */
  public int nextEmployeeNo(int year) {
    Integer n =
        jdbc.queryForObject(
            "INSERT INTO employee_no_counter (year, last_no) VALUES (?, 1)"
                + " ON CONFLICT (year) DO UPDATE SET last_no = employee_no_counter.last_no + 1"
                + " RETURNING last_no",
            Integer.class,
            year);
    return n == null ? 1 : n;
  }

  // ---------- 직원 ----------

  public boolean mailIdTaken(String mailId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM employee WHERE mail_id = ?", Integer.class, mailId);
    return n != null && n > 0;
  }

  public void insertEmployee(
      String operatorId,
      String employeeNo,
      String name,
      String mailId,
      String mailAddress,
      String employmentType,
      String affiliationType,
      String affiliationCode,
      LocalDate startDate,
      @Nullable LocalDate endDate,
      String registeredBy,
      Instant at) {
    jdbc.update(
        "INSERT INTO employee (operator_id, employee_no, name, mail_id, mail_address, employment_type,"
            + " affiliation_type, affiliation_code, start_date, end_date, status, iam_sync, registered_by,"
            + " row_version, created_at, updated_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,'ACTIVE','NONE',?,1,?,?)",
        operatorId,
        employeeNo,
        name,
        mailId,
        mailAddress,
        employmentType,
        affiliationType,
        affiliationCode,
        Date.valueOf(startDate),
        endDate == null ? null : Date.valueOf(endDate),
        registeredBy,
        Timestamp.from(at),
        Timestamp.from(at));
  }

  public Optional<EmployeeRow> employee(String operatorId, boolean forUpdate) {
    return jdbc
        .query(
            "SELECT * FROM employee WHERE operator_id = ?" + (forUpdate ? " FOR UPDATE" : ""),
            (rs, i) -> employee(rs),
            operatorId)
        .stream()
        .findFirst();
  }

  /** 사번 내림차순(최근 등록 먼저). cursor = 이전 쪽 마지막 사번. */
  public List<EmployeeRow> list(
      @Nullable String status,
      @Nullable String affiliationCode,
      @Nullable String q,
      @Nullable String cursor,
      int size) {
    StringBuilder sql = new StringBuilder("SELECT * FROM employee WHERE true");
    List<Object> args = new ArrayList<>();
    if (status != null) {
      sql.append(" AND status = ?");
      args.add(status);
    }
    if (affiliationCode != null) {
      sql.append(" AND affiliation_code = ?");
      args.add(affiliationCode);
    }
    if (q != null && !q.isBlank()) {
      String like = q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
      sql.append(" AND (name LIKE ? OR mail_id LIKE ? OR employee_no LIKE ?)");
      args.add(like);
      args.add(like.toLowerCase());
      args.add(like.toUpperCase());
    }
    if (cursor != null) {
      sql.append(" AND employee_no < ?");
      args.add(cursor);
    }
    sql.append(" ORDER BY employee_no DESC LIMIT ?");
    args.add(size);
    return jdbc.query(sql.toString(), (rs, i) -> employee(rs), args.toArray());
  }

  public void setKeycloakUserId(String operatorId, String keycloakUserId, Instant at) {
    jdbc.update(
        "UPDATE employee SET keycloak_user_id = ?, updated_at = ? WHERE operator_id = ?",
        keycloakUserId,
        Timestamp.from(at),
        operatorId);
  }

  /** 재직 상태 변경 + Keycloak·사서함 맞추기 대기. */
  public void updateStatus(
      String operatorId,
      String status,
      @Nullable String reason,
      @Nullable LocalDate startDate,
      @Nullable LocalDate endDate,
      boolean changeDates,
      Instant at) {
    if (changeDates) {
      jdbc.update(
          "UPDATE employee SET status = ?, status_reason = ?, start_date = COALESCE(?, start_date), end_date = ?,"
              + " iam_sync = 'PENDING', iam_sync_attempts = 0, iam_sync_error = NULL, iam_sync_next_at = ?,"
              + " row_version = row_version + 1, updated_at = ? WHERE operator_id = ?",
          status,
          reason,
          startDate == null ? null : Date.valueOf(startDate),
          endDate == null ? null : Date.valueOf(endDate),
          Timestamp.from(at),
          Timestamp.from(at),
          operatorId);
    } else {
      jdbc.update(
          "UPDATE employee SET status = ?, status_reason = ?,"
              + " iam_sync = 'PENDING', iam_sync_attempts = 0, iam_sync_error = NULL, iam_sync_next_at = ?,"
              + " row_version = row_version + 1, updated_at = ? WHERE operator_id = ?",
          status,
          reason,
          Timestamp.from(at),
          Timestamp.from(at),
          operatorId);
    }
  }

  public void updateAffiliation(String operatorId, String type, String code, Instant at) {
    jdbc.update(
        "UPDATE employee SET affiliation_type = ?, affiliation_code = ?, row_version = row_version + 1,"
            + " updated_at = ? WHERE operator_id = ?",
        type,
        code,
        Timestamp.from(at),
        operatorId);
  }

  public void bumpVersion(String operatorId, Instant at) {
    jdbc.update(
        "UPDATE employee SET row_version = row_version + 1, updated_at = ? WHERE operator_id = ?",
        Timestamp.from(at),
        operatorId);
  }

  /** Keycloak·사서함을 지금 재직 상태에 맞추는 일을 남긴다. */
  public void markIamSync(String operatorId, Instant at) {
    jdbc.update(
        "UPDATE employee SET iam_sync = 'PENDING', iam_sync_attempts = 0, iam_sync_error = NULL, iam_sync_next_at = ?"
            + " WHERE operator_id = ?",
        Timestamp.from(at),
        operatorId);
  }

  /** 계약 종료일이 오늘보다 앞인 재직 중 직원(자동 정지 대상). */
  public List<String> contractEnded(LocalDate today) {
    return jdbc.queryForList(
        "SELECT operator_id FROM employee WHERE status = 'ACTIVE' AND end_date IS NOT NULL AND end_date < ?",
        String.class,
        Date.valueOf(today));
  }

  /** Keycloak·사서함 맞추기가 남은 직원(잠금 — 여러 대여도 한 대만). */
  public List<EmployeeRow> lockIamSyncDue(Instant now, int limit) {
    return jdbc.query(
        "SELECT * FROM employee WHERE iam_sync = 'PENDING' AND (iam_sync_next_at IS NULL OR iam_sync_next_at <= ?)"
            + " ORDER BY iam_sync_next_at LIMIT ? FOR UPDATE SKIP LOCKED",
        (rs, i) -> employee(rs),
        Timestamp.from(now),
        limit);
  }

  public void iamSyncDone(String operatorId, Instant at) {
    jdbc.update(
        "UPDATE employee SET iam_sync = 'NONE', iam_sync_error = NULL, iam_sync_next_at = NULL, updated_at = ?"
            + " WHERE operator_id = ?",
        Timestamp.from(at),
        operatorId);
  }

  public void iamSyncFailed(String operatorId, String error, Instant next) {
    jdbc.update(
        "UPDATE employee SET iam_sync_attempts = iam_sync_attempts + 1, iam_sync_error = ?, iam_sync_next_at = ?"
            + " WHERE operator_id = ?",
        error,
        Timestamp.from(next),
        operatorId);
  }

  // ---------- 계정 발급 진행 ----------

  public void insertProvisioning(String operatorId, Instant at) {
    jdbc.update(
        "INSERT INTO provisioning (operator_id, step, attempts, next_attempt_at, updated_at)"
            + " VALUES (?, 'REQUESTED', 0, ?, ?)",
        operatorId,
        Timestamp.from(at),
        Timestamp.from(at));
  }

  public Optional<ProvisioningRow> provisioning(String operatorId, boolean forUpdate) {
    return jdbc
        .query(
            "SELECT * FROM provisioning WHERE operator_id = ?" + (forUpdate ? " FOR UPDATE" : ""),
            (rs, i) -> provisioning(rs),
            operatorId)
        .stream()
        .findFirst();
  }

  /** 진행할 차례인 것(잠금 — 여러 대여도 한 대만). */
  public List<ProvisioningRow> lockDue(Instant now, int limit) {
    return jdbc.query(
        "SELECT * FROM provisioning WHERE step IN ('REQUESTED','MAILBOX_CREATED','IAM_CREATED','INVITE_SENT')"
            + " AND (next_attempt_at IS NULL OR next_attempt_at <= ?)"
            + " ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED",
        (rs, i) -> provisioning(rs),
        Timestamp.from(now),
        limit);
  }

  public void advance(String operatorId, String step, Instant at) {
    jdbc.update(
        "UPDATE provisioning SET step = ?, attempts = 0, last_error_code = NULL, next_attempt_at = ?, updated_at = ?"
            + " WHERE operator_id = ?",
        step,
        Timestamp.from(at),
        Timestamp.from(at),
        operatorId);
  }

  public void failed(
      String operatorId,
      String step,
      int attempts,
      String error,
      @Nullable Instant next,
      Instant at) {
    jdbc.update(
        "UPDATE provisioning SET step = ?, attempts = ?, last_error_code = ?, next_attempt_at = ?, updated_at = ?"
            + " WHERE operator_id = ?",
        step,
        attempts,
        error,
        next == null ? null : Timestamp.from(next),
        Timestamp.from(at),
        operatorId);
  }

  // ---------- 요청 권한 ----------

  public void insertGrant(
      String operatorId,
      String role,
      List<String> centers,
      @Nullable LocalDate validFrom,
      @Nullable LocalDate validTo,
      @Nullable String reason,
      Instant at) {
    jdbc.update(
        "INSERT INTO requested_grant (operator_id, role, centers, valid_from, valid_to, reason, submission, created_at)"
            + " VALUES (?, ?, ?::varchar[], ?, ?, ?, 'PENDING', ?)",
        operatorId,
        role,
        "{" + String.join(",", centers) + "}",
        validFrom == null ? null : Date.valueOf(validFrom),
        validTo == null ? null : Date.valueOf(validTo),
        reason,
        Timestamp.from(at));
  }

  public List<GrantRow> grants(String operatorId) {
    return jdbc.query(
        "SELECT * FROM requested_grant WHERE operator_id = ? ORDER BY grant_id",
        (rs, i) ->
            new GrantRow(
                rs.getString("role"),
                list(rs.getArray("centers")),
                date(rs, "valid_from"),
                date(rs, "valid_to"),
                rs.getString("reason"),
                rs.getString("submission"),
                rs.getLong("grant_id"),
                rs.getString("approval_ref")),
        operatorId);
  }

  /** 아직 올리지 않은 요청 권한을 제출됨으로(같은 요청 ID). */
  public int markGrantsSubmitted(String operatorId, String requestId) {
    return jdbc.update(
        "UPDATE requested_grant SET submission = 'SUBMITTED', request_id = ?"
            + " WHERE operator_id = ? AND submission = 'PENDING'",
        requestId,
        operatorId);
  }

  /** 승인 결과(ApprovalDecided 행) 반영. 이미 결정된 행은 그대로. */
  public int markGrantDecided(
      String operatorId, long grantId, String submission, String approvalRef, Instant at) {
    return jdbc.update(
        "UPDATE requested_grant SET submission = ?, approval_ref = ?, decided_at = ?"
            + " WHERE operator_id = ? AND grant_id = ? AND submission IN ('PENDING', 'SUBMITTED')",
        submission,
        approvalRef,
        Timestamp.from(at),
        operatorId,
        grantId);
  }

  // ---------- 본사 부서 ----------

  /** 처음 보는 본사 부서 코드면 더한다(이름 없음). 이미 있으면 그대로. */
  public void ensureDepartment(String code, String actor, Instant at) {
    jdbc.update(
        "INSERT INTO hq_department (code, name, updated_by, created_at, updated_at)"
            + " VALUES (?, NULL, ?, ?, ?) ON CONFLICT (code) DO NOTHING",
        code,
        actor,
        Timestamp.from(at),
        Timestamp.from(at));
  }

  /** 표시 이름을 정한다(없으면 만든다). */
  public void upsertDepartment(String code, @Nullable String name, String actor, Instant at) {
    jdbc.update(
        "INSERT INTO hq_department (code, name, updated_by, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?) ON CONFLICT (code) DO UPDATE"
            + " SET name = EXCLUDED.name, updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at",
        code,
        name,
        actor,
        Timestamp.from(at),
        Timestamp.from(at));
  }

  /** 코드 오름차순. 직원 수는 소속이 본사 부서이고 퇴사하지 않은 직원. */
  public List<DepartmentRow> departments() {
    return jdbc.query(
        DEPARTMENTS + " GROUP BY d.code, d.name ORDER BY d.code", HrStore::department);
  }

  public Optional<DepartmentRow> department(String code) {
    return jdbc
        .query(DEPARTMENTS + " WHERE d.code = ? GROUP BY d.code, d.name", HrStore::department, code)
        .stream()
        .findFirst();
  }

  private static DepartmentRow department(ResultSet rs, int i) throws SQLException {
    return new DepartmentRow(rs.getString("code"), rs.getString("name"), rs.getInt("n"));
  }

  // ---------- 변경 기록 ----------

  public void insertChange(
      String operatorId,
      String type,
      String changedBy,
      String sourceSystem,
      @Nullable String reason,
      @Nullable String detailJson,
      Instant at) {
    jdbc.update(
        "INSERT INTO employee_change (operator_id, change_type, changed_by, source_system, reason, detail, changed_at)"
            + " VALUES (?,?,?,?,?,?::jsonb,?)",
        operatorId,
        type,
        changedBy,
        sourceSystem,
        reason,
        detailJson,
        Timestamp.from(at));
  }

  // ---------- 변환 ----------

  private static EmployeeRow employee(ResultSet rs) throws SQLException {
    return new EmployeeRow(
        rs.getString("operator_id"),
        rs.getString("employee_no"),
        rs.getString("name"),
        rs.getString("mail_id"),
        rs.getString("mail_address"),
        rs.getString("employment_type"),
        rs.getString("affiliation_type"),
        rs.getString("affiliation_code"),
        rs.getDate("start_date").toLocalDate(),
        date(rs, "end_date"),
        rs.getString("status"),
        rs.getString("status_reason"),
        rs.getString("keycloak_user_id"),
        rs.getString("iam_sync"),
        rs.getInt("iam_sync_attempts"),
        rs.getString("iam_sync_error"),
        rs.getLong("row_version"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getString("registered_by"));
  }

  private static ProvisioningRow provisioning(ResultSet rs) throws SQLException {
    Timestamp next = rs.getTimestamp("next_attempt_at");
    return new ProvisioningRow(
        rs.getString("operator_id"),
        rs.getString("step"),
        rs.getInt("attempts"),
        rs.getString("last_error_code"),
        next == null ? null : next.toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static @Nullable LocalDate date(ResultSet rs, String col) throws SQLException {
    Date d = rs.getDate(col);
    return d == null ? null : d.toLocalDate();
  }

  private static List<String> list(@Nullable Array a) throws SQLException {
    return a == null
        ? List.of()
        : Arrays.stream((Object[]) a.getArray()).map(String::valueOf).toList();
  }
}
