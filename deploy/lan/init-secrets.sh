#!/usr/bin/env bash
# LAN 공유 개발 환경용 비밀정보 생성 → deploy/lan/.secrets/ (git 제외). 이미 있으면 그대로 둔다(덮어쓰지 않음).
# - db-password: 각 PC postgres 의 pmart 사용자 비밀번호
# - signing-key.pem / signing-kid: auth-service RS256 서명키(PKCS#8). 재생성하면 발급된 토큰이 모두 무효
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
echo "secrets: $(pwd)/.secrets"
