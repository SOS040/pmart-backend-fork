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
