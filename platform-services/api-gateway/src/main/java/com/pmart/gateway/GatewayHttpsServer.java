package com.pmart.gateway;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * HTTPS 입구를 HTTP(server.port) 옆에 하나 더 연다(ADR-0057 — LAN 사설 CA, 클라이언트가 옮기는 동안 HTTP·HTTPS 둘 다). 같은
 * {@link HttpHandler}(보안 체인·라우팅·필터 전부)를 그대로 쓰므로 두 입구의 규칙이 같다.
 *
 * <p>설정 {@code pmart.gateway.https.port}(0 이면 열지 않음)·{@code pmart.gateway.https.bundle}(Spring SSL
 * 번들 이름 — 예 {@code spring.ssl.bundle.pem.<이름>.keystore.certificate/private-key}). Boot 의 server.ssl
 * 은 포트가 하나뿐이라 쓰지 않는다.
 */
public class GatewayHttpsServer implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(GatewayHttpsServer.class);

  /**
   * @param port HTTPS 포트(0 이면 끔, 시험은 무작위 포트를 쓰려고 -1)
   * @param bundle SSL 번들 이름
   */
  @ConfigurationProperties(prefix = "pmart.gateway.https")
  public record Properties(int port, String bundle) {}

  private final Properties properties;
  private final SslBundles bundles;
  private final HttpHandler handler;
  private volatile DisposableServer server;

  public GatewayHttpsServer(Properties properties, SslBundles bundles, HttpHandler handler) {
    this.properties = properties;
    this.bundles = bundles;
    this.handler = handler;
  }

  /** 실제로 열린 포트(시험용). 열리지 않았으면 -1. */
  public int port() {
    DisposableServer s = server;
    return s == null ? -1 : s.port();
  }

  @Override
  public void start() {
    if (properties.bundle() == null || properties.bundle().isBlank()) {
      throw new IllegalStateException("pmart.gateway.https.bundle 이 필요하다(HTTPS 포트를 켰다)");
    }
    SslBundle bundle = bundles.getBundle(properties.bundle());
    SslContext ssl;
    try {
      ssl =
          SslContextBuilder.forServer(bundle.getManagers().getKeyManagerFactory())
              .protocols("TLSv1.3", "TLSv1.2")
              .build();
    } catch (SSLException e) {
      throw new IllegalStateException("HTTPS 인증서를 읽을 수 없다(번들 " + properties.bundle() + ")", e);
    }
    server =
        HttpServer.create()
            .port(Math.max(properties.port(), 0))
            .secure(spec -> spec.sslContext(ssl))
            .handle(new ReactorHttpHandlerAdapter(handler))
            .bindNow();
    log.info("HTTPS 입구 열림: 포트 {}", server.port());
  }

  @Override
  public void stop() {
    DisposableServer s = server;
    if (s != null) {
      s.disposeNow();
      server = null;
    }
  }

  @Override
  public boolean isRunning() {
    return server != null;
  }

  /** HTTP 서버(웹 서버 lifecycle)보다 늦게 열고 먼저 닫는다. */
  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 1;
  }
}
