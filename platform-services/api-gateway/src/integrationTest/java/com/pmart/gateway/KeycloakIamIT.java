package com.pmart.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.ObjectMapper;

/**
 * Keycloak 로그인 화면을 게이트웨이 {@code /iam} 뒤로(ADR-0056 결정 11) — 실제 Keycloak 26.8.1 컨테이너를 공개 주소 {@code
 * http://localhost:<게이트웨이>/iam} 으로 띄우고 브라우저처럼 로그인 화면 → 비밀번호 제출 → redirect(code)까지 게이트웨이를 거쳐 간다.
 *
 * <ul>
 *   <li>로그인은 Keycloak 쿠키가 게이트웨이를 지나야 된다 — {@code /iam} 라우트만 쿠키를 넘긴다({@code
 *       CookieStripGlobalFilter}). 쿠키 없이 같은 제출을 하면 실패하는 것도 본다(이 시험이 쿠키 예외를 실제로 확인한다는 증거).
 *   <li>관리 콘솔·master Realm·계정 화면은 보안 단계에서 거부(denyAll — 익명 401, 토큰 있어도 403. 라우트도 없다).
 *   <li>다른 라우트(PMart API)로는 쿠키가 여전히 가지 않는다(WireMock 업스트림이 받은 요청).
 * </ul>
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "spring.data.redis.url=redis://localhost:1",
      "pmart.gateway.rate-limit.enabled=false",
      "management.tracing.export.enabled=false"
    })
@EnableWireMock(
    @ConfigureWireMock(
        name = "upstream",
        baseUrlProperties = {"pmart.gateway.upstream.employee-auth-service"}))
class KeycloakIamIT {

  /** Keycloak 공개 주소(KC_HOSTNAME)에 게이트웨이 포트가 들어가야 해서 미리 고른다. */
  static final int PORT = freePort();

  @SuppressWarnings("resource")
  static final GenericContainer<?> KEYCLOAK =
      new GenericContainer<>("quay.io/keycloak/keycloak:26.8.1")
          .withCommand("start-dev", "--import-realm")
          .withEnv("KC_HOSTNAME", "http://localhost:" + PORT + "/iam")
          .withEnv("KC_PROXY_HEADERS", "xforwarded")
          .withEnv("KC_HTTP_ENABLED", "true")
          .withCopyFileToContainer(
              MountableFile.forClasspathResource("keycloak/pmart-staff-it-realm.json"),
              "/opt/keycloak/data/import/pmart-staff-realm.json") // 파일 이름 = Realm 이름(26.8)
          .withExposedPorts(8080)
          .waitingFor(
              Wait.forHttp("/realms/pmart-staff")
                  .forPort(8080)
                  .forStatusCode(200)
                  .withStartupTimeout(Duration.ofMinutes(4)));

  static {
    KEYCLOAK.start();
  }

  static int freePort() {
    try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
      return s.getLocalPort();
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("server.port", () -> PORT);
    r.add(
        "pmart.gateway.upstream.keycloak",
        () -> "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080));
  }

  @InjectWireMock("upstream")
  WireMockServer upstream;

  static final String GW = "http://localhost:" + PORT;
  static final Pattern FORM_ACTION =
      Pattern.compile("<form[^>]*id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"");

  /** 쿠키를 들고(이름=값만) 리디렉션을 따라가지 않는 브라우저. */
  static final class Browser {
    final HttpClient client =
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    final Map<String, String> cookies = new LinkedHashMap<>();
    final boolean keepCookies;

    Browser(boolean keepCookies) {
      this.keepCookies = keepCookies;
    }

    HttpResponse<String> get(String url) throws Exception {
      return send(HttpRequest.newBuilder(URI.create(url)).GET());
    }

    HttpResponse<String> postForm(String url, Map<String, String> form) throws Exception {
      StringBuilder b = new StringBuilder();
      form.forEach(
          (k, v) ->
              b.append(b.isEmpty() ? "" : "&")
                  .append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                  .append('=')
                  .append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
      return send(
          HttpRequest.newBuilder(URI.create(url))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString(b.toString())));
    }

    HttpResponse<String> send(HttpRequest.Builder req) throws Exception {
      if (keepCookies && !cookies.isEmpty()) {
        StringBuilder c = new StringBuilder();
        cookies.forEach(
            (k, v) -> c.append(c.isEmpty() ? "" : "; ").append(k).append('=').append(v));
        req.header("Cookie", c.toString());
      }
      HttpResponse<String> r = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
      for (String h : r.headers().allValues("Set-Cookie")) {
        String pair = h.split(";", 2)[0];
        int eq = pair.indexOf('=');
        if (eq > 0) {
          cookies.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
      }
      return r;
    }
  }

  static String authUrl() {
    return GW
        + "/iam/realms/pmart-staff/protocol/openid-connect/auth?client_id=it-login&response_type=code"
        + "&scope=openid&redirect_uri=http%3A%2F%2Flocalhost%3A1%2Fcb&state=s1";
  }

  /** 로그인 화면을 열고 form action 을 돌려준다. */
  static String openLoginPage(Browser b) throws Exception {
    HttpResponse<String> page = b.get(authUrl());
    assertThat(page.statusCode()).as("게이트웨이 경유 로그인 화면").isEqualTo(200);
    Matcher m = FORM_ACTION.matcher(page.body());
    assertThat(m.find()).isTrue();
    return m.group(1).replace("&amp;", "&");
  }

  @Test
  void staffLoginWorksThroughGatewayWithCookies() throws Exception {
    Browser b = new Browser(true);
    String action = openLoginPage(b);
    // Keycloak 이 공개 주소(게이트웨이 /iam)를 알고 화면을 만든다
    assertThat(action).startsWith(GW + "/iam/realms/pmart-staff/login-actions/authenticate");
    assertThat(b.cookies).containsKey("AUTH_SESSION_ID");

    HttpResponse<String> submitted =
        b.postForm(action, Map.of("username", "u1", "password", "pw-u1", "credentialId", ""));
    assertThat(submitted.statusCode()).as("비밀번호 제출 → redirect").isEqualTo(302);
    String location = submitted.headers().firstValue("Location").orElseThrow();
    assertThat(location)
        .startsWith("http://localhost:1/cb?")
        .contains("code=")
        .contains("state=s1");
    // iss 는 공개 주소
    assertThat(location)
        .contains(
            "iss=" + URLEncoder.encode(GW + "/iam/realms/pmart-staff", StandardCharsets.UTF_8));
  }

  @Test
  void withoutCookiesTheSameLoginFails() throws Exception {
    Browser b = new Browser(false);
    String action = openLoginPage(b);
    HttpResponse<String> submitted =
        b.postForm(action, Map.of("username", "u1", "password", "pw-u1", "credentialId", ""));
    // 쿠키가 없으면 Keycloak 은 로그인 시도를 이어 붙이지 못한다(오류 화면) — 게이트웨이가 쿠키를 지우면 이렇게 된다
    boolean redirectedWithCode =
        submitted.statusCode() == 302
            && submitted.headers().firstValue("Location").orElse("").contains("code=");
    assertThat(redirectedWithCode).isFalse();
  }

  @Test
  void discoveryAndResourcesAreServedButAdminPathsAreDenied() throws Exception {
    Browser b = new Browser(true);
    HttpResponse<String> disc =
        b.get(GW + "/iam/realms/pmart-staff/.well-known/openid-configuration");
    assertThat(disc.statusCode()).isEqualTo(200);
    assertThat(new ObjectMapper().readTree(disc.body()).path("issuer").asString())
        .isEqualTo(GW + "/iam/realms/pmart-staff");

    HttpResponse<String> page = b.get(authUrl());
    Matcher css = Pattern.compile("href=\"(/iam/resources/[^\"]+\\.css)\"").matcher(page.body());
    assertThat(css.find()).as("로그인 화면의 CSS 도 /iam 아래").isTrue();
    assertThat(b.get(GW + css.group(1)).statusCode()).isEqualTo(200);

    for (String blocked :
        new String[] {
          "/iam/admin/master/console/",
          "/iam/admin/realms/pmart-staff/users",
          "/iam/realms/master/protocol/openid-connect/auth?client_id=security-admin-console",
          "/iam/realms/master/.well-known/openid-configuration",
          "/iam/realms/pmart-staff/account",
          "/iam/"
        }) {
      // denyAll — 토큰 없는 요청은 401(익명 → 인증 요구), 토큰이 있어도 403. 어느 쪽도 Keycloak 에 닿지 않는다
      assertThat(b.get(GW + blocked).statusCode()).as(blocked).isEqualTo(401);
    }
  }

  @Test
  void otherRoutesStillDoNotForwardCookies() throws Exception {
    upstream.stubFor(
        any(anyUrl()).willReturn(aResponse().withStatus(302).withHeader("Location", "x")));
    HttpResponse<String> r =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(
                            GW + "/api/v1/employee-auth/logins?app=mfc&deviceId=d&codeChallenge=c"))
                    .header("Cookie", "SECRET=1; OTHER=2")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).isEqualTo(302);
    upstream.verify(anyRequestedFor(anyUrl()).withHeader("Cookie", absent()));
  }
}
