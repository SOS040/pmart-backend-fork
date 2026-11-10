// 컨테이너 이미지: Jib (com.google.cloud.tools.jib). Dockerfile·Docker 데몬 없이 Gradle이 OCI 이미지를 만든다.
// - 레이어: 의존성 / 스냅샷 의존성 / 리소스 / 클래스 (변경 빈도 순, 캐시 효율)
// - 태스크: jib(레지스트리 push), jibDockerBuild(로컬 Docker 데몬), jibBuildTar(tar 파일, 데몬 불필요)
// - 비밀정보는 환경변수로만 (REGISTRY_USERNAME / REGISTRY_PASSWORD). 저장소·application.yml·이미지에 넣지 않는다.
// 이 플러그인은 pmart.spring-service·pmart.spring-gateway 가 적용한다. 직접 적용하지 않는다.

plugins {
    id("com.google.cloud.tools.jib")
}

// ---- 입력 (CI가 -P 또는 환경변수로 주입. 없으면 로컬 기본값) ----
val containerRegistry = providers.gradleProperty("containerRegistry")
    .orElse(providers.environmentVariable("CONTAINER_REGISTRY"))
    .orElse("localhost:5000")
val imageTag = providers.gradleProperty("imageTag")
    .orElse(providers.environmentVariable("IMAGE_TAG"))
    .orElse(project.version.toString().ifBlank { "dev" })
val gitRevision = providers.gradleProperty("gitRevision")
    .orElse(providers.environmentVariable("GIT_COMMIT"))
    .orElse("unknown")
val registryUsername = providers.environmentVariable("REGISTRY_USERNAME")
val registryPassword = providers.environmentVariable("REGISTRY_PASSWORD")
val allowInsecure = providers.gradleProperty("allowInsecureRegistries").map { it.toBoolean() }.orElse(false)
// push 대상이 Docker Hub 인가(docker.io/<계정>). 베이스(eclipse-temurin)도 Docker Hub 라 같은 자격 증명을 쓸 수 있다
val dockerHubTarget = containerRegistry.get().let { it.startsWith("docker.io/") || it.startsWith("registry-1.docker.io/") }

jib {
    from {
        // 베이스 이미지(ADR-0028): Eclipse Temurin 21 JDK (Ubuntu). 개발 단계 — Docker Desktop Exec 탭에서 셸(sh·bash)과
        // JVM 진단 도구(jcmd·jstack·jmap·jfr)를 쓰기 위해서다. 운영은 GraalVM Native 이미지로 따로 정한다(이 베이스를 쓰지 않음).
        // 예외: OR-Tools(libs.ortools.java) 의존 서비스는 Native 금지 — JVM 실행(ADR-0053). Native 설정을 만들 때 이 서비스들을 빼는 장치를 같이 넣는다.
        // Docker Hub 공식 이미지(익명 pull — rate limit 영향을 받는다). digest 고정(멀티 아키텍처 인덱스, 아래 platform 으로 amd64 선택).
        // 갱신: docker pull eclipse-temurin:21-jdk → RepoDigests 의 sha256 으로 교체(PR).
        image = "eclipse-temurin:21-jdk@sha256:ebfbb18d11ff163ee83b21258acf12248af48d22a0fdfdc68fa1a1516c05976a"
        // 베이스도 Docker Hub 라, push 대상이 Docker Hub 이고 REGISTRY_* 가 있으면 같은 자격 증명으로 받는다.
        // (없으면 Docker 자격 증명 도우미 → 익명. PC 1 의 저장된 Docker Desktop 자격 증명이 낡으면 401 — 2026-11-02 재현)
        if (registryUsername.isPresent && registryPassword.isPresent && dockerHubTarget) {
            auth {
                username = registryUsername.get()
                password = registryPassword.get()
            }
        }
        platforms {
            platform {
                architecture = "amd64"
                os = "linux"
            }
        }
    }
    to {
        // 태그는 imageTag 하나만. (to.image에 태그를 넣지 않으면 Jib이 latest를 추가로 붙인다 → 운영 digest 기반 배포와 충돌)
        // 이름은 {registry}/pmart-{service}. Docker Hub는 namespace/repository 두 단계만 허용하므로
        // containerRegistry=docker.io/<계정> → docker.io/<계정>/pmart-<service> (ADR-0012)
        image = "${containerRegistry.get()}/pmart-${project.name}:${imageTag.get()}"
        if (registryUsername.isPresent && registryPassword.isPresent) {
            auth {
                username = registryUsername.get()
                password = registryPassword.get()
            }
        }
    }
    container {
        // mainClass를 지정하지 않으면 Jib이 main 메서드를 가진 클래스를 탐색한다(느림·경고).
        // 각 서비스 build.gradle.kts에서 jib.container.mainClass 를 지정하면 탐색을 건너뛴다.
        ports = listOf("8080")
        jvmFlags = listOf(
            "-XX:MaxRAMPercentage=75.0",
            "-XX:+ExitOnOutOfMemoryError",
            "-Djava.security.egd=file:/dev/./urandom",
            "-Dfile.encoding=UTF-8",
            "-Duser.timezone=UTC",
        )
        // 비루트 실행: 베이스의 내장 사용자 ubuntu(uid 1000). 기본값은 root 라 반드시 지정한다.
        // root 가 필요한 작업(패키지 설치 등)은 docker exec -u 0 으로만 — Exec 탭 기본 셸은 ubuntu 다.
        user = "ubuntu"
        creationTime = "USE_CURRENT_TIMESTAMP" // 이미지 생성 시각 표시용. 레이어 내용은 재현 가능.
        labels = mapOf(
            "org.opencontainers.image.title" to project.name,
            "org.opencontainers.image.version" to imageTag.get(),
            "org.opencontainers.image.revision" to gitRevision.get(),
            "org.opencontainers.image.vendor" to "PMart",
        )
    }
    setAllowInsecureRegistries(allowInsecure.get()) // 로컬 레지스트리(http)용. 운영 레지스트리는 TLS
}

// Jib 3.5.x는 Gradle configuration cache를 지원하지 않는다("this.project is null").
// jib* 태스크만 configuration cache 비호환으로 표시하면 해당 호출에서만 캐시를 끄고 나머지 빌드는 계속 캐시를 쓴다.
tasks.matching { it.name.startsWith("jib") }.configureEach {
    notCompatibleWithConfigurationCache("Jib Gradle plugin은 configuration cache를 지원하지 않음 (GoogleContainerTools/jib#3132)")
}
