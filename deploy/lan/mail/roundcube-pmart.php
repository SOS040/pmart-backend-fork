<?php
// 사내 메일 웹메일(ADR-0056 결정 9) — roundcube 이미지가 /var/roundcube/config/*.php 를 읽는다.
// 메일 서버(IMAP 143·SMTP 587, STARTTLS)는 LAN 사설 CA(ADR-0057) 인증서를 쓰므로 확인을 끄지 않고 루트 CA 로 확인한다.
$pmartTls = ['ssl' => [
    'verify_peer' => true,
    'verify_peer_name' => true,
    'peer_name' => 'mailserver',
    'cafile' => '/etc/pmart-tls/pmart-lan-root-ca.crt',
]];
$config['imap_conn_options'] = $pmartTls;
$config['smtp_conn_options'] = $pmartTls;
// 로그인에 "gildong" 만 넣어도 gildong@pmart.com 으로
$config['username_domain'] = 'pmart.com';
$config['mail_domain'] = 'pmart.com';
$config['product_name'] = 'PMart 사내 메일';
$config['language'] = 'ko_KR';

// 직원 본인 비밀번호 바꾸기(2026-11-14): 기본으로 들어 있는 password 플러그인을 켜고, 바꾼 비밀번호는 mail-provisioner 가 계정 파일에 쓴다
// (httpapi 드라이버 — 같은 PC 5 Docker 네트워크, LAN 포트 없음. 계약 mail-provisioner 0.2.0 /internal/mailbox-password-changes).
// 현재 비밀번호를 받아 Roundcube 가 먼저 확인하고, mail-provisioner 도 해시로 다시 확인한다. 실패 응답 본문에 비밀번호는 없다.
// 플러그인 폴더에 config.inc.php 가 없으면 플러그인이 이 본 설정을 쓴다(config.inc.php.dist 는 읽지 않음).
$config['plugins'][] = 'password';
$config['password_driver'] = 'httpapi';
$config['password_confirm_current'] = true;
$config['password_minimum_length'] = 12; // mail-provisioner 와 같게(12~128자)
$config['password_httpapi_url'] = 'http://mail-provisioner:8513/api/v1/internal/mailbox-password-changes';
$config['password_httpapi_method'] = 'POST';
$config['password_httpapi_var_user'] = 'user';
$config['password_httpapi_var_curpass'] = 'curpass';
$config['password_httpapi_var_newpass'] = 'newpass';
