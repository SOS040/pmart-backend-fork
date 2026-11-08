# [pmart-backend → pmart-clients] 요청: 백오피스 LAN 빌드의 게이트웨이 주소를 IP 로 (2026-11-06)

- 성격: **수정 요청(백오피스 web, `build:lan`).** 백엔드는 클라이언트를 수정하지 않는다.
- 백엔드 쪽은 끝났다: 게이트웨이 CORS 허용 출처에 `http://192.168.0.40` 추가(pmart-config `api-gateway-lan.yml` `8334e71`, LAN 게이트웨이 재시작·확인 완료). 기존 `http://d603-16` 도 그대로 허용한다.

## 1. 현상
- PC 1 이 아닌 PC 에서 `http://d603-16` 이 열리지 않는다(시간 초과).
- 원인: 이름 `d603-16` 이 다른 PC 에서 IPv6 링크 로컬(`fe80::…`)로 먼저 풀리고, 그 주소로는 응답이 없다. `http://192.168.0.40/` 은 200 으로 열린다(pc3 에서 확인).
- 그런데 IP 로 열어도 쓸 수 없다. `build:lan` 이 API 를 `http://D603-16:8080/api/v1`(이름)로 부르기 때문이다(`web/backoffice/vite.config.ts` 13줄).

## 2. 요청
1. `vite.config.ts` 의 lan 모드 `VITE_PMART_API_BASE` 를 `http://192.168.0.40:8080/api/v1` 로 바꾼다. 11~12줄 주석(“IP 로 열면 막힘”)도 고친다. 주석의 `deploy/lan/cors-origins.conf` 는 없는 파일이다. 실제 위치는 pmart-config `api-gateway-lan.yml` `pmart.gateway.cors.allowed-origins` 이다.
2. `npm run build:lan` 결과(`dist/`)를 알려 주면 백엔드가 `deploy/lan/lan.sh publish-backoffice <dist>` 로 LAN 에 올린다.
3. `docs/apps/backoffice-web.md` 62줄의 안내(“브라우저는 `http://D603-16` 으로 열어야 CORS 통과”)를 `http://192.168.0.40` 으로 고친다.

## 3. 확인 기준
- 다른 PC 브라우저에서 `http://192.168.0.40` → 로그인 → 가격 화면 조회가 된다.
- CORS 사전 요청(LAN, 2026-11-06 확인): Origin `http://192.168.0.40` → 200, `http://d603-16` → 200, 그 밖 → 403.

## 4. 참고
- 게이트웨이 IP 는 `deploy/lan/placement.conf` 의 pc4 줄이 정본이다. pc4 IP 가 바뀌면 이 주소와 CORS 출처를 같이 바꾼다.
- 그 전까지 임시 방법: 쓰는 PC 의 hosts 에 `192.168.0.40  d603-16` 을 넣고 `http://d603-16` 으로 연다.
