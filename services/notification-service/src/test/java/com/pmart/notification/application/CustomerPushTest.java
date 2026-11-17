package com.pmart.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.pmart.notification.infrastructure.PushTokenStore;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** 고객 푸시 대상(2026-11-17): 고객 키가 있고 정한 알림 종류일 때만. 키·종류가 없으면 보내지 않는다(기본값을 지어내지 않는다). */
class CustomerPushTest {

  @Test
  void onlyChosenTemplatesWhenCustomerKeyPresent() throws Exception {
    CustomerPush p = push(key("pmart-cust"), List.of("DELIVERY_STARTED", "ORDER_CANCELLED"));
    assertThat(p.pushes("DELIVERY_STARTED")).isTrue();
    assertThat(p.pushes("ORDER_CANCELLED")).isTrue();
    assertThat(p.pushes("ORDER_CONFIRMED")).isFalse(); // 알림함만
  }

  @Test
  void noTemplatesMeansNoPush() throws Exception {
    assertThat(push(key("pmart-cust"), null).pushes("DELIVERY_STARTED")).isFalse();
    assertThat(push(key("pmart-cust"), List.of()).pushes("DELIVERY_STARTED")).isFalse();
  }

  @Test
  void noCustomerKeyMeansNoPush() {
    assertThat(push(null, List.of("DELIVERY_STARTED")).pushes("DELIVERY_STARTED")).isFalse();
    assertThat(push("  ", List.of("DELIVERY_STARTED")).pushes("DELIVERY_STARTED")).isFalse();
  }

  @Test
  void unreadableKeyMeansNoPush() {
    assertThat(push("{not json", List.of("DELIVERY_STARTED")).pushes("DELIVERY_STARTED")).isFalse();
  }

  private static CustomerPush push(@Nullable String customerKey, @Nullable List<String> templates) {
    NotificationProperties props =
        new NotificationProperties(
            null,
            null,
            new NotificationProperties.Fcm(null, customerKey, templates, null, null),
            null,
            null);
    return new CustomerPush(
        props,
        mock(PushTokenStore.class),
        RestClient.builder(),
        new ObjectMapper(),
        Clock.systemUTC());
  }

  private static String key(String projectId) throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
    g.initialize(2048);
    String pem =
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder().encodeToString(g.generateKeyPair().getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
    Map<String, String> k = new LinkedHashMap<>();
    k.put("type", "service_account");
    k.put("project_id", projectId);
    k.put("client_email", "push@" + projectId + ".iam.gserviceaccount.com");
    k.put("private_key", pem);
    return new ObjectMapper().writeValueAsString(k);
  }
}
