package com.pmart.config.cipher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.cloud.context.encrypt.EncryptorFactory;
import org.springframework.security.crypto.encrypt.TextEncryptor;

/**
 * 설정 저장소에 넣을 {@code {cipher}} 값을 만든다(ADR-0027). 서버와 같은 {@link EncryptorFactory}(AES, key+salt)를 쓴다.
 *
 * <p>입력 파일 한 줄 = {@code 키 TAB 평문(Base64) [TAB 기존 암호문]}. 기존 암호문이 같은 평문으로 복호화되면 그대로 돌려준다 — 암호화는 매번
 * 무작위 IV 라 다시 만들면 값이 바뀌어 설정 저장소에 의미 없는 변경이 생기기 때문이다.
 *
 * <p>출력 파일 한 줄 = {@code 키 TAB 암호문}({@code {cipher}} 접두사 없이). 키·salt 는 파일 경로로 받는다(명령줄에 비밀을 두지 않는다).
 * 평문은 출력·로그에 쓰지 않는다.
 *
 * <pre>
 * ./gradlew -q :platform-services:config-server:configCipher \
 *   -PcipherIn=in.tsv -PcipherOut=out.tsv -PcipherKeyFile=encrypt-key -PcipherSaltFile=encrypt-salt
 * </pre>
 */
public final class ConfigCipherTool {

  private ConfigCipherTool() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 4) {
      System.err.println("usage: ConfigCipherTool <in.tsv> <out.tsv> <key-file> <salt-file>");
      System.exit(2);
    }
    TextEncryptor encryptor = encryptor(read(Path.of(args[2])), read(Path.of(args[3])));
    List<String> out = new ArrayList<>();
    int reused = 0;
    int created = 0;
    for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
      if (line.isBlank()) {
        continue;
      }
      String[] f = line.split("\t", -1);
      if (f.length < 2) {
        throw new IllegalArgumentException("형식 오류(키 TAB 평문Base64 [TAB 기존 암호문]): " + f[0]);
      }
      String plain = new String(Base64.getDecoder().decode(f[1]), StandardCharsets.UTF_8);
      String previous = f.length > 2 ? f[2] : "";
      String cipher = reuseOrEncrypt(encryptor, plain, previous);
      if (cipher.equals(previous)) {
        reused++;
      } else {
        created++;
      }
      out.add(f[0] + "\t" + cipher);
    }
    // 줄 끝은 \n 고정(Files.write 는 Windows 에서 \r\n — 셸이 읽으면 암호문 끝에 \r 이 붙어 복호화가 깨진다)
    Files.writeString(
        Path.of(args[1]),
        out.isEmpty() ? "" : String.join("\n", out) + "\n",
        StandardCharsets.UTF_8);
    System.err.println("config cipher: " + created + " encrypted, " + reused + " unchanged");
  }

  static TextEncryptor encryptor(String key, String salt) {
    if (key.isBlank() || salt.isBlank()) {
      throw new IllegalArgumentException("encrypt key·salt 가 비어 있다");
    }
    return new EncryptorFactory(salt).create(key);
  }

  /** 기존 암호문이 같은 평문이면 그대로, 아니면(없음·다른 값·다른 키) 새로 암호화한다. */
  static String reuseOrEncrypt(TextEncryptor encryptor, String plain, String previous) {
    if (!previous.isEmpty()) {
      try {
        if (plain.equals(encryptor.decrypt(previous))) {
          return previous;
        }
      } catch (RuntimeException e) {
        // 다른 키로 만든 값이거나 깨진 값 — 새로 암호화한다
      }
    }
    return encryptor.encrypt(plain);
  }

  private static String read(Path p) throws IOException {
    return Files.readString(p, StandardCharsets.UTF_8).strip();
  }
}
