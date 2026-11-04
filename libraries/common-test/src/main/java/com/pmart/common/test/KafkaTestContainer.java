package com.pmart.common.test;

import org.testcontainers.kafka.KafkaContainer;

/**
 * 통합 시험용 Kafka 컨테이너(apache/kafka). 모든 시험은 {@code new KafkaContainer(...)} 대신 이것으로 만든다.
 *
 * <p>Testcontainers 2.0 의 기본 시작 명령은 {@code while [ ! -f /tmp/testcontainers_start.sh ]; do sleep
 * 0.1; done; /tmp/testcontainers_start.sh} 다. 스크립트는 컨테이너가 뜬 뒤 복사되는데, 파일이 생기자마자 실행하므로 복사(내용·실행 권한)가
 * 끝나기 전에 실행하면 셸이 126(실행할 수 없음)으로 끝나고 컨테이너가 죽는다. 부하가 클 때(전체 병렬 시험·다른 컨테이너 동시 실행)만 드러난다 — 2026-11-04
 * 전체 check 에서 mfc-capacity ServiceBootIT 가 이렇게 실패했다. 여기서는 스크립트 마지막 줄({@code
 * /etc/kafka/docker/run})까지 쓰인 것을 확인한 뒤 실행 권한이 필요 없게 {@code bash} 로 읽혀 실행한다.
 */
public final class KafkaTestContainer {

  public static final String IMAGE = "apache/kafka:4.1.1";

  /** 시작 스크립트가 다 쓰인 뒤 bash 로 실행한다(위 설명). */
  public static final String SAFE_START =
      "until grep -q /etc/kafka/docker/run /tmp/testcontainers_start.sh 2>/dev/null; do sleep 0.1; done;"
          + " exec bash /tmp/testcontainers_start.sh";

  private KafkaTestContainer() {}

  public static KafkaContainer create() {
    return new KafkaContainer(IMAGE).withCommand("sh", "-c", SAFE_START);
  }
}
