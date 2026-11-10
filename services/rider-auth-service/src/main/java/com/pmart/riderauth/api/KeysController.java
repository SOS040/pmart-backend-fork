package com.pmart.riderauth.api;

import com.pmart.riderauth.api.generated.KeysApi;
import com.pmart.riderauth.api.generated.model.JwkSet;
import com.pmart.riderauth.infrastructure.keys.SigningKeyRing;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 KeysApi: 라이더 토큰 공개키(JWKS). 게이트웨이·모든 서비스가 발급자 표의 rider 줄로 이 주소를 가리킨다. */
@RestController
public class KeysController implements KeysApi {

  private final SigningKeyRing keys;

  public KeysController(SigningKeyRing keys) {
    this.keys = keys;
  }

  @Override
  @SuppressWarnings("unchecked")
  public ResponseEntity<JwkSet> getRiderJwks() {
    List<Map<String, Object>> list = (List<Map<String, Object>>) keys.publicJwks().get("keys");
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)))
        .body(new JwkSet().keys(list));
  }
}
