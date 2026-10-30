package com.pmart.product.content.infrastructure;

import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/** S3 클라이언트(동기). 버킷·자격 증명이 없으면 기동하지 않는다(0/빈 값으로 대체하지 않음). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(S3Properties.class)
class S3StorageConfig {

  @Bean(destroyMethod = "close")
  S3Client s3Client(S3Properties p) {
    if (blank(p.bucket()) || blank(p.accessKey()) || blank(p.secretKey())) {
      throw new IllegalStateException(
          "이미지 저장소 설정이 없습니다: PMART_S3_BUCKET, PMART_S3_ACCESS_KEY, PMART_S3_SECRET_KEY 가 필요합니다.");
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
  S3ImageStorage s3ImageStorage(S3Client client, S3Properties p) {
    S3ImageStorage s = new S3ImageStorage(client, p.bucket());
    s.ensureBucket(p.createBucket());
    return s;
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
