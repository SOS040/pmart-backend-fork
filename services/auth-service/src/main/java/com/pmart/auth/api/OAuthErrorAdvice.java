package com.pmart.auth.api;

import com.pmart.auth.api.generated.model.OAuthError;
import com.pmart.auth.application.ServiceTokenIssuer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 서비스 토큰 엔드포인트의 오류는 Problem 이 아니라 RFC 6749 5.2 형식({@code error}). {@link KeysController} 에만 적용. */
@RestControllerAdvice(assignableTypes = KeysController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class OAuthErrorAdvice {

  @ExceptionHandler(ServiceTokenIssuer.InvalidClientException.class)
  ResponseEntity<OAuthError> invalidClient() {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"pmart-service-tokens\"")
        .cacheControl(CacheControl.noStore())
        .body(new OAuthError().error(OAuthError.ErrorEnum.INVALID_CLIENT));
  }

  @ExceptionHandler(ServiceTokenIssuer.UnsupportedGrantTypeException.class)
  ResponseEntity<OAuthError> unsupportedGrant() {
    return ResponseEntity.badRequest()
        .cacheControl(CacheControl.noStore())
        .body(new OAuthError().error(OAuthError.ErrorEnum.UNSUPPORTED_GRANT_TYPE));
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  ResponseEntity<OAuthError> invalidRequest(MissingServletRequestParameterException e) {
    return ResponseEntity.badRequest()
        .cacheControl(CacheControl.noStore())
        .body(
            new OAuthError()
                .error(OAuthError.ErrorEnum.INVALID_REQUEST)
                .errorDescription(e.getParameterName() + " 필요"));
  }
}
