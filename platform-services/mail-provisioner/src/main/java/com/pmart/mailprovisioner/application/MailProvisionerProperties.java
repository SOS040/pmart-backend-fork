package com.pmart.mailprovisioner.application;

import java.nio.file.Path;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.mail-provisioner.*}.
 *
 * @param accountsFile docker-mailserver 계정 파일(설정 볼륨의 postfix-accounts.cf). 메일 서버가 바뀐 것을 알아채 사서함을
 *     만든다
 * @param domain 다룰 수 있는 도메인(이 밖의 주소는 400)
 * @param smtpHost 메일을 받는지 확인할 메일 서버(같은 Docker 네트워크)
 * @param smtpPort SMTP 포트(25)
 * @param heloName SMTP 인사 이름
 * @param smtpTimeout SMTP 연결·응답 한도
 */
@ConfigurationProperties(prefix = "pmart.mail-provisioner")
public record MailProvisionerProperties(
    @Nullable Path accountsFile,
    @Nullable String domain,
    @Nullable String smtpHost,
    int smtpPort,
    @Nullable String heloName,
    @Nullable Duration smtpTimeout) {

  public MailProvisionerProperties {
    accountsFile =
        accountsFile == null ? Path.of("/tmp/docker-mailserver/postfix-accounts.cf") : accountsFile;
    domain = domain == null || domain.isBlank() ? "pmart.com" : domain;
    smtpHost = smtpHost == null || smtpHost.isBlank() ? "mailserver" : smtpHost;
    smtpPort = smtpPort <= 0 ? 25 : smtpPort;
    heloName = heloName == null || heloName.isBlank() ? "mail-provisioner.pmart.com" : heloName;
    smtpTimeout = smtpTimeout == null ? Duration.ofSeconds(5) : smtpTimeout;
  }
}
