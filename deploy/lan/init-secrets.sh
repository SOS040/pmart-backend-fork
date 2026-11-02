#!/usr/bin/env bash
# LAN 공유 개발 환경용 비밀정보 생성 → deploy/lan/.secrets/ (git 제외). 이미 있으면 그대로 둔다(덮어쓰지 않음).
# - db-password: 각 PC postgres 의 pmart 사용자 비밀번호
# - signing-key.pem / signing-kid: auth-service RS256 서명키(PKCS#8). 재생성하면 발급된 토큰이 모두 무효
# - s3-access-key / s3-secret-key: s3(SeaweedFS) 자격 증명(ADR-0016)
# - operator-admin-login / operator-admin-password / operator-admin-hash: auth-service 첫 관리자(ADR-0018).
#   비밀번호 원문은 이 파일에만 있다(사용자가 읽어 로그인). 서비스에는 해시만 넘긴다. 해시는 BcryptHash.java(spring-security-crypto) 로 만든다.
# - config-encrypt-key / config-encrypt-salt: config-server {cipher} 암호화 키(ADR-0027). 바꾸면 render 가 저장소 값을 모두 다시 암호화한다.
#   salt 는 16진수여야 한다(Encryptors.text).
# - config-password: 서비스 → config-server Basic 인증 비밀번호(사용자 pmart)
# - config-deploy-key(.pub): config-server 가 GitHub 설정 저장소를 읽는 SSH 키(ed25519). 공개키를 저장소 Deploy key(읽기 전용)로 등록한다
# - grafana-admin-password: Grafana 관리자(admin) 비밀번호(ADR-0030). 원문은 이 파일에만 있다
# 이 값들은 LAN 개발 환경 전용이다. 운영은 Secret Manager.
set -euo pipefail
cd "$(dirname "$0")"
umask 077
mkdir -p .secrets
[ -f .secrets/db-password ] || { openssl rand -hex 24 > .secrets/db-password; echo "created db-password"; }
if [ ! -f .secrets/signing-key.pem ]; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out .secrets/signing-key.pem 2>/dev/null
  echo "lan-$(date +%Y%m%d)" > .secrets/signing-kid
  echo "created signing-key.pem (kid $(cat .secrets/signing-kid))"
fi
[ -f .secrets/s3-access-key ] || { echo "pmart-lan-$(openssl rand -hex 4)" > .secrets/s3-access-key; echo "created s3-access-key"; }
[ -f .secrets/s3-secret-key ] || { openssl rand -hex 24 > .secrets/s3-secret-key; echo "created s3-secret-key"; }
[ -f .secrets/operator-admin-login ] || { echo "admin" > .secrets/operator-admin-login; echo "created operator-admin-login"; }
[ -f .secrets/operator-admin-password ] || { openssl rand -base64 18 | tr -d '\r\n/+=' > .secrets/operator-admin-password; echo "created operator-admin-password"; }
if [ ! -f .secrets/operator-admin-hash ]; then
  jar="$(find "$HOME/.gradle/caches/modules-2" -name 'spring-security-crypto-*.jar' ! -name '*sources*' 2>/dev/null | sort | tail -1)"
  [ -n "$jar" ] || { echo "spring-security-crypto jar 없음: 먼저 ./gradlew :services:auth-service:compileJava" >&2; exit 1; }
  tr -d '\r\n' < .secrets/operator-admin-password | java -cp "$jar" BcryptHash.java > .secrets/operator-admin-hash
  grep -q '^{bcrypt}\$2' .secrets/operator-admin-hash || { rm -f .secrets/operator-admin-hash; echo "bcrypt 해시 생성 실패" >&2; exit 1; }
  echo "created operator-admin-hash"
fi
[ -f .secrets/config-encrypt-key ] || { openssl rand -hex 32 > .secrets/config-encrypt-key; echo "created config-encrypt-key"; }
[ -f .secrets/config-encrypt-salt ] || { openssl rand -hex 16 > .secrets/config-encrypt-salt; echo "created config-encrypt-salt"; }
[ -f .secrets/config-password ] || { openssl rand -hex 24 > .secrets/config-password; echo "created config-password"; }
if [ ! -f .secrets/config-deploy-key ]; then
  ssh-keygen -q -t ed25519 -N "" -C "pmart-config-server-lan" -f .secrets/config-deploy-key
  echo "created config-deploy-key — 공개키(.secrets/config-deploy-key.pub)를 GitHub pmart-config 저장소 Deploy key(읽기 전용)로 등록"
fi
[ -f .secrets/grafana-admin-password ] || { openssl rand -base64 18 | tr -d '\r\n/+=' > .secrets/grafana-admin-password; echo "created grafana-admin-password"; }
echo "secrets: $(pwd)/.secrets"
