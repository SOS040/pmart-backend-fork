package com.pmart.mailprovisioner;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.mailprovisioner.infrastructure.AccountsFile;
import com.pmart.mailprovisioner.infrastructure.AccountsFile.Account;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.commons.codec.digest.Sha2Crypt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 계정 파일: 다른 줄을 보존하는지, 비활성(!)·다시 활성이 원래 해시로 돌아오는지, 해시가 Dovecot SHA512-CRYPT 로 검증되는지. */
class AccountsFileTest {

  @TempDir Path dir;

  @Test
  void addDisableEnableKeepsOtherLines() throws Exception {
    Path f = dir.resolve("postfix-accounts.cf");
    Files.write(
        f,
        List.of(
            "no-reply@pmart.com|{SHA512-CRYPT}$6$abc$x",
            "postmaster@pmart.com|{SHA512-CRYPT}$6$def$y"),
        StandardCharsets.UTF_8);
    AccountsFile accounts = new AccountsFile(f);

    accounts.update(
        "gildong@pmart.com", b -> new Account("gildong@pmart.com", "{SHA512-CRYPT}$6$s$h"));
    accounts.update("gildong@pmart.com", b -> b.disable());
    assertThat(Files.readAllLines(f))
        .containsExactly(
            "no-reply@pmart.com|{SHA512-CRYPT}$6$abc$x",
            "postmaster@pmart.com|{SHA512-CRYPT}$6$def$y",
            "gildong@pmart.com|{SHA512-CRYPT}!$6$s$h");
    assertThat(accounts.find("gildong@pmart.com").orElseThrow().disabled()).isTrue();

    accounts.update("gildong@pmart.com", b -> b.enable());
    assertThat(Files.readAllLines(f)).contains("gildong@pmart.com|{SHA512-CRYPT}$6$s$h");
    assertThat(accounts.update("gildong@pmart.com", b -> b.enable()).orElseThrow().disabled())
        .isFalse();
  }

  @Test
  void hashIsVerifiableSha512Crypt() throws Exception {
    var m =
        Class.forName("com.pmart.mailprovisioner.application.MailboxService")
            .getDeclaredMethod("hash", String.class);
    m.setAccessible(true);
    String stored = (String) m.invoke(null, "correct horse battery");
    assertThat(stored).startsWith("{SHA512-CRYPT}$6$");
    String crypt = stored.substring("{SHA512-CRYPT}".length());
    String salt = crypt.substring(0, crypt.lastIndexOf('$'));
    // 같은 salt 로 다시 만들면 같은 값 = Dovecot 이 같은 방식으로 확인할 수 있다
    assertThat(
            Sha2Crypt.sha512Crypt("correct horse battery".getBytes(StandardCharsets.UTF_8), salt))
        .isEqualTo(crypt);
    assertThat(Sha2Crypt.sha512Crypt("wrong".getBytes(StandardCharsets.UTF_8), salt))
        .isNotEqualTo(crypt);
  }
}
