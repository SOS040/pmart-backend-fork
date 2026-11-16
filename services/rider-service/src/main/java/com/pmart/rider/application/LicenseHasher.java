package com.pmart.rider.application;

import com.pmart.common.web.PmartException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 운전면허 번호 중복 확인용 키 해시(HMAC-SHA256). 원문은 저장하지 않는다 — 12자리 숫자라 키 없는 해시는 되돌릴 수 있어 서버 키를 쓴다. 키 {@code
 * pmart.rider.license-hash-key}(pmart-config {cipher}, PC 1)가 없으면 면허를 받는 요청만 422 CONFIG_REQUIRED.
 */
@Component
public class LicenseHasher {

  private final @Nullable String key;

  public LicenseHasher(@Value("${pmart.rider.license-hash-key:}") @Nullable String key) {
    this.key = key == null || key.isBlank() ? null : key;
  }

  public String hash(String digits) {
    if (key == null) {
      throw PmartException.configRequired("pmart.rider.license-hash-key");
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(digits.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
