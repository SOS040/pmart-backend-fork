# mail-provisioner

사서함 프로비저닝 사이드카(ADR-0056 결정 10·D9). 업무 서비스가 아니라 사내 메일 서버(docker-mailserver)의 계정 파일을 다루는 플랫폼 구성요소다. 호출자는 hr-service, 그리고 직원 본인 비밀번호 바꾸기만 Roundcube(2026-11-14, 계약 0.2.0).

## 하는 일
- 계약 `contracts/openapi/mail-provisioner.yaml`(`/api/v1/internal/mailboxes…` — 서비스 간 경로, 사용자 토큰 없음, ADR-0026).
- 만들기(멱등, 무작위 비밀번호)·조회(메일을 받는가 — SMTP `RCPT TO`)·비활성·다시 활성·비밀번호. **지우지 않는다**(D7).
- 방식: 메일 서버 설정 볼륨(`mail-config` → `/tmp/docker-mailserver`)의 `postfix-accounts.cf` 에 `주소|{SHA512-CRYPT}$6$…` 한 줄 — `setup email add` 와 같은 결과를 docker.sock 없이. 메일 서버의 변경 감지가 사서함·Dovecot 사용자를 만든다(수 초).
- 비활성 = 해시 앞에 `!`(로그인 불가, 메일은 계속 받음), 다시 활성 = `!` 제거(비활성 전 비밀번호로).
- 비밀번호는 `commons-codec` `Sha2Crypt`(SHA512-CRYPT, 무작위 16자 salt) 해시만 파일에 쓴다. 로그·응답에 비밀번호를 싣지 않는다.

## 규칙
- **본인 비밀번호 바꾸기**(`POST /internal/mailbox-password-changes`, 0.2.0): Roundcube `password` 플러그인 `httpapi` 드라이버가 form `user`·`curpass`·`newpass` 로, 공통 헤더 없이 부른다(`pmart.web.excluded-paths`). 현재 비밀번호의 해시가 맞을 때만 바꾸고(확인·쓰기는 계정 파일 잠금 안에서 한 번에), 없음·비활성·주소 형식·틀림은 모두 같은 403. 새 비밀번호 12~128자(400). Roundcube 설정 `deploy/lan/mail/roundcube-pmart.php`. hr-service 의 `PUT …/password`(확인 없이 정함)는 Roundcube 에 주지 않는다.
- 파일 쓰기는 같은 폴더 임시 파일 → 원자적 이름 바꾸기, 한 프로세스 안에서 직렬(`AccountsFile` synchronized). 한 대만 띄운다.
- 다른 줄(기본 사서함 `no-reply`·`postmaster` — `mail-accounts-init`, 관리자 `setup email add`)은 보존한다.
- DB·Kafka·보안 라이브러리 없음. LAN 포트 없음, Eureka 등록 안 함(lan 프로필) — hr-service 가 같은 PC 5 에서 `http://mail-provisioner:8513` 로 부른다.
- 컨테이너는 root 로 돈다(메일 서버 설정 볼륨 파일 주인이 root). render.sh 가 같은 PC 에 mailserver 가 없으면 렌더를 멈춘다.

## 시험
```bash
./gradlew :platform-services:mail-provisioner:check   # 단위(계정 파일·해시 검증) + 통합(임시 계정 파일·가짜 SMTP)
```
실제 docker-mailserver·Dovecot 로그인 확인은 LAN 배치 때(ADR-0056 5-5).
