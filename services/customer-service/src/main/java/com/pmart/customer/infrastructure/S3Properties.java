package com.pmart.customer.infrastructure;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code pmart.storage.s3.*} — 프로필 사진 사본 저장소(S3 호환, ADR-0016·0024). 값은 환경변수({@code PMART_S3_*})로만
 * 받는다. 버킷이 비어 있으면 프로필 사진 사본 기능만 꺼진다(로그인·프로필은 그대로). 자격 증명은 로그에 남기지 않는다(toString 에서 가린다).
 *
 * @param endpoint 비우면 AWS 기본 엔드포인트. SeaweedFS 는 {@code http://s3:8333}
 * @param createBucket 버킷이 없을 때 만들지(로컬·시험만). 아니면 기동 실패
 */
@ConfigurationProperties(prefix = "pmart.storage.s3")
public record S3Properties(
    @Nullable String endpoint,
    @DefaultValue("us-east-1") String region,
    @Nullable String bucket,
    @Nullable String accessKey,
    @Nullable String secretKey,
    @DefaultValue("true") boolean pathStyle,
    @DefaultValue("false") boolean createBucket) {

  @Override
  public String toString() {
    return "S3Properties[endpoint="
        + endpoint
        + ", region="
        + region
        + ", bucket="
        + bucket
        + ", pathStyle="
        + pathStyle
        + ", createBucket="
        + createBucket
        + "]";
  }
}
