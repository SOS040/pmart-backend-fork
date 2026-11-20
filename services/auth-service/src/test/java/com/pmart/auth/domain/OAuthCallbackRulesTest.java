package com.pmart.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class OAuthCallbackRulesTest {

  @Test
  void scriptHashInCspMatchesTheScript() throws Exception {
    String h =
        Base64.getEncoder()
            .encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest(OAuthCallbackRules.SCRIPT.getBytes(StandardCharsets.UTF_8)));
    assertThat(OAuthCallbackRules.SCRIPT_SHA256).isEqualTo(h);
    assertThat(OAuthCallbackRules.CSP)
        .contains("'sha256-" + h + "'")
        .contains("default-src 'none'");
  }

  @Test
  void pageLinksToTheFixedAppAddressWithQueryEscapedOnlyForHtml() {
    String location =
        OAuthCallbackRules.location(OAuthCallbackRules.CUSTOMER_NAVER, "code=a%2Bb&state=x\"<y>'");
    String page = OAuthCallbackRules.page(location);
    assertThat(page)
        .contains(
            "href=\"com.pmart.customer://oauth/naver?code=a%2Bb&amp;state=x&quot;&lt;y&gt;&#39;\"")
        .contains("PMart 앱으로 돌아가기")
        .contains("<script>" + OAuthCallbackRules.SCRIPT + "</script>")
        .doesNotContain("x\"<y>");
  }
}
