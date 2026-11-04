package com.pmart.assistant.application;

import com.pmart.assistant.infrastructure.ingest.KnowledgeIngestor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * {@code git rev-parse HEAD}·{@code git status --porcelain}. git 이 없거나 실패하면 revision=null,
 * dirty=true(재현 보장 없음).
 */
class GitSourceRevision implements KnowledgeIngestor.SourceRevision {

  @Override
  public @Nullable String revision(Path repo) {
    String out = run(repo, List.of("git", "rev-parse", "HEAD"));
    return out == null || out.isBlank() ? null : out.strip();
  }

  @Override
  public boolean dirty(Path repo) {
    String out = run(repo, List.of("git", "status", "--porcelain"));
    return out == null || !out.isBlank();
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
