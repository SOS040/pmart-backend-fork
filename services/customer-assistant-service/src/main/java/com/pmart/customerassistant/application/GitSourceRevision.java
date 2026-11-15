package com.pmart.customerassistant.application;

import com.pmart.customerassistant.infrastructure.ingest.KnowledgeIngestor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * {@code git rev-parse HEAD}·{@code git status --porcelain}. git 을 쓸 수 없으면(LAN 색인 컨테이너 — 문서 사본만 있고
 * .git 이 없다) 문서 사본의 {@code REVISION} 파일을 읽는다(PC 1 render.sh 가 씀: 1줄 커밋, 2줄 {@code clean}·{@code
 * dirty} — 2026-11-15). 둘 다 없으면 revision=null, dirty=true(재현 보장 없음).
 */
class GitSourceRevision implements KnowledgeIngestor.SourceRevision {

  @Override
  public @Nullable String revision(Path repo) {
    String out = run(repo, List.of("git", "rev-parse", "HEAD"));
    if (out != null && !out.isBlank()) {
      return out.strip();
    }
    List<String> file = revisionFile(repo);
    return file.isEmpty() ? null : file.get(0);
  }

  @Override
  public boolean dirty(Path repo) {
    String out = run(repo, List.of("git", "status", "--porcelain"));
    if (out != null) {
      return !out.isBlank();
    }
    List<String> file = revisionFile(repo);
    return file.size() < 2 || !"clean".equals(file.get(1));
  }

  /** REVISION 파일의 [커밋, clean|dirty]. 없거나 커밋 모양(16진수 40자)이 아니면 빈 목록. */
  static List<String> revisionFile(Path repo) {
    Path f = repo.resolve("REVISION");
    if (!Files.isRegularFile(f)) {
      return List.of();
    }
    try {
      List<String> lines =
          Files.readAllLines(f, StandardCharsets.UTF_8).stream()
              .map(String::strip)
              .filter(l -> !l.isEmpty())
              .toList();
      if (lines.isEmpty() || !lines.get(0).matches("[0-9a-f]{40}")) {
        return List.of();
      }
      return lines;
    } catch (IOException e) {
      return List.of();
    }
  }

  private static @Nullable String run(Path repo, List<String> cmd) {
    try {
      Process p =
          new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
      byte[] out = p.getInputStream().readAllBytes();
      if (!p.waitFor(20, TimeUnit.SECONDS) || p.exitValue() != 0) {
        return null;
      }
      return new String(out, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }
}
