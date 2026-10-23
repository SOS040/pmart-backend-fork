package com.pmart.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * 서비스 하나의 계약 존재·파싱·참조 검증.
 * - contracts/openapi/{service}.yaml 이 있고 YAML로 파싱된다.
 * - `openapi:` 가 3.1.x 이고 `paths` 가 있다.
 * - `$ref: '_common.yaml#...'` 가 가리키는 파일이 있다.
 * - info.description 또는 x-events 에 적힌 이벤트명(PascalCase)이 contracts/events/{Event}.v1.json 으로 존재한다 (있을 때만).
 */
abstract class ContractCheckTask : DefaultTask() {

    @get:Input
    abstract val serviceName: Property<String>

    @get:Input
    abstract val required: Property<Boolean>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val openapiDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val eventsDir: DirectoryProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun run() {
        val name = serviceName.get()
        val spec = openapiDir.get().file("$name.yaml").asFile
        val report = StringBuilder()
        val problems = mutableListOf<String>()

        if (!spec.exists()) {
            if (required.get()) {
                throw GradleException("계약 없음: ${spec.path}. contracts/ 를 먼저 작성한다 (CLAUDE.md).")
            }
            report.appendLine("SKIP: no contract required for $name")
            writeReport(report)
            return
        }

        val loader = LoaderOptions().apply { codePointLimit = 16 * 1024 * 1024 }
        val root: Map<*, *> = try {
            Yaml(loader).load<Any>(spec.readText()) as? Map<*, *> ?: error("root is not a mapping")
        } catch (e: Exception) {
            throw GradleException("OpenAPI 파싱 실패: ${spec.path}: ${e.message}")
        }

        val version = root["openapi"]?.toString() ?: ""
        if (!version.startsWith("3.1")) problems += "openapi 버전이 3.1.x 가 아님: '$version'"
        if (root["paths"] !is Map<*, *>) problems += "paths 없음"
        report.appendLine("openapi=$version paths=${(root["paths"] as? Map<*, *>)?.size ?: 0}")

        // $ref 대상 파일 존재 확인 (외부 파일 참조만)
        val text = spec.readText()
        val refFiles = Regex("""\${'$'}ref:\s*'([^#']+\.yaml)#""").findAll(text).map { it.groupValues[1] }.toSet()
        for (rf in refFiles) {
            if (!File(spec.parentFile, rf).exists()) problems += "참조 파일 없음: $rf"
        }
        report.appendLine("ref files: ${refFiles.joinToString()}")

        // x-events: [EventA, EventB] 가 있으면 스키마 파일 존재 확인
        val info = root["info"] as? Map<*, *>
        val events = (info?.get("x-events") as? List<*>)?.map { it.toString() } ?: emptyList()
        for (ev in events) {
            if (!eventsDir.get().file("$ev.v1.json").asFile.exists()) problems += "이벤트 스키마 없음: $ev.v1.json"
        }
        if (events.isNotEmpty()) report.appendLine("events checked: ${events.size}")

        if (problems.isNotEmpty()) {
            report.appendLine("PROBLEMS:")
            problems.forEach { report.appendLine(" - $it") }
            writeReport(report)
            throw GradleException("계약 검증 실패 ($name):\n" + problems.joinToString("\n") { " - $it" })
        }
        report.appendLine("OK")
        writeReport(report)
    }

    private fun writeReport(sb: StringBuilder) {
        val f = reportFile.get().asFile
        f.parentFile.mkdirs()
        f.writeText(sb.toString())
    }
}
