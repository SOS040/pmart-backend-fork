package com.pmart.assistant;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 이 서비스 통합 시험용 PostgreSQL + pgvector. common-test 공용 이미지(postgres:17-alpine)는 바꾸지 않는다 — pgvector 가
 * 필요한 건 이 서비스뿐(ADR-0036). 로컬 전용 compose(deploy/local/ai-assistant)와 같은 이미지.
 *
 * <p>JVM 당 한 번 띄우고(정적 start) 끝날 때 Testcontainers 가 정리한다. 시험 클래스마다 멈추면 클래스 사이에서 공유되는 Spring 컨텍스트가 꺼진
 * 포트를 붙잡는다(2026-11-04 확인).
 */
public abstract class PgvectorContainerSupport {

  public static final DockerImageName IMAGE =
      DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres");

  @ServiceConnection
  protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(IMAGE);

  static {
    POSTGRES.start();
  }
}
