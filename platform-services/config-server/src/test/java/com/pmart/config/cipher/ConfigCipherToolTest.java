package com.pmart.config.cipher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.cloud.context.encrypt.EncryptorFactory;
import org.springframework.security.crypto.encrypt.TextEncryptor;

class ConfigCipherToolTest {

  private final TextEncryptor enc = ConfigCipherTool.encryptor("unit-key-0123456789", "0a1b2c3d");

  @Test
  void 서버와_같은_EncryptorFactory_로_복호화된다() {
    String c = ConfigCipherTool.reuseOrEncrypt(enc, "secret", "");
    assertThat(new EncryptorFactory("0a1b2c3d").create("unit-key-0123456789").decrypt(c))
        .isEqualTo("secret");
  }

  @Test
  void 같은_평문이면_기존_암호문을_그대로_쓴다() {
    String first = ConfigCipherTool.reuseOrEncrypt(enc, "secret", "");
    assertThat(ConfigCipherTool.reuseOrEncrypt(enc, "secret", first)).isEqualTo(first);
  }

  @Test
  void 평문이_바뀌거나_다른_키의_값이면_새로_암호화한다() {
    String first = ConfigCipherTool.reuseOrEncrypt(enc, "secret", "");
    String changed = ConfigCipherTool.reuseOrEncrypt(enc, "secret-2", first);
    assertThat(changed).isNotEqualTo(first);
    assertThat(enc.decrypt(changed)).isEqualTo("secret-2");

    String otherKey = ConfigCipherTool.encryptor("other-key-987654", "0a1b2c3d").encrypt("secret");
    String re = ConfigCipherTool.reuseOrEncrypt(enc, "secret", otherKey);
    assertThat(enc.decrypt(re)).isEqualTo("secret");
    assertThat(ConfigCipherTool.reuseOrEncrypt(enc, "secret", "not-hex")).isNotEqualTo("not-hex");
  }

  @Test
  void 파일_입출력_특수문자_평문과_재사용(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("key"), "unit-key-0123456789\n");
    Files.writeString(dir.resolve("salt"), "0a1b2c3d\r\n");
    String hash = "{bcrypt}$2a$10$abc/def"; // $·{} 가 들어간 값
    Files.write(dir.resolve("in.tsv"), List.of("A\t" + b64(hash), "B\t" + b64("x\ty")));
    String[] args = {
      dir.resolve("in.tsv").toString(),
      dir.resolve("out.tsv").toString(),
      dir.resolve("key").toString(),
      dir.resolve("salt").toString()
    };
    ConfigCipherTool.main(args);
    // 셸(render.sh)이 읽으므로 OS 와 관계없이 \n 만(\r 이 붙으면 암호문이 깨진다 — 2026-11-02 Windows 에서 발견)
    assertThat(Files.readString(dir.resolve("out.tsv"))).doesNotContain("\r");
    List<String> out = Files.readAllLines(dir.resolve("out.tsv"));
    assertThat(out).hasSize(2);
    String a = out.get(0).split("\t")[1];
    assertThat(enc.decrypt(a)).isEqualTo(hash);
    assertThat(enc.decrypt(out.get(1).split("\t")[1])).isEqualTo("x\ty");
    assertThat(String.join("\n", out)).doesNotContain("bcrypt");

    Files.write(dir.resolve("in.tsv"), List.of("A\t" + b64(hash) + "\t" + a));
    ConfigCipherTool.main(args);
    assertThat(Files.readAllLines(dir.resolve("out.tsv")).get(0)).isEqualTo("A\t" + a);
  }

  @Test
  void 키나_salt_가_비면_거부() {
    assertThatThrownBy(() -> ConfigCipherTool.encryptor(" ", "0a1b"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static String b64(String s) {
    return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }
}
