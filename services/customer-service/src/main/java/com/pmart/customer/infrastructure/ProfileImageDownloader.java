package com.pmart.customer.infrastructure;

import com.pmart.customer.application.ProfileImageProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 제공자 사진 본문을 받는다(허용 여부는 호출 전에 {@code ProfileImageRules.fetchUri} 로 판정). 리다이렉트는 따라가지 않는다 — 허용한 호스트
 * 밖으로 끌려가지 않게. 상한을 넘으면 읽기를 멈춘다. 실패는 빈 값(다음 로그인 때 다시 받는다).
 */
@Component
public class ProfileImageDownloader {

  private final HttpClient http;
  private final ProfileImageProperties props;

  public ProfileImageDownloader(ProfileImageProperties props) {
    this.props = props;
    this.http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(props.connectTimeout())
            .build();
  }

  public Optional<byte[]> download(URI uri) throws IOException, InterruptedException {
    HttpRequest req = HttpRequest.newBuilder(uri).timeout(props.readTimeout()).GET().build();
    HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream in = res.body()) {
      if (res.statusCode() != 200) {
        return Optional.empty();
      }
      byte[] body = in.readNBytes(props.maxBytes() + 1);
      return body.length > props.maxBytes() ? Optional.empty() : Optional.of(body);
    }
  }
}
