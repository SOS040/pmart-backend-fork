# ADR-0016 상품 이미지는 S3 호환 저장소에 두고, 로컬·LAN 은 SeaweedFS 로 띄운다

- 상태: 제안 (2026-10-30, 사용자 결정)
- 관련: `docs/architecture/product-registration-design.md`, ADR-0012(이미지 레지스트리 공개), ADR-0013(LAN 배포), 수집 저장소 `code-and-pick/product-data`(`infra/docker-compose.yml`, `backend/build.gradle`)

## 배경
상품 대표·상세설명·상세정보 이미지를 저장할 곳이 기술 스택에 없다. 상품 데이터를 만드는 수집 저장소는 이미 S3 호환 저장소에 이미지를 올리고 있고, 사용자는 그 "S3 목업" 버전에 맞추기를 원했다(2026-10-30). 수집 이미지는 제3자 앱 화면 캡처라 공개 이미지(Docker Hub 공개 저장소, ADR-0012)나 Git 에 넣을 수 없다.

## 결정
- product-content-service 는 이미지 파일을 **S3 API** 로 저장하고 DB 에는 버킷·키·메타(크기·해시·역할)만 둔다.
- 로컬(`deploy/local`)·LAN(`deploy/lan`)에서는 수집 저장소와 **같은 구성**으로 띄운다.
  - 이미지 `chrislusf/seaweedfs:4.48`, `mini` 모드, S3 포트 8333, path-style 주소, 리전 `us-east-1`.
  - 자격 증명은 시작 시 S3 설정 파일로 쓴다. 로컬(`deploy/local`)은 postgres(`pmart-local`)와 같이 로컬 전용 고정 기본값을 두고 환경변수로 바꿀 수 있게 한다(compose 가 값 없이 멈추지 않게). LAN·운영 값은 `.secrets/`·비밀 저장소에서만 받는다.
  - 버킷은 비공개. 버킷 이름은 서비스 설정값.
- 클라이언트: AWS SDK for Java v2 `software.amazon.awssdk:s3`, BOM **2.55.11**(수집 저장소와 동일), `forcePathStyle(true)` + `endpointOverride`. `netty-nio-client` 는 제외(동기 클라이언트만). 좌표는 `gradle/libs.versions.toml` 에 등록한다.
- 운영 대상 S3(AWS 등)로 바꿀 때는 endpoint·자격 증명 설정만 바꾼다.

## 대안
- MinIO: 수집 저장소가 "MinIO 커뮤니티 이미지가 더 이상 배포되지 않는다"는 이유로 SeaweedFS 로 옮겼다. 버전을 맞추려면 같은 것을 쓴다.
- 서비스 디스크 볼륨: 간단하지만 서비스 여러 대·다른 PC 에서 공유하기 어렵고, 수집 저장소와 형식이 달라진다.
- DB(bytea) 저장: 이미지 크기(상세설명 수 MB)와 백업 부담. 채택하지 않음.

## 결과
- 새 인프라 구성요소 1개(SeaweedFS)와 새 의존성 1개(AWS SDK v2 s3)가 생긴다.
- LAN 배치 파일(ADR-0013)에 s3 서비스와 볼륨을 추가해야 한다(배포는 사용자 요청 시에만).
- 통합 시험은 Testcontainers 로 같은 SeaweedFS 이미지를 띄운다.
