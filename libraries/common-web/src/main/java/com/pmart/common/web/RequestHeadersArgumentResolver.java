package com.pmart.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** 컨트롤러 파라미터 {@link RequestHeaders} 주입. 필터가 저장한 속성이 없으면 헤더에서 직접 만든다. */
public class RequestHeadersArgumentResolver implements HandlerMethodArgumentResolver {

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return RequestHeaders.class.equals(parameter.getParameterType());
  }

  @Override
  public @Nullable Object resolveArgument(
      MethodParameter parameter,
      @Nullable ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      @Nullable WebDataBinderFactory binderFactory) {
    HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
    if (request != null) {
      Object attr = request.getAttribute(RequestHeaders.ATTRIBUTE);
      if (attr instanceof RequestHeaders headers) {
        return headers;
      }
    }
    Integer version = null;
    String rawVersion = webRequest.getHeader(RequestHeaders.EXPECTED_VERSION);
    if (rawVersion != null && !rawVersion.isBlank()) {
      try {
        version = Integer.parseInt(rawVersion.trim());
      } catch (NumberFormatException ignored) {
        version = null;
      }
    }
    return new RequestHeaders(
        webRequest.getHeader(RequestHeaders.IDEMPOTENCY_KEY),
        webRequest.getHeader(RequestHeaders.SOURCE_SYSTEM),
        version,
        webRequest.getHeader(RequestHeaders.TRACEPARENT));
  }
}
