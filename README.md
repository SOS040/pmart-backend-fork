# PMart Backend

퀵 커머스(도심 소형 풀필먼트 기반 즉시·예약 배달) 백엔드. Spring Boot 마이크로서비스 / Gradle Multi-Project (Kotlin DSL) / Java 21.

PMart 프로젝트 전체 소개와 다른 저장소(앱·설정·시뮬레이터)는 조직 페이지 [code-and-pick](https://github.com/code-and-pick)에서 볼 수 있다.

## 서비스 영역
| 영역 | 하는 일 |
|---|---|
| Commerce | 상품·상품 콘텐츠·고객·장바구니·검색·홈 진열·가격·프로모션·리뷰 |
| Order & Payment | 주문·주문 사가 조정·이행 경로·취소/반품 클레임·결제(토스페이먼츠) |
| CDC WMS | 중앙 물류센터 재고·입고·위치·출고·품질 |
| MFC | 도심 소형 풀필먼트 센터 재고·피킹/포장·입고·처리 능력·위치·품질 |
| Middle-mile TMS | CDC → MFC 간선 운송 계획·실행 |
| Last-mile Delivery | 배송 가능 지역·배차·배송 실행·라이더·추적 |
| Platform & Support | API 게이트웨이·인증(고객·라이더·직원)·인사·승인·알림·감사·상담·AI 상담(RAG)·설정 서버 |

서비스별 책임 경계는 `docs/architecture/service-map.md`, 기술 스택은 `docs/architecture/tech-stack.md`.

## 시작하기
```bash
./gradlew build                         # 전체 (Windows: .\gradlew.bat)
./gradlew :services:<name>:test         # 단위 테스트
./gradlew :services:<name>:check        # 단위 + 통합(Testcontainers, Docker 필요) + 계약 검증 + 형식
./gradlew :services:<name>:bootJar      # 실행 이미지용 jar
./gradlew check -PskipIntegration=true  # Docker 없는 환경
./gradlew :services:<name>:jibBuildTar  # 컨테이너 이미지 tar (Docker 데몬 불필요)
./gradlew :services:<name>:jibDockerBuild  # 로컬 Docker 데몬에 이미지 생성
./gradlew :services:<name>:jib -PcontainerRegistry=docker.io/<계정> -PimageTag=<tag>  # Docker Hub push → <계정>/pmart-<name> (CI, 인증은 REGISTRY_USERNAME/PASSWORD 환경변수)
```

## 구조
`CLAUDE.md`의 "구조"와 "문서 지도"를 참조한다. 설계 정본은 `docs/architecture/`, 계약 정본은 `contracts/`.

## 문서
- 전체 흐름·확정 정책: `docs/business/overview.md`
- 서비스 책임 경계: `docs/architecture/service-map.md`
- 아키텍처: `docs/architecture/architecture-design.md`, 결정 기록 `docs/adr/`
- 작업 일지: `docs/worklog/`

---

- 동성인재개발교육원 — https://www.dshrd.or.kr/
- 지도: 서성원 훈련 교사
