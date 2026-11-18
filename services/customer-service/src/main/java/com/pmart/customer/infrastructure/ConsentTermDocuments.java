package com.pmart.customer.infrastructure;

import com.pmart.customer.domain.ConsentItem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 약관 본문(2026-11-19, 사용자 "그냥 PMart로 해줘 그리고 진행해줘" — 계약 0.6.1). 버전별 Markdown 파일 {@code
 * classpath:consent-terms/<항목>/<버전>.md}. 시연용 초안(법률 검토 전) — 문구를 바꾸면 새 버전 파일과 설정 versions 를 함께 올린다(예전
 * 버전 파일은 지우지 않는다 — 예전에 동의한 버전을 다시 볼 수 있게).
 */
@Component
public class ConsentTermDocuments {

  /** 계약 version 패턴과 같다(경로 이탈 방지). */
  private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9._-]{1,32}$");

  public record Document(ConsentItem item, String version, String title, String body) {}

  public Optional<Document> find(ConsentItem item, String version) {
    if (version == null || !VERSION.matcher(version).matches() || version.contains("..")) {
      return Optional.empty();
    }
    ClassPathResource r =
        new ClassPathResource("consent-terms/" + item.name() + "/" + version + ".md");
    if (!r.exists()) {
      return Optional.empty();
    }
    try (InputStream in = r.getInputStream()) {
      String body = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
      return Optional.of(new Document(item, version, title(body, item), body));
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  public boolean exists(ConsentItem item, String version) {
    return find(item, version).isPresent();
  }

  private static String title(String body, ConsentItem item) {
    for (String line : body.split("\n")) {
      if (line.startsWith("# ")) {
        return line.substring(2).strip();
      }
    }
    return item.name();
  }
}
