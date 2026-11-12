// mail-provisioner: 사서함 프로비저닝 사이드카(ADR-0056 결정 10·D9). 메일 서버(docker-mailserver) 설정 볼륨의 계정 파일에 한 줄을 써서
// 사서함을 만들고 비활성화한다(docker.sock 없음). 호출자는 hr-service 하나(/internal — LAN 포트 없음, 같은 PC 5 Docker 네트워크).
// DB·Kafka 없음. 비밀번호는 SHA512-CRYPT 해시만 파일에 쓰고 로그·응답에 싣지 않는다.
plugins {
    id("pmart.spring-service")
}

pmartOpenApi {
    basePackage.set("com.pmart.mailprovisioner")
}

dependencies {
    implementation(project(":libraries:common-web"))
    implementation(project(":libraries:common-observability"))
    implementation(libs.bundles.service.observability)
    implementation(libs.commons.codec) // Sha2Crypt(SHA512-CRYPT)

}

jib {
    container {
        mainClass = "com.pmart.mailprovisioner.MailProvisionerApplication"
    }
}
