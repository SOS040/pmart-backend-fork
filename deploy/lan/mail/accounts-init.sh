#!/bin/sh
# 사내 메일(ADR-0056) 기본 사서함을 계정 파일에 **없을 때만** 넣는다 — 메일 서버보다 먼저 한 번 도는 컨테이너(mail-accounts-init).
# docker-mailserver 는 계정이 하나도 없으면 Dovecot 이 뜨지 않으므로 첫 기동 전에 넣는다. 직원 사서함 줄(나중에 mail-provisioner·관리자 명령이 넣음)은 건드리지 않는다.
# 해시는 render.sh 가 .secrets 비밀번호로 만든 SHA512-CRYPT(.env, 원문 비밀번호는 넘기지 않는다).
set -eu
f=/tmp/docker-mailserver/postfix-accounts.cf
touch "$f"
add() {
  if grep -q "^$1|" "$f"; then echo "있음: $1"; else printf '%s|{SHA512-CRYPT}%s\n' "$1" "$2" >> "$f"; echo "추가: $1"; fi
}
add no-reply@pmart.com "$PMART_MAIL_NOREPLY_HASH"
add postmaster@pmart.com "$PMART_MAIL_POSTMASTER_HASH"
