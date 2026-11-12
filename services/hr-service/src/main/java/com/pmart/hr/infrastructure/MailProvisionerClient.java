package com.pmart.hr.infrastructure;

import com.pmart.hr.application.HrProperties;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * mail-provisioner(사서함 사이드카, 계약 {@code contracts/openapi/mail-provisioner.yaml}) 호출. 서비스 간
 * 경로(/internal — 토큰 없음, ADR-0026). 비밀번호는 로그·예외 메시지에 싣지 않는다.
 */
public class MailProvisionerClient {

  /** 닿지 않음·5xx — 다시 시도. */
  public static class Unavailable extends RuntimeException {
    public Unavailable(String message, @Nullable Throwable cause) {
      super(message, cause);
    }
  }

  /** 거절(4xx). */
  public static class Rejected extends RuntimeException {
    private final String code;

    public Rejected(String code) {
      super(code);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }

  private final RestClient http;

  public MailProvisionerClient(HrProperties.MailProvisioner props, String sourceSystem) {
    JdkClientHttpRequestFactory f =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Objects.requireNonNull(props.timeout()))
                .build());
    f.setReadTimeout(Objects.requireNonNull(props.timeout()));
    this.http =
        RestClient.builder()
            .baseUrl(
                Objects.requireNonNull(props.baseUrl()).replaceAll("/+$", "") + "/api/v1/internal")
            .defaultHeader("X-Source-System", sourceSystem)
            // 명령은 모두 멱등이라 요청마다 새 키(공통 헤더 규칙)
            .requestInterceptor(
                (req, body, ex) -> {
                  req.getHeaders().set("Idempotency-Key", java.util.UUID.randomUUID().toString());
                  return ex.execute(req, body);
                })
            .requestFactory(f)
            .build();
  }

  /** 만들기(멱등). */
  public void create(String address) {
    run(
        () ->
            http.post()
                .uri("/mailboxes")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("address", address))
                .retrieve()
                .toBodilessEntity());
  }

  /** 메일 서버가 이 주소로 오는 메일을 받는가. */
  public boolean receiving(String address) {
    Map<?, ?> m = run(() -> http.get().uri("/mailboxes/{a}", address).retrieve().body(Map.class));
    return m != null && Boolean.TRUE.equals(m.get("receiving"));
  }

  public void disable(String address) {
    run(() -> http.post().uri("/mailboxes/{a}/disable", address).retrieve().toBodilessEntity());
  }

  public void enable(String address) {
    run(() -> http.post().uri("/mailboxes/{a}/enable", address).retrieve().toBodilessEntity());
  }

  public void setPassword(String address, String password) {
    run(
        () ->
            http.put()
                .uri("/mailboxes/{a}/password", address)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("password", password))
                .retrieve()
                .toBodilessEntity());
  }

  private <T> T run(java.util.function.Supplier<T> s) {
    try {
      return s.get();
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().is4xxClientError()) {
        throw new Rejected("MAILBOX_" + e.getStatusCode().value());
      }
      throw new Unavailable("mail-provisioner " + e.getStatusCode().value(), e);
    } catch (RestClientException e) {
      throw new Unavailable("mail-provisioner 에 닿지 않음", e);
    }
  }
}
