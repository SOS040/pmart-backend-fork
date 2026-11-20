package com.pmart.auth.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 운영자 로그인(ADR-0018)·고객 소셜 로그인 설정값 등록. 주기 작업(탈퇴 소셜 식별값 파기, ADR-0077)을 켠다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({CustomerProperties.class, GuestProperties.class})
@EnableScheduling
class AuthPropertiesConfig {}
