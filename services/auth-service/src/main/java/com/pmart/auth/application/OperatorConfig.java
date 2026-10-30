package com.pmart.auth.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 운영자 로그인(ADR-0018)·고객 소셜 로그인 설정값 등록. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({OperatorProperties.class, CustomerProperties.class})
class OperatorConfig {}
