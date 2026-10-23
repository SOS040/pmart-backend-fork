// 계약 검증: 서비스의 OpenAPI 스펙이 contracts/openapi/{service}.yaml 에 존재하고 파싱되는지,
// 스펙이 참조하는 공통 컴포넌트 파일과 이벤트 스키마 파일이 있는지 확인해 check에 연결한다.
// (전체 린트는 CI에서 redocly/ajv로 수행. 여기서는 빌드가 계약 없이 진행되는 것을 막는 최소 검증.)

import com.pmart.buildlogic.ContractCheckTask

val contractsDir = rootProject.layout.projectDirectory.dir("contracts")

val contractCheck = tasks.register<ContractCheckTask>("contractCheck") {
    group = "verification"
    description = "contracts/openapi/${project.name}.yaml 존재·파싱·참조 검증"
    serviceName.set(project.name)
    openapiDir.set(contractsDir.dir("openapi"))
    eventsDir.set(contractsDir.dir("events"))
    // 스펙이 없어도 되는 프로젝트(플랫폼 인프라 앱)는 build.gradle.kts에서 required.set(false)
    required.set(true)
    reportFile.set(layout.buildDirectory.file("reports/contract-check/${project.name}.txt"))
}

tasks.named("check") { dependsOn(contractCheck) }
