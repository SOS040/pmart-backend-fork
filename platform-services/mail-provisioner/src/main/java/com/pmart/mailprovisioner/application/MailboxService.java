package com.pmart.mailprovisioner.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mailprovisioner.infrastructure.AccountsFile;
import com.pmart.mailprovisioner.infrastructure.AccountsFile.Account;
import com.pmart.mailprovisioner.infrastructure.SmtpProbe;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.apache.commons.codec.digest.Sha2Crypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 사서함 만들기·비활성·다시 활성·비밀번호(ADR-0056 결정 10). 지우는 일은 없다(D7). 모든 명령은 멱등 — 같은 결과면 파일을 다시 쓰지 않는다. 비밀번호는
 * SHA512-CRYPT(Dovecot 이 읽는 형식, 무작위 16바이트 salt) 해시만 파일에 쓴다. 로그에는 주소만.
 */
@Service
public class MailboxService {

  private static final Logger log = LoggerFactory.getLogger(MailboxService.class);
  private static final Pattern LOCAL = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,62}$");
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String SALT_CHARS =
      "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

  /** 응답용. */
  public record State(String address, boolean disabled, boolean receiving) {}

  private final AccountsFile accounts;
  private final SmtpProbe smtp;
  private final MailProvisionerProperties props;

  public MailboxService(AccountsFile accounts, SmtpProbe smtp, MailProvisionerProperties props) {
    this.accounts = accounts;
    this.smtp = smtp;
    this.props = props;
  }

  /** 없으면 아무도 모르는 무작위 비밀번호로 만든다. 있으면 그대로(비활성이면 다시 활성). */
  public State create(String rawAddress) {
    String address = normalize(rawAddress);
    Account a =
        accounts
            .update(
                address,
                before ->
                    before == null ? new Account(address, hash(randomSecret())) : before.enable())
            .orElseThrow();
    log.info("사서함 만들기(또는 있음): {}", address);
    return state(a);
  }

  public State get(String rawAddress) {
    return state(existing(normalize(rawAddress)));
  }

  public State disable(String rawAddress) {
    String address = normalize(rawAddress);
    existing(address);
    Account a =
        accounts.update(address, before -> before == null ? null : before.disable()).orElseThrow();
    log.info("사서함 비활성: {}", address);
    return state(a);
  }

  public State enable(String rawAddress) {
    String address = normalize(rawAddress);
    existing(address);
    Account a =
        accounts.update(address, before -> before == null ? null : before.enable()).orElseThrow();
    log.info("사서함 다시 활성: {}", address);
    return state(a);
  }

  /** 웹메일 비밀번호. 비활성 사서함은 409(먼저 다시 활성). */
  public void setPassword(String rawAddress, String password) {
    String address = normalize(rawAddress);
    if (existing(address).disabled()) {
      throw new PmartException(
          "MAILBOX_DISABLED", HttpStatus.CONFLICT, "비활성 사서함입니다. 먼저 다시 활성하세요.", false, List.of());
    }
    accounts.update(
        address, before -> before == null ? null : new Account(address, hash(password)));
    log.info("사서함 비밀번호 바꿈: {}", address);
  }

  private State state(Account a) {
    boolean receiving;
    try {
      receiving = smtp.accepts(a.address());
    } catch (SmtpProbe.Unavailable e) {
      log.warn("메일 서버 확인 실패: {}", e.getMessage());
      receiving = false;
    }
    return new State(a.address(), a.disabled(), receiving);
  }

  private Account existing(String address) {
    return accounts
        .find(address)
        .orElseThrow(() -> PmartException.notFound("MAILBOX_NOT_FOUND", "사서함이 없습니다."));
  }

  private String normalize(String raw) {
    String address = raw.strip().toLowerCase(Locale.ROOT);
    int at = address.indexOf('@');
    if (at <= 0
        || !address.substring(at + 1).equals(props.domain())
        || !LOCAL.matcher(address.substring(0, at)).matches()) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED,
          "다룰 수 없는 주소입니다(@" + props.domain() + " 만).",
          List.of(new PmartException.Detail("address", "도메인·형식")));
    }
    return address;
  }

  static String hash(String password) {
    StringBuilder salt = new StringBuilder("$6$");
    for (int i = 0; i < 16; i++) {
      salt.append(SALT_CHARS.charAt(RANDOM.nextInt(SALT_CHARS.length())));
    }
    return AccountsFile.SCHEME
        + Sha2Crypt.sha512Crypt(
            password.getBytes(java.nio.charset.StandardCharsets.UTF_8), salt.toString());
  }

  private static String randomSecret() {
    byte[] raw = new byte[24];
    RANDOM.nextBytes(raw);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  }
}
