package com.pmart.mailprovisioner.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.mailprovisioner.infrastructure.AccountsFile;
import java.nio.charset.StandardCharsets;
import org.apache.commons.codec.digest.Sha2Crypt;
import org.junit.jupiter.api.Test;

/** 본인 비밀번호 바꾸기의 현재 비밀번호 확인 — 이 서비스가 만든 해시와 rounds 가 붙은 해시(관리자 setup 명령 등) 모두. */
class MailboxPasswordMatchTest {

  @Test
  void matchesOwnHash() {
    String stored = MailboxService.hash("correct-horse-1");
    assertThat(MailboxService.matches("correct-horse-1", stored)).isTrue();
    assertThat(MailboxService.matches("correct-horse-2", stored)).isFalse();
    assertThat(MailboxService.matches("", stored)).isFalse();
  }

  @Test
  void matchesHashWithRounds() {
    String crypt =
        Sha2Crypt.sha512Crypt(
            "correct-horse-1".getBytes(StandardCharsets.UTF_8), "$6$rounds=10000$abcdefgh12345678");
    assertThat(MailboxService.matches("correct-horse-1", AccountsFile.SCHEME + crypt)).isTrue();
    assertThat(MailboxService.matches("nope-nope-nope", AccountsFile.SCHEME + crypt)).isFalse();
  }

  @Test
  void disabledOrOtherSchemeNeverMatches() {
    String stored = MailboxService.hash("correct-horse-1");
    String disabled = AccountsFile.SCHEME + "!" + stored.substring(AccountsFile.SCHEME.length());
    assertThat(MailboxService.matches("correct-horse-1", disabled)).isFalse();
    assertThat(MailboxService.matches("correct-horse-1", "{PLAIN}correct-horse-1")).isFalse();
  }
}
