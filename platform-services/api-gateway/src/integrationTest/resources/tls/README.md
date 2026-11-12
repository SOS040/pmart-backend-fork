# 시험 전용 자체 서명 인증서

`GatewayHttpsIT` 가 HTTPS 입구(`pmart.gateway.https`)를 시험하려고 쓴다. **시험 전용 — 어디에서도 신뢰하지 않는 키이며 비밀값이 아니다.**
LAN 인증서(ADR-0057 사설 CA)와 무관하다. 다시 만들기:

```bash
MSYS2_ARG_CONV_EXCL='*' openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" -keyout it-key.pem -out it-cert.pem
```
