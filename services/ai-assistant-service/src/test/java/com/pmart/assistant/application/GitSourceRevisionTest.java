package com.pmart.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** LAN 색인 컨테이너처럼 .git 이 없는 문서 사본에서는 REVISION 파일로 출처 커밋을 기록한다(2026-11-15). */
class GitSourceRevisionTest {

  private static final String SHA = "8dca94e71d1650de428a09d77efd029242de5a00";
  private final GitSourceRevision rev = new GitSourceRevision();

  @Test
  void readsCleanRevisionFileWhenNotAGitRepo(@TempDir Path repo) throws IOException {
    Files.writeString(repo.resolve("REVISION"), SHA + "\nclean\n");
    assertThat(rev.revision(repo)).isEqualTo(SHA);
    assertThat(rev.dirty(repo)).isFalse();
  }

  @Test
  void dirtyLineOrMissingStateMeansDirty(@TempDir Path repo) throws IOException {
    Files.writeString(repo.resolve("REVISION"), SHA + "\ndirty\n");
    assertThat(rev.revision(repo)).isEqualTo(SHA);
    assertThat(rev.dirty(repo)).isTrue();
    Files.writeString(repo.resolve("REVISION"), SHA + "\n"); // 옛 형식(커밋만) — 깨끗한지 모름
    assertThat(rev.dirty(repo)).isTrue();
  }

  @Test
  void noFileOrMalformedMeansUnknown(@TempDir Path repo) throws IOException {
    assertThat(rev.revision(repo)).isNull();
    assertThat(rev.dirty(repo)).isTrue();
    Files.writeString(repo.resolve("REVISION"), "not-a-commit\nclean\n");
    assertThat(rev.revision(repo)).isNull();
    assertThat(rev.dirty(repo)).isTrue();
  }
}
