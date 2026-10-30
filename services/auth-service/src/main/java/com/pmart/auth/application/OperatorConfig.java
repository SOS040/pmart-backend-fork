package com.pmart.auth.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 운영자 로그인 설정값 등록(ADR-0018). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OperatorProperties.class)
class OperatorConfig {}
