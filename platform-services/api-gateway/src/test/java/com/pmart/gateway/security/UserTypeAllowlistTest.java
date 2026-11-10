package com.pmart.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.gateway.security.UserTypeAllowlist.Verdict;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class UserTypeAllowlistTest {

  final UserTypeAllowlist list =
      UserTypeAllowlist.parse(
          List.of(
              "# comment",
              "GET /api/v1/deliveries/{deliveryId} OPERATOR,RIDER",
              "GET /api/v1/deliveries/{deliveryId}/eta CUSTOMER,OPERATOR",
              "POST /api/v1/deliveries/{deliveryId}/completions RIDER",
              "GET /api/v1/storefront/home PUBLIC",
              "GET /api/v1/unlabeled UNLABELED"));

  @Test
  void typeInListIsAllowed() {
    assertThat(list.decide(HttpMethod.GET, "/api/v1/deliveries/D1", "RIDER").verdict())
        .isEqualTo(Verdict.ALLOWED);
  }

  @Test
  void otherTypeIsNotAllowed() {
    var d = list.decide(HttpMethod.POST, "/api/v1/deliveries/D1/completions", "CUSTOMER");
    assertThat(d.verdict()).isEqualTo(Verdict.TYPE_NOT_ALLOWED);
    assertThat(d.pattern()).isEqualTo("/api/v1/deliveries/{deliveryId}/completions");
  }

  @Test
  void mostSpecificPatternWins() {
    assertThat(list.decide(HttpMethod.GET, "/api/v1/deliveries/D1/eta", "CUSTOMER").verdict())
        .isEqualTo(Verdict.ALLOWED);
  }

  @Test
  void methodMustMatch() {
    assertThat(list.decide(HttpMethod.DELETE, "/api/v1/deliveries/D1", "RIDER").verdict())
        .isEqualTo(Verdict.NOT_LISTED);
  }

  @Test
  void publicAndUnlabeled() {
    assertThat(list.decide(HttpMethod.GET, "/api/v1/storefront/home", "CUSTOMER").verdict())
        .isEqualTo(Verdict.ALLOWED);
    assertThat(list.decide(HttpMethod.GET, "/api/v1/unlabeled", "CUSTOMER").verdict())
        .isEqualTo(Verdict.NOT_LISTED);
    assertThat(list.decide(HttpMethod.GET, "/api/v1/nowhere", "CUSTOMER").verdict())
        .isEqualTo(Verdict.NOT_LISTED);
  }

  @Test
  void prefixHidesIds() {
    assertThat(UserTypeAllowlistFilter.prefix("/api/v1/orders/ORD-1/items"))
        .isEqualTo("/api/v1/orders/**");
  }
}
