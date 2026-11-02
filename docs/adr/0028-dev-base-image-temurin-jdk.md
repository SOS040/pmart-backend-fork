# ADR-0028 개발 단계 컨테이너 베이스는 eclipse-temurin:21-jdk — 운영은 GraalVM Native 로 따로 정한다

- 상태: 승인 (2026-11-02, 사용자 결정 "Docker Desktop 각 컨테이너의 Exec 탭에서 쉘 커맨드를 사용하고 싶다 — 구글 이미지는 쉘이 없어서 개발단계에서 바람직하지 않다", "eum 에 있는 거랑 같은 걸로", "운영은 GraalVM 으로 Native 이미지를 만들어 실행할 것 — 구글 이미지는 필요 없다")
- 대체: `pmart.container-image` 의 베이스 `gcr.io/distroless/java21-debian12:nonroot`(2026-10-23, 결정 기록 없음 — 이 ADR 이 처음 기록이다)
- 관련: `.claude/rules/ci-cd.md`(컨테이너 이미지), ADR-0012(Docker Hub 공개 이미지), `deploy/helm/pmart-service/values.yaml`

## 배경
- distroless 이미지에는 실행 파일이 `java` 하나뿐이고 `/bin/sh` 가 없다. Docker Desktop 의 Exec 탭은 컨테이너 안에서 `/bin/sh` 를 실행하므로 `stat /bin/sh: no such file or directory` 로 열리지 않는다(LAN pc3 에서 확인).
- distroless 의 `debug-nonroot` 변종은 busybox 를 `/busybox/sh` 에 두고 `/bin/sh` 는 없다(2026-11-02 이미지 내용 확인) → Exec 탭 문제를 풀지 못한다.
- 지금 이미지는 개발(LAN)에서만 쓴다. 운영 이미지는 GraalVM Native 로 따로 만들 계획이다(사용자).

## 결정
1. **베이스 = `eclipse-temurin:21-jdk`**(Ubuntu, digest 고정). Eum 과 같은 Temurin JDK 계열이다 — 버전은 21(Eum 은 17). pmart 코드는 Java 21 로 컴파일되어 JDK 17 에서 실행되지 않는다.
   - Exec 탭: `/bin/sh`(dash)·`bash`·`curl` 사용.
   - JDK 라서 `jcmd`·`jstack`·`jmap`·`jfr` 로 컨테이너 안에서 바로 JVM 진단을 할 수 있다.
2. **비루트 실행 유지**: 베이스 기본 사용자는 root 이므로 Jib `container.user = ubuntu`(uid 1000, 베이스 내장). root 가 필요하면 `docker exec -u 0` 로만. Helm `podSecurityContext` 도 1000 으로 맞춘다.
3. **베이스 pull 인증**: 베이스가 Docker Hub 에 있으므로(distroless 는 gcr.io 익명) push 대상이 Docker Hub 이면 `REGISTRY_USERNAME`/`REGISTRY_PASSWORD` 로 베이스도 받는다. PC 1 의 저장된 Docker Desktop 자격 증명이 낡으면 401 이 난다(2026-11-02 재현).
4. **운영 이미지**는 이 베이스를 쓰지 않는다. GraalVM Native 이미지(빌드 방식·베이스·라이브러리 호환)는 운영 준비 때 새 ADR 로 정한다.

## 대안
- **distroless 유지 + 디버그 컨테이너를 옆에 붙이기**(`docker run --network/--pid container:…`): 보안상 더 낫지만 Exec 탭을 쓸 수 없다(사용자 결정으로 제외).
- **`eclipse-temurin:21-jre`**: 셸은 있고 더 작지만 JVM 진단 도구가 없다. 사용자는 Eum 과 같은 JDK 를 골랐다.
- **distroless `debug-nonroot`**: `/bin/sh` 가 없어 Exec 탭이 열리지 않는다.

## 결과
- 이미지가 커진다(로컬 Docker 기준 약 840MB, distroless 기반은 그보다 작았다). LAN PC 는 베이스 레이어를 한 번 받고 이후에는 앱 레이어만 받는다.
- 셸·패키지가 늘어 공격 표면과 CVE 수가 커진다. 개발(LAN) 전용이라는 전제에서 받아들인다 — **운영에 이 이미지를 그대로 쓰지 않는다**(결정 4).
- 영향: `build-logic/.../pmart.container-image.gradle.kts`(모든 33개 앱 + config-server 이미지), Helm values(uid), `ci-cd.md`. 서비스 코드·`libraries/` 는 바뀌지 않는다.
- 확인(2026-11-02, PC 1 로컬 `jibDockerBuild`): config-server 이미지가 `ubuntu(1000)` 로 기동해 GitHub 설정 저장소를 SSH 로 받았고, `docker exec … /bin/sh` 에서 `java 21.0.13`·`bash`·`jcmd 1 VM.uptime` 동작.
