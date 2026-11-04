package com.pmart.product.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.product.domain.Hashes;
import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** BR-48: 업로드를 메모리에 통째로 올리지 않는다 — 해시는 스트림으로, 읽을 때마다 새로 연다. */
class UploadedBundleTest {

  @Test
  void hashMatchesWholeFileHashAndStreamCanBeReopened() throws Exception {
    byte[] data = new byte[3 * 1024 * 1024 + 7];
    for (int i = 0; i < data.length; i++) {
      data[i] = (byte) (i * 31);
    }
    AtomicInteger opens = new AtomicInteger();
    UploadedBundle b =
        UploadedBundle.from(
            () -> {
              opens.incrementAndGet();
              return new ByteArrayInputStream(data);
            });
    assertThat(b.sha256()).isEqualTo(Hashes.sha256(data)); // 예전 멱등 해시와 같은 값
    try (var in = b.open()) {
      assertThat(in.readAllBytes()).hasSize(data.length);
    }
    assertThat(opens.get()).isEqualTo(2); // 해시 1번 + 읽기 1번 — 바이트를 들고 있지 않는다
  }
}
