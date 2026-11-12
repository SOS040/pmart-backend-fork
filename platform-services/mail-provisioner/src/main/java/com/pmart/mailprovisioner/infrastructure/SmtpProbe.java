package com.pmart.mailprovisioner.infrastructure;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 메일 서버가 그 주소로 오는 메일을 받는지 SMTP 로 확인한다(EHLO → MAIL FROM:&lt;&gt; → RCPT TO → QUIT). 본문(DATA)은 보내지
 * 않는다. RCPT 가 2xx 면 받는다. 같은 Docker 네트워크라 인증·TLS 없이(메일 서버 PERMIT_DOCKER).
 */
public class SmtpProbe {

  /** 메일 서버에 닿지 않음. */
  public static class Unavailable extends RuntimeException {
    public Unavailable(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private final String host;
  private final int port;
  private final String helo;
  private final Duration timeout;

  public SmtpProbe(String host, int port, String helo, Duration timeout) {
    this.host = host;
    this.port = port;
    this.helo = helo;
    this.timeout = timeout;
  }

  public boolean accepts(String address) {
    try (Socket s = new Socket()) {
      s.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
      s.setSoTimeout((int) timeout.toMillis());
      BufferedReader in =
          new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
      OutputStream out = s.getOutputStream();
      expect(in);
      send(out, "EHLO " + helo);
      expect(in);
      send(out, "MAIL FROM:<>");
      expect(in);
      send(out, "RCPT TO:<" + address + ">");
      char rcpt = reply(in);
      send(out, "QUIT");
      return rcpt == '2';
    } catch (IOException e) {
      throw new Unavailable("메일 서버에 닿지 않음", e);
    }
  }

  private static void send(OutputStream out, String line) throws IOException {
    out.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
    out.flush();
  }

  private static void expect(BufferedReader in) throws IOException {
    char c = reply(in);
    if (c != '2') {
      throw new IOException("SMTP 응답 " + c + "xx");
    }
  }

  /** 여러 줄 응답("250-…")은 마지막 줄("250 …")까지 읽고 첫 숫자를 돌려준다. */
  private static char reply(BufferedReader in) throws IOException {
    String line;
    do {
      line = in.readLine();
      if (line == null || line.length() < 3) {
        throw new IOException("SMTP 연결이 끊김");
      }
    } while (line.length() > 3 && line.charAt(3) == '-');
    return line.charAt(0);
  }
}
