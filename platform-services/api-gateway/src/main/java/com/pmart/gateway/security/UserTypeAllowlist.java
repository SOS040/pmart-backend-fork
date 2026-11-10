package com.pmart.gateway.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * 경로별 사용자 종류 허용 목록(ADR-0054 §6.1). 정본은 계약 — 각 작업의 {@code x-pmart-user-types} 를 모아 만든 {@code
 * access/user-type-allowlist.txt}(시험 {@code UserTypeAllowlistFileTest} 가 계약과 같은지 확인하고, {@code
 * PMART_WRITE_ALLOWLIST=1} 이면 다시 쓴다). 손으로 고치지 않는다.
 *
 * <p>한 줄 = {@code 메서드 경로패턴 종류}. 종류는 쉼표로 구분한 사용자 종류, 공개 작업은 {@code PUBLIC}, 계약에 표시가 없으면 {@code
 * UNLABELED}.
 */
public final class UserTypeAllowlist {

  public static final String RESOURCE = "access/user-type-allowlist.txt";
  public static final String PUBLIC = "PUBLIC";
  public static final String UNLABELED = "UNLABELED";

  /** 판정 결과. */
  public enum Verdict {
    /** 허용 목록에 있고 종류도 맞다(공개 작업 포함). */
    ALLOWED,
    /** 그 경로·메서드에 들어올 수 없는 종류. */
    TYPE_NOT_ALLOWED,
    /** 목록에 없는 경로·메서드, 또는 계약에 표시가 없는 작업. */
    NOT_LISTED
  }

  /** 판정 + 맞은 경로 패턴(없으면 null). */
  public record Decision(Verdict verdict, String pattern) {}

  record Entry(HttpMethod method, PathPattern pattern, Set<String> types) {}

  private static final Comparator<Entry> MOST_SPECIFIC_FIRST =
      (a, b) -> PathPattern.SPECIFICITY_COMPARATOR.compare(a.pattern(), b.pattern());

  private final List<Entry> entries;

  UserTypeAllowlist(List<Entry> entries) {
    this.entries = List.copyOf(entries);
  }

  public int size() {
    return entries.size();
  }

  public static UserTypeAllowlist load() {
    InputStream in = UserTypeAllowlist.class.getClassLoader().getResourceAsStream(RESOURCE);
    if (in == null) {
      throw new IllegalStateException("허용 목록 파일이 없습니다: " + RESOURCE);
    }
    try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      return parse(r.lines().toList());
    } catch (IOException e) {
      throw new IllegalStateException("허용 목록을 읽지 못했습니다", e);
    }
  }

  static UserTypeAllowlist parse(List<String> lines) {
    PathPatternParser parser = new PathPatternParser();
    List<Entry> out = new ArrayList<>();
    for (String raw : lines) {
      String line = raw.strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      String[] p = line.split("\\s+");
      if (p.length != 3) {
        throw new IllegalStateException("허용 목록 줄 형식 오류: " + line);
      }
      out.add(new Entry(HttpMethod.valueOf(p[0]), parser.parse(p[1]), Set.of(p[2].split(","))));
    }
    return new UserTypeAllowlist(out);
  }

  /** 이 요청(메서드·경로)을 이 사용자 종류가 불러도 되는가. */
  public Decision decide(HttpMethod method, String path, String userType) {
    PathContainer pc = PathContainer.parsePath(path);
    Optional<Entry> match =
        entries.stream()
            .filter(e -> e.method().equals(method) && e.pattern().matches(pc))
            .min(MOST_SPECIFIC_FIRST);
    if (match.isEmpty()) {
      return new Decision(Verdict.NOT_LISTED, null);
    }
    Entry e = match.get();
    String pattern = e.pattern().getPatternString();
    if (e.types().contains(PUBLIC) || e.types().contains(userType)) {
      return new Decision(Verdict.ALLOWED, pattern);
    }
    if (e.types().contains(UNLABELED)) {
      return new Decision(Verdict.NOT_LISTED, pattern);
    }
    return new Decision(Verdict.TYPE_NOT_ALLOWED, pattern);
  }
}
