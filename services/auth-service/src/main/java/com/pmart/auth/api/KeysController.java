package com.pmart.auth.api;

import com.pmart.auth.api.generated.KeysApi;
import com.pmart.auth.api.generated.model.JwkSet;
import com.pmart.auth.api.generated.model.ServiceTokenResponse;
import com.pmart.auth.application.ServiceTokenIssuer;
import com.pmart.auth.infrastructure.keys.SigningKeyRing;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 KeysApi: JWKS 공개키와 서비스 토큰 발급(ADR-0011). 요청·응답 변환만 하고 판정은 {@link ServiceTokenIssuer}.
 *
 * <p>클라이언트 인증 헤더({@code Authorization: Basic})는 OpenAPI 상 파라미터로 선언할 수 없어 요청에서 직접 읽는다.
 */
@RestController
public class KeysController implements KeysApi {

  private final SigningKeyRing keys;
  private final ServiceTokenIssuer issuer;
  private final HttpServletRequest request;

  public KeysController(
      SigningKeyRing keys, ServiceTokenIssuer issuer, HttpServletRequest request) {
    this.keys = keys;
    this.issuer = issuer;
    this.request = request;
  }

  @Override
  @SuppressWarnings("unchecked")
  public ResponseEntity<JwkSet> getJwks() {
    List<Map<String, Object>> list = (List<Map<String, Object>>) keys.publicJwks().get("keys");
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)))
        .body(new JwkSet().keys(list));
  }

  @Override
  public ResponseEntity<ServiceTokenResponse> issueServiceToken(String grantType) {
    String[] credentials = basicCredentials(request.getHeader(HttpHeaders.AUTHORIZATION));
    ServiceTokenIssuer.IssuedToken token = issuer.issue(credentials[0], credentials[1], grantType);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(
            new ServiceTokenResponse()
                .accessToken(token.tokenValue())
                .tokenType(ServiceTokenResponse.TokenTypeEnum.BEARER)
                .expiresIn(Math.toIntExact(token.expiresInSeconds())));
  }

  /** RFC 6749 2.3.1: client_id·secret 은 form-urlencoded 후 Basic. 형식이 틀리면 invalid_client. */
  private static String[] basicCredentials(String header) {
    if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
      throw new ServiceTokenIssuer.InvalidClientException();
    }
    String decoded;
    try {
      decoded =
          new String(
              Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new ServiceTokenIssuer.InvalidClientException();
    }
    int colon = decoded.indexOf(':');
    if (colon <= 0) {
      throw new ServiceTokenIssuer.InvalidClientException();
    }
    return new String[] {
      URLDecoder.decode(decoded.substring(0, colon), StandardCharsets.UTF_8),
      URLDecoder.decode(decoded.substring(colon + 1), StandardCharsets.UTF_8)
    };
  }
}
