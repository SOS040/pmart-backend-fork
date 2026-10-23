// build-logic: convention plugin 전용 included build.
rootProject.name = "build-logic"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        // 루트의 카탈로그를 그대로 공유한다 (버전 정본은 하나).
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
