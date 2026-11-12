#!/usr/bin/env bash
# LAN 개발용 HTTPS 사설 CA (docs/architecture/lan-https-private-ca.md, 2026-11-12 결정 T1~T6).
# lan.sh 가 source 한다. PC 1 Git Bash 에서 openssl 로만 만든다(새 도구 없음).
#   루트 CA 개인키  deploy/lan/.secrets/tls/pmart-lan-root-ca.key   (비밀 — git 제외, 10년)
#   루트 CA 인증서  deploy/lan/tls/pmart-lan-root-ca.crt            (공개값 — 저장소에 커밋, 기기·앱에 설치)
#   PC 서버 인증서  deploy/lan/.secrets/tls/<pc>.crt · <pc>.key       (1년, SAN = 그 PC 의 LAN IP + 컴퓨터 이름 + extra-sans.conf)
# Git Bash 는 "/O=…" 같은 인자를 Windows 경로로 바꾸므로 openssl 호출에서 변환을 끈다.

TLS_DIR=deploy/lan/tls
TLS_SECRETS=deploy/lan/.secrets/tls
TLS_CA_KEY=$TLS_SECRETS/pmart-lan-root-ca.key
TLS_CA_CRT=$TLS_DIR/pmart-lan-root-ca.crt

tls_openssl() { MSYS2_ARG_CONV_EXCL='*' openssl "$@"; }

# 루트 CA — 없을 때만 만든다(다시 만들면 모든 기기에 다시 설치해야 한다)
tls_ensure_ca() {
  mkdir -p "$TLS_SECRETS" "$TLS_DIR"
  if [ -f "$TLS_CA_KEY" ]; then
    [ -f "$TLS_CA_CRT" ] || { echo "루트 CA 인증서 없음($TLS_CA_CRT) — 저장소에서 받아 오거나 키와 함께 다시 만들 것" >&2; return 1; }
    return 0
  fi
  [ -f "$TLS_CA_CRT" ] && { echo "루트 CA 인증서는 있는데 개인키가 없음 — 이 PC 가 CA 를 만든 PC(PC 1)인지 확인" >&2; return 1; }
  (umask 077; tls_openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$TLS_CA_KEY" 2>/dev/null)
  tls_openssl req -x509 -new -key "$TLS_CA_KEY" -sha256 -days 3650 \
    -subj "/O=PMart LAN Dev/CN=PMart LAN Dev Root CA $(date +%Y)" \
    -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -addext "subjectKeyIdentifier=hash" \
    -out "$TLS_CA_CRT"
  echo "created 루트 CA: $TLS_CA_CRT (개인키 $TLS_CA_KEY)"
}

# PC 서버 인증서 발급·갱신. 인자: pc 이름, LAN IP, 컴퓨터 이름(ssh HostName)
tls_issue() {
  local pc="$1" ip="$2" host="$3" sans extra tmp
  tls_ensure_ca || return 1
  sans="IP:$ip"
  [ -n "$host" ] && sans="$sans,DNS:$(tr '[:upper:]' '[:lower:]' <<<"$host")" # DNS 이름은 대소문자 구분 없음
  extra="$(awk -v p="$pc" '$1==p { for (i=2;i<=NF;i++) printf ",DNS:%s", $i }' "$TLS_DIR/extra-sans.conf" 2>/dev/null)"
  sans="$sans$extra"
  tmp="$TLS_SECRETS/.issue-$pc"; rm -rf "$tmp"; mkdir -p "$tmp" # 상대 경로 — 경로 변환을 끈 openssl 이 /tmp 를 못 연다
  (umask 077; tls_openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$TLS_SECRETS/$pc.key" 2>/dev/null)
  tls_openssl req -new -key "$TLS_SECRETS/$pc.key" -subj "/O=PMart LAN Dev/CN=$pc ($ip)" -out "$tmp/$pc.csr"
  printf 'basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=%s\nauthorityKeyIdentifier=keyid\nsubjectKeyIdentifier=hash\n' "$sans" > "$tmp/ext"
  tls_openssl x509 -req -in "$tmp/$pc.csr" -CA "$TLS_CA_CRT" -CAkey "$TLS_CA_KEY" -CAcreateserial -CAserial "$TLS_SECRETS/pmart-lan-root-ca.srl" \
    -days 365 -sha256 -extfile "$tmp/ext" -out "$TLS_SECRETS/$pc.crt" 2>/dev/null
  rm -rf "$tmp"
  tls_openssl verify -CAfile "$TLS_CA_CRT" "$TLS_SECRETS/$pc.crt" >/dev/null || { echo "$pc 인증서 검증 실패" >&2; return 1; }
  echo "$pc 인증서: $TLS_SECRETS/$pc.crt  SAN=$sans  만료=$(tls_openssl x509 -in "$TLS_SECRETS/$pc.crt" -noout -enddate | cut -d= -f2)"
}

# 만료 임박(기본 30일) 인증서 이름 출력 — watch.sh 가 쓴다
tls_expiring() {
  local secs="${1:-2592000}" f
  for f in "$TLS_CA_CRT" "$TLS_SECRETS"/*.crt; do
    [ -f "$f" ] || continue
    tls_openssl x509 -in "$f" -noout -checkend "$secs" >/dev/null 2>&1 || echo "$(basename "$f") $(tls_openssl x509 -in "$f" -noout -enddate | cut -d= -f2)"
  done
}

# 루트 CA 를 Windows "신뢰할 수 있는 루트 인증 기관"(LocalMachine\Root)에 넣는 PowerShell — 이미 있으면 그대로(같은 지문)
tls_trust_ps() { # 인자: 그 PC 에서 본 인증서 경로를 만드는 PowerShell 식(예: "(Join-Path \$env:USERPROFILE 'x.crt')"), [결과를 쓸 파일(Windows 경로)]
  local out="${2:-}"
  cat <<EOF
\$p = $1
\$c = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2(\$p)
if (Get-ChildItem Cert:\LocalMachine\Root | Where-Object Thumbprint -eq \$c.Thumbprint) { \$r = "이미 신뢰함: " + \$c.Subject + " (" + \$c.Thumbprint + ")" }
else { Import-Certificate -FilePath \$p -CertStoreLocation Cert:\LocalMachine\Root | Out-Null; \$r = "신뢰 추가: " + \$c.Subject + " (" + \$c.Thumbprint + ")" }
EOF
  if [ -n "$out" ]; then echo "\$r | Out-File -Encoding utf8 '$out'"; else echo "\$r"; fi
}
