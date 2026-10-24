// 컨테이너 이미지: Jib (com.google.cloud.tools.jib). Dockerfile·Docker 데몬 없이 Gradle이 OCI 이미지를 만든다.
// - 레이어: 의존성 / 스냅샷 의존성 / 리소스 / 클래스 (변경 빈도 순, 캐시 효율)
// - 태스크: jib(레지스트리 push), jibDockerBuild(로컬 Docker 데몬), jibBuildTar(tar 파일, 데몬 불필요)
// - 비밀정보는 환경변수로만 (REGISTRY_USERNAME / REGISTRY_PASSWORD). 저장소·application.yml·이미지에 넣지 않는다.
// 이 플러그인은 pmart.spring-service(및 추후 pmart.spring-gateway)가 적용한다. 직접 적용하지 않는다.

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

jib {
    from {
        // 베이스 이미지: Google distroless Java 21 (OpenJDK 21, Debian 12, 셸·패키지 관리자 없음, 비루트 uid 65532).
        // gcr.io는 익명 pull이 가능해 Docker Hub 인증·rate limit 영향을 받지 않는다.
        // digest 고정으로 재현 가능. 갱신 절차: docs/worklog 참조 (태그 nonroot의 최신 digest를 조회해 교체).
        image = "gcr.io/distroless/java21-debian12:nonroot@sha256:7e37784d94dccbf5ccb195c73b295f5ad00cd266512dfbac12eb9c3c28f8077d"
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
        // 베이스 이미지 digest (파일 상단 image 설정과 동일 값)
        jvmFlags = listOf(
            "-XX:MaxRAMPercentage=75.0",
            "-XX:+ExitOnOutOfMemoryError",
            "-Djava.security.egd=file:/dev/./urandom",
            "-Dfile.encoding=UTF-8",
            "-Duser.timezone=UTC",
        )
        user = "nonroot" // distroless nonroot 변종의 내장 사용자(uid 65532)
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
