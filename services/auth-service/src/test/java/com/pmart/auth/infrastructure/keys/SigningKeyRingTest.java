package com.pmart.auth.infrastructure.keys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SigningKeyRingTest {

  private static String pkcs8Pem() throws Exception {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    String b64 =
        Base64.getMimeEncoder(64, "\n".getBytes())
            .encodeToString(gen.generateKeyPair().getPrivate().getEncoded());
    return "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----\n";
  }

  @Test
  void noKeysWithoutEphemeralFailsFast() {
    assertThatThrownBy(() -> SigningKeyRing.from(new AuthProperties.Signing(List.of(), false)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Secret Manager");
  }

  @Test
  void firstKeySignsAndAllKeysArePublishedWithoutPrivateParts() throws Exception {
    SigningKeyRing ring =
        SigningKeyRing.from(
            new AuthProperties.Signing(
                List.of(
                    new AuthProperties.Key("2026-10", pkcs8Pem()),
                    new AuthProperties.Key("2026-07", pkcs8Pem())),
                false));

    assertThat(ring.activeKey().getKeyID()).isEqualTo("2026-10");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> keys = (List<Map<String, Object>>) ring.publicJwks().get("keys");
    assertThat(keys).extracting(k -> k.get("kid")).containsExactly("2026-10", "2026-07");
    assertThat(keys).allSatisfy(k -> assertThat(k).doesNotContainKeys("d", "p", "q", "dp", "dq"));
  }

  @Test
  void unreadableKeyFailsWithoutLeakingContent() {
    assertThatThrownBy(
            () ->
                SigningKeyRing.from(
                    new AuthProperties.Signing(
                        List.of(new AuthProperties.Key("k1", "not-a-key-SECRETVALUE")), false)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("k1")
        .hasMessageNotContaining("SECRETVALUE");
  }
}
