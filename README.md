# PMart Backend

퀵 커머스(도심 소형 풀필먼트 기반 즉시·예약 배달) 백엔드. Spring Boot 마이크로서비스 / Gradle Multi-Project (Kotlin DSL) / Java 21.

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
