package com.pmart.product.content;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import java.time.Duration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * PostgreSQL + Kafka + S3 호환 저장소(SeaweedFS, deploy/local 과 같은 이미지·mini 모드) 통합 시험 베이스. 시험용 자격 증명은 이
 * 컨테이너 안에서만 쓰는 임의 값이다.
 */
public abstract class ContentContainerSupport extends PostgresKafkaContainerSupport {

  public static final String S3_IMAGE = "chrislusf/seaweedfs:4.48";
  public static final String S3_KEY = "it-access-key";
  public static final String S3_SECRET = "it-secret-key-0123456789";
  public static final String BUCKET = "pmart-product-content-it";

  @SuppressWarnings("resource")
  protected static final GenericContainer<?> S3 =
      new GenericContainer<>(S3_IMAGE)
          .withCreateContainerCmdModifier(
              c ->
                  c.withEntrypoint(
                      "sh",
                      "-c",
                      "printf '{\"identities\":[{\"name\":\"app\",\"credentials\":[{\"accessKey\":\""
                          + S3_KEY
                          + "\",\"secretKey\":\""
                          + S3_SECRET
                          + "\"}],\"actions\":[\"Admin\",\"Read\",\"List\",\"Tagging\",\"Write\"]}]}'"
                          + " > /tmp/s3.json && exec /entrypoint.sh mini -dir=/data"
                          + " -s3.config=/tmp/s3.json -admin.ui=false -s3.port.iceberg=0"
                          + " -s3.port.lance=0"))
          .withExposedPorts(8333)
          .waitingFor(
              Wait.forLogMessage(".*S3\\s+ready.*", 1).withStartupTimeout(Duration.ofMinutes(2)));

  static {
    S3.start();
  }

  protected static String s3Endpoint() {
    return "http://" + S3.getHost() + ":" + S3.getMappedPort(8333);
  }

  @DynamicPropertySource
  static void s3(DynamicPropertyRegistry registry) {
    registry.add("pmart.storage.s3.endpoint", ContentContainerSupport::s3Endpoint);
    registry.add("pmart.storage.s3.bucket", () -> BUCKET);
    registry.add("pmart.storage.s3.access-key", () -> S3_KEY);
    registry.add("pmart.storage.s3.secret-key", () -> S3_SECRET);
    registry.add("pmart.storage.s3.create-bucket", () -> "true");
    // 서비스 신원 토큰(mfc-inventory 내부 가용 호출). 값은 시험용
    registry.add("pmart.security.service-token.client-secret", () -> "it-secret");
  }
}
