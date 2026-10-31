package com.pmart.customer.infrastructure;

import com.pmart.customer.application.ProfileImageProperties;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * 프로필 사진 사본 저장소(ADR-0024). 버킷({@code PMART_S3_BUCKET})이 있을 때만 만든다 — 없으면 사본 기능만 꺼지고 /me 는 사진 없이
 * 응답한다. 버킷이 있는데 자격 증명이 없으면 기동하지 않는다(빈 값으로 대체하지 않음).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({S3Properties.class, ProfileImageProperties.class})
class ProfileImageStorageConfig {

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnExpression("!'${pmart.storage.s3.bucket:}'.isBlank()")
  static class Enabled {

    @Bean(destroyMethod = "close")
    S3Client s3Client(S3Properties p) {
      if (blank(p.accessKey()) || blank(p.secretKey())) {
        throw new IllegalStateException(
            "프로필 사진 저장소 자격 증명이 없습니다: PMART_S3_ACCESS_KEY, PMART_S3_SECRET_KEY 가 필요합니다.");
      }
      S3ClientBuilder b =
          S3Client.builder()
              .region(Region.of(p.region()))
              .credentialsProvider(
                  StaticCredentialsProvider.create(
                      AwsBasicCredentials.create(p.accessKey(), p.secretKey())))
              .forcePathStyle(p.pathStyle());
      if (!blank(p.endpoint())) {
        b.endpointOverride(URI.create(p.endpoint()));
      }
      return b.build();
    }

    @Bean
    S3ProfileImageStorage profileImageStorage(S3Client client, S3Properties p) {
      S3ProfileImageStorage s = new S3ProfileImageStorage(client, p.bucket());
      s.ensureBucket(p.createBucket());
      return s;
    }
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
