// convention plugin 모음. precompiled script plugin: src/main/kotlin/pmart.*.gradle.kts
plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(libs.versions.java.get().toInt())
}

dependencies {
    // precompiled script plugin 안에서 `id("org.springframework.boot")`, `id("com.diffplug.spotless")`를 쓰려면
    // 플러그인 아티팩트를 build-logic의 classpath에 둔다.
    implementation(libs.plugin.spring.boot)
    implementation(libs.plugin.spotless)
    implementation(libs.plugin.jib)
    // precompiled script plugin에서 타입 안전한 카탈로그 접근(libs.xxx)을 쓰기 위한 표준 우회.
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
    // contract-check 태스크의 YAML 파싱
    implementation("org.yaml:snakeyaml:2.4")
}
