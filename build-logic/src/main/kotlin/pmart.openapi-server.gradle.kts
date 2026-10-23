// contracts/openapi/{project.name}.yaml → Spring MVC 인터페이스·DTO 생성 (OpenAPI Generator, 'spring' generator).
// - 계약이 정본. 생성 코드는 build/generated/openapi 에만 존재하고 저장소에 커밋하지 않는다.
// - 서비스는 @RestController 가 생성된 *Api 인터페이스를 구현한다. 구현하지 않은 메서드는 인터페이스 default 로 501 을 반환한다.
// - 패키지 기본값: com.pmart.<project.name 에서 -service 제거, '-' → '.'> (예: mfc-inventory-service → com.pmart.mfc.inventory)
//   서비스에서 바꾸려면: pmartOpenApi { basePackage.set("com.pmart.order") }

import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    java
    id("org.openapi.generator")
}

interface PmartOpenApiExtension {
    val basePackage: Property<String>
    /** false 면 생성·검증을 건너뛴다 (계약 없는 플랫폼 앱). */
    val enabled: Property<Boolean>
}

val ext = extensions.create<PmartOpenApiExtension>("pmartOpenApi")
ext.basePackage.convention("com.pmart." + project.name.removeSuffix("-service").replace('-', '.'))
ext.enabled.convention(true)

val specFile = rootProject.layout.projectDirectory.file("contracts/openapi/${project.name}.yaml")
val openApiOutputDir = layout.buildDirectory.dir("generated/openapi")

// configuration cache: 확장 객체 대신 Provider 값만 캡처한다
val generationEnabled: Provider<Boolean> = ext.enabled
val basePackageProvider: Provider<String> = ext.basePackage

tasks.named<GenerateTask>("openApiGenerate") {
    val enabledValue = generationEnabled.get()
    onlyIf("pmartOpenApi.enabled") { enabledValue }
    generatorName.set("spring")
    library.set("spring-boot")
    inputSpec.set(specFile.asFile.path)
    outputDir.set(openApiOutputDir.get().asFile.absolutePath) // 주의: 태스크 자신의 outputDir 와 이름 충돌 방지
    apiPackage.set(basePackageProvider.map { "$it.api.generated" })
    modelPackage.set(basePackageProvider.map { "$it.api.generated.model" })
    invokerPackage.set(basePackageProvider.map { "$it.api.generated.invoker" })
    // 생성 범위: 인터페이스 + 모델만. 컨트롤러·설정·테스트·pom 은 만들지 않는다.
    globalProperties.set(
        mapOf(
            "apis" to "",
            "models" to "",
            "supportingFiles" to "ApiUtil.java", // default 메서드의 예시 응답 헬퍼만
            "apiTests" to "false",
            "modelTests" to "false",
            "apiDocs" to "false",
            "modelDocs" to "false",
        ),
    )
    configOptions.set(
        mapOf(
            "interfaceOnly" to "true",
            "useSpringBoot3" to "true",          // jakarta.* 네임스페이스
            "useTags" to "true",                 // 태그별 *Api 인터페이스
            "useResponseEntity" to "true",
            "skipDefaultInterface" to "false",   // 미구현 메서드는 501
            "documentationProvider" to "springdoc",
            "openApiNullable" to "false",        // jackson-databind-nullable 의존 제거
            "useJakartaEe" to "true",
            "dateLibrary" to "java8",
            "serializableModel" to "false",
            "generatedConstructorWithRequiredArgs" to "false",
            "containerDefaultToNull" to "true",
            "performBeanValidation" to "false",
            "useBeanValidation" to "true",
            "additionalModelTypeAnnotations" to "",
            "unhandledException" to "false",
            "requestMappingMode" to "api_interface",
            "hideGenerationTimestamp" to "true",
            "generateJsonIncludeAnnotations" to "false",
            "generateJsonSetterNullsAnnotations" to "false",
        ),
    )
    // 변경 감지: 계약 파일 + 공통 컴포넌트
    inputs.file(specFile)
    inputs.file(rootProject.layout.projectDirectory.file("contracts/openapi/_common.yaml"))
}

// 생성 소스를 main 에 포함, 컴파일 전에 생성
val generatedJava = openApiOutputDir.map { it.dir("src/main/java") }
extensions.getByType<SourceSetContainer>().named("main") {
    java.srcDir(generatedJava)
}
tasks.named("compileJava") { dependsOn("openApiGenerate") }

// Spotless 는 손으로 쓴 소스만 검사한다 (생성 코드 제외) — java-conventions 의 target("src/**/*.java") 가 이미 그렇게 한다.

// 생성 코드가 필요로 하는 의존성 (springdoc 는 pmart.spring-service 에서 추가)
dependencies {
    "implementation"("jakarta.annotation:jakarta.annotation-api")
    "implementation"("jakarta.validation:jakarta.validation-api")
}
