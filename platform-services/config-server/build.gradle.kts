// config-server: Spring Cloud Config Server (ADR-0027). LAN 의 모든 Spring 앱이 부팅할 때 설정을 받는다.
// - 저장소: GitHub code-and-pick/pmart-config (private, 읽기 전용 Deploy key). 최상위 파일을 프로필로 고른다(ADR-0029).
// - /monitor: GitHub push 웹훅(smee.io 중계)을 받아 바뀐 파일의 앱에 Bus 로 갱신을 알린다(ADR-0033).
// - 비밀값은 {cipher}(AES, encrypt.key)로 저장소에 두고 서버가 복호화해 내려준다. /encrypt·/decrypt 엔드포인트는 막는다.
// - 업무 로직·업무 API 없음 → OpenAPI 생성·계약 검증을 끈다.
plugins {
    id("pmart.spring-service")
}

pmartOpenApi {
    enabled.set(false)
}

tasks.named<com.pmart.buildlogic.ContractCheckTask>("contractCheck") {
    required.set(false)
}

dependencies {
    implementation(libs.spring.cloud.config.server)
    implementation(libs.spring.boot.starter.security) // 클라이언트 Basic 인증
    implementation(libs.spring.cloud.config.monitor) // /monitor: GitHub 웹훅 → Bus(convention 의 bus-kafka) (ADR-0033)
    implementation(project(":libraries:common-observability"))
    implementation(libs.bundles.service.observability)

    "integrationTestImplementation"(platform(libs.spring.cloud.bom))
    "integrationTestImplementation"(libs.spring.cloud.config.server) // 시험 저장소 생성(JGit)·EncryptorFactory
}

jib {
    container {
        mainClass = "com.pmart.config.ConfigServerApplication"
    }
}

// 설정 저장소용 {cipher} 값 생성(deploy/lan/render.sh 가 부른다). 서버와 같은 EncryptorFactory 를 쓴다.
// 입력·출력·키·salt 는 파일 경로(-P). 비밀값을 명령줄 인자로 넘기지 않는다.
tasks.register<JavaExec>("configCipher") {
    group = "pmart"
    description = "설정 저장소용 {cipher} 값 생성 (ConfigCipherTool)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.pmart.config.cipher.ConfigCipherTool")
    // 실행할 때만 읽는다(구성 단계에서 .get() 하면 -P 없이 도는 check 등이 실패한다)
    val names = listOf("cipherIn", "cipherOut", "cipherKeyFile", "cipherSaltFile")
    val values = names.map { providers.gradleProperty(it) }
    argumentProviders.add(CommandLineArgumentProvider {
        values.mapIndexed { i, p -> p.orNull ?: throw GradleException("-P${names[i]} 가 필요하다") }
    })
}
