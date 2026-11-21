# 회신 — BR-80 내 정보 프로필 사진 바꾸기

보낸 영역: platform
받는 앱: customer-app
답하는 것: `pmart-clients/docs/handoff/customer-app/2026-11-21-backend-profile-photo-edit.md`
작성: pmart-backend, 2026-11-21 — 사용자 "pc1 프론트세션이 보낸거 작업해줘"
계약: **customer-service 0.15.0**(`contracts/openapi/customer-service.yaml`)

## 만든 것
| 요청 | API | 결과 |
|---|---|---|
| R1 직접 올리기 | `PUT /me/profile-image` — `multipart/form-data` 의 `file` 한 장, 본인 토큰·`Idempotency-Key`·`X-Source-System` | `CommandResponse`(status APPLIED). 새 주소는 `GET /me` 의 `profileImageUrl`(`?v=` 바뀜) |
| R2 기본 이미지 | `DELETE /me/profile-image` | APPLIED, `profileImageUrl` 없음, `GET /me/profile-image` 404 |
| R3 로그인 계정 사진으로 | `POST /me/profile-image/provider-restorations` | APPLIED. 사본은 응답 **뒤** 서버가 받는다 — 바로 `GET /me` 하면 잠깐 `profileImageUrl` 이 없을 수 있다(1~2초 뒤 다시). 제공자가 사진을 준 적 없으면 사진 없음 |
| R4 덮지 않기 | `Profile.photoSource` = `PROVIDER`·`CUSTOM`·`NONE` | CUSTOM·NONE 이면 로그인해도 제공자 사진으로 덮지 않는다. PROVIDER 면 지금처럼 로그인마다 최신 |
| R5 | — | 사진·주소는 로그에 남기지 않는다(계정 ID·출처만) |

- **형식·크기**: JPEG·PNG·WebP(파일 앞부분으로 판정 — 확장자·Content-Type 은 보지 않음), **2MB 이하**. 넘거나 다른 형식이면 400 `VALIDATION_FAILED`, `details[].field = "file"`. 서버는 크기를 다시 바꾸지 않으니 지금 생각한 대로 정사각형 512px JPEG 로 보내면 된다.
- 같은 `Idempotency-Key` 재전송은 처음 결과. 같은 사진을 다시 올리면 바뀌지 않고 APPLIED.
- 탈퇴 신청 이후는 409 `INVALID_STATE`. 저장소 장애는 503(재시도 가능).
- 응답의 `version` 은 프로필 version 그대로(사진은 프로필 version 을 올리지 않는다).
- 시트에 [로그인 계정 사진으로 되돌리기]를 보일지는 `photoSource != PROVIDER` 로 정하면 된다.

## 확인
- customer-service check 통과(단위 30·통합 36, 새 시험 `ProfileImageIT.customerChoosesPhotoAndLoginDoesNotOverwriteIt` — 올리기·재전송·로그인해도 그대로·형식/크기 400·기본·되돌리기·운영자 403).
- 게이트웨이 사용자 유형 허용 목록 3줄 추가.

## 상태
- LAN 배포 전 — 사용자 "배포" 뒤 PC 2 customer-service(V18) + PC 4 게이트웨이 + 설정(업로드 상한). 배포되면 이 줄을 고친다.
