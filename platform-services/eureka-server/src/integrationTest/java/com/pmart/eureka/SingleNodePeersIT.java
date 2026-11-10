package com.pmart.eureka;

import static org.assertj.core.api.Assertions.assertThat;

import com.netflix.eureka.cluster.PeerEurekaNodes;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 단일 노드 — 자기 자신을 복제 대상(peer)으로 잡지 않는다(2026-11-11 LAN).
 *
 * <p>LAN 설정은 defaultZone 을 인증 정보 없는 {@code http://localhost:8761/eureka/} 로 덮고(비밀번호가 로그에 찍히지 않게),
 * 인스턴스는 {@code prefer-ip-address}·PC IP 로 자신을 안다. 그러면 Eureka 가 localhost 를 남으로 보고 모든 등록·갱신을 그 주소로
 * 복제하다 401(Basic 인증 없음)로 버린다 — 10분에 로그 1,300줄(2026-11-11 LAN). {@code eureka.server.my-url} 을 같은
 * 주소로 두면 자기 주소로 알아 peer 가 없다.
 */
class SingleNodePeersIT {

  static final String ZONE = "http://localhost:8761/eureka/";

  @Nested
  @SpringBootTest(
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = {
        "eureka.client.service-url.defaultZone=" + ZONE,
        "eureka.instance.hostname=eureka-server",
        "eureka.instance.prefer-ip-address=true",
        "eureka.instance.ip-address=192.168.0.40",
        "eureka.server.my-url=" + ZONE
      })
  class WithMyUrl {
    @Autowired PeerEurekaNodes peers;

    @Test
    void hasNoPeers() {
      assertThat(peers.getPeerEurekaNodes()).isEmpty();
    }
  }

  /** 대조군: my-url 이 없으면 localhost 를 복제 대상으로 잡는다(고치기 전 LAN). */
  @Nested
  @SpringBootTest(
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = {
        "eureka.client.service-url.defaultZone=" + ZONE,
        "eureka.instance.hostname=eureka-server",
        "eureka.instance.prefer-ip-address=true",
        "eureka.instance.ip-address=192.168.0.40"
      })
  class WithoutMyUrl {
    @Autowired PeerEurekaNodes peers;

    @Test
    void treatsLocalhostAsPeer() {
      assertThat(peers.getPeerEurekaNodes()).hasSize(1);
    }
  }
}
