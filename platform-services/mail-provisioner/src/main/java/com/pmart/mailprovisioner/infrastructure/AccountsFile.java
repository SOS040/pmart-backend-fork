package com.pmart.mailprovisioner.infrastructure;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * docker-mailserver 계정 파일({@code postfix-accounts.cf}) — 한 줄 = {@code 주소|{SHA512-CRYPT}$6$…}. 메일
 * 서버의 변경 감지가 이 파일을 보고 사서함·로그인 정보를 다시 만든다(docker.sock 없이 {@code setup email add} 와 같은 결과).
 *
 * <p>비활성은 해시 앞에 {@code !} 를 붙인다 — 그 해시는 맞을 수 없으므로 로그인(웹메일·IMAP)이 안 되고, 주소는 그대로라 메일은 계속 받는다(지우지 않음,
 * D7). 다시 활성은 {@code !} 를 뗀다(비활성 전 비밀번호로 돌아온다). 쓰기는 같은 폴더의 임시 파일 → 원자적 이름 바꾸기, 한 프로세스 안에서 직렬. 이 파일에
 * 직접 손대는 다른 것(관리자 setup 명령, mail-accounts-init)은 줄을 더하기만 한다.
 */
public class AccountsFile {

  public static final String SCHEME = "{SHA512-CRYPT}";
  public static final String DISABLED_MARK = "!";

  /** 한 계정. hash 는 {@code {SHA512-CRYPT}…} 전체(비활성이면 scheme 바로 뒤에 ! 가 있다). */
  public record Account(String address, String hash) {
    public boolean disabled() {
      return hash.startsWith(SCHEME + DISABLED_MARK);
    }

    public Account disable() {
      return disabled()
          ? this
          : new Account(address, SCHEME + DISABLED_MARK + hash.substring(SCHEME.length()));
    }

    public Account enable() {
      return disabled() ? new Account(address, SCHEME + hash.substring(SCHEME.length() + 1)) : this;
    }
  }

  private final Path file;

  public AccountsFile(Path file) {
    this.file = file;
  }

  public synchronized Optional<Account> find(String address) {
    return Optional.ofNullable(read().get(address));
  }

  /** 그 주소의 줄을 바꾼다(없으면 함수가 null 을 받는다). 함수가 null 을 돌려주면 바꾸지 않는다. 바뀐 결과(또는 그대로)를 돌려준다. */
  public synchronized Optional<Account> update(
      String address, UnaryOperator<@Nullable Account> change) {
    Map<String, Account> all = read();
    Account before = all.get(address);
    Account after = change.apply(before);
    if (after == null) {
      return Optional.ofNullable(before);
    }
    if (!after.equals(before)) {
      all.put(address, after);
      write(all.values());
    }
    return Optional.of(after);
  }

  private Map<String, Account> read() {
    Map<String, Account> m = new LinkedHashMap<>();
    if (!Files.exists(file)) {
      return m;
    }
    try {
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        int bar = line.indexOf('|');
        if (line.isBlank() || line.startsWith("#") || bar <= 0) {
          continue;
        }
        String address = line.substring(0, bar).strip().toLowerCase();
        m.put(address, new Account(address, line.substring(bar + 1).strip()));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("계정 파일을 읽을 수 없다", e);
    }
    return m;
  }

  private void write(Iterable<Account> accounts) {
    List<String> lines = new ArrayList<>();
    for (Account a : accounts) {
      lines.add(a.address() + "|" + a.hash());
    }
    try {
      Path dir = file.toAbsolutePath().getParent();
      Path tmp = Files.createTempFile(dir, ".postfix-accounts", ".tmp");
      Files.write(tmp, lines, StandardCharsets.UTF_8);
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new UncheckedIOException("계정 파일을 쓸 수 없다", e);
    }
  }
}
