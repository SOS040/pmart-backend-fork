package com.pmart.auth.api;

import com.pmart.auth.api.generated.KeysApi;
import com.pmart.auth.api.generated.model.JwkSet;
import com.pmart.auth.infrastructure.keys.SigningKeyRing;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 KeysApi: JWKS 공개키(ADR-0011). 모든 서비스·게이트웨이가 사용자 토큰(고객·운영자·비회원)을 이 공개키로 검증한다. 서비스 토큰 발급은
 * 없앴다(ADR-0026 — 서비스 간 인증 없음).
 */
@RestController
public class KeysController implements KeysApi {

  private final SigningKeyRing keys;

  public KeysController(SigningKeyRing keys) {
    this.keys = keys;
  }

  @Override
  @SuppressWarnings("unchecked")
  public ResponseEntity<JwkSet> getJwks() {
    List<Map<String, Object>> list = (List<Map<String, Object>>) keys.publicJwks().get("keys");
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)))
        .body(new JwkSet().keys(list));
  }
}
