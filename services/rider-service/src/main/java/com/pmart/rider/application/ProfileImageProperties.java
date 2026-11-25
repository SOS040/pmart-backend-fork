package com.pmart.rider.application;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code pmart.rider.profile-image.*} — 제공자 프로필 사진 사본(2026-11-25, 고객과 같은 기술 설정값).
 *
 * @param allowedHostSuffixes 받아도 되는 호스트(하위 도메인 포함) — 구글·카카오·네이버 사진 호스트
 * @param allowHttp http 허용(시험만)
 * @param sizePx 구글 사진 주소에 붙이는 크기
 * @param maxBytes 본문 상한
 */
@ConfigurationProperties(prefix = "pmart.rider.profile-image")
public record ProfileImageProperties(
    @DefaultValue({"googleusercontent.com", "kakaocdn.net", "pstatic.net"})
        List<String> allowedHostSuffixes,
    @DefaultValue("false") boolean allowHttp,
    @DefaultValue("256") int sizePx,
    @DefaultValue("1048576") int maxBytes,
    @DefaultValue("2s") Duration connectTimeout,
    @DefaultValue("5s") Duration readTimeout) {}
