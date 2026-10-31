package com.pmart.customer.application;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code pmart.customer.profile-image.*} — 제공자 프로필 사진 사본(ADR-0024). 기술 설정값이다(업무 정책 아님).
 *
 * @param allowedHostSuffixes 받아도 되는 호스트(하위 도메인 포함). 기본 Google 사진 호스트
 * @param allowHttp http 허용(시험만)
 * @param sizePx Google 사진 주소에 붙이는 크기
 * @param maxBytes 본문 상한
 * @param connectTimeout 연결 시간 제한
 * @param readTimeout 응답 시간 제한
 */
@ConfigurationProperties(prefix = "pmart.customer.profile-image")
public record ProfileImageProperties(
    @DefaultValue("googleusercontent.com") List<String> allowedHostSuffixes,
    @DefaultValue("false") boolean allowHttp,
    @DefaultValue("256") int sizePx,
    @DefaultValue("1048576") int maxBytes,
    @DefaultValue("2s") Duration connectTimeout,
    @DefaultValue("5s") Duration readTimeout) {}
