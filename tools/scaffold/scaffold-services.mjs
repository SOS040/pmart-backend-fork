#!/usr/bin/env node
// 3단계 서비스 스캐폴딩. 사용:
//   node tools/scaffold/scaffold-services.mjs [--only name,name] [--phase 1|2|all]
// phase 1: 빌드 파일·애플리케이션·설정·마이그레이션·테스트·CLAUDE.md 생성 + settings.gradle.kts 등록 (+ deploy/local init-db.sql)
// phase 2: (사전에 ./gradlew :services:<name>:openApiGenerate 실행) 생성된 *Api 인터페이스마다 @RestController 스텁 생성
// 기존 파일은 덮어쓰지 않는다(안전). 삭제 후 재실행하면 재생성.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "../..");
const args = process.argv.slice(2);
const only = (args.includes("--only") ? args[args.indexOf("--only") + 1] : "").split(",").filter(Boolean);
const phase = args.includes("--phase") ? args[args.indexOf("--phase") + 1] : "all";

const table = JSON.parse(fs.readFileSync(path.join(here, "services.json"), "utf8")).services;
const services = only.length ? table.filter((s) => only.includes(s.name)) : table;

// Java 예약어(case 등)와 충돌하면 services.json 의 package 로 재정의한다.
const pkgOverride = Object.fromEntries(table.filter((s) => s.package).map((s) => [s.name, s.package]));
const pkgOf = (name) => pkgOverride[name] ?? "com.pmart." + name.replace(/-service$/, "").replace(/-/g, ".");

const dbOf = (name) => name.replace(/-/g, "_");
const classOf = (name) =>
  name
    .split("-")
    .map((p) => p[0].toUpperCase() + p.slice(1))
    .join("");

function writeIfAbsent(file, content) {
  if (fs.existsSync(file)) return false;
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, content.replace(/\r\n/g, "\n"));
  return true;
}

function buildGradle(s) {
  const f = new Set(s.features);
  const lines = [
    `// ${s.name}: docs/architecture/service-map.md 참조. 업무 규칙은 domain/application 패키지에.`,
    `plugins {`,
    `    id("pmart.spring-service")`,
    `}`,
    ``,
    `pmartOpenApi {`,
    `    basePackage.set("${pkgOf(s.name)}")`,
    `}`,
    ``,
    `dependencies {`,
    `    implementation(project(":libraries:common-web"))`,
    `    implementation(project(":libraries:common-observability"))`,
    `    implementation(project(":libraries:common-security"))`,
  ];
  if (f.has("kafka")) lines.push(`    implementation(project(":libraries:common-messaging"))`, `    implementation(libs.bundles.service.kafka)`);
  if (f.has("data")) lines.push(`    implementation(libs.bundles.service.data)`);
  if (f.has("redis")) lines.push(`    implementation(libs.spring.boot.starter.data.redis)`);
  lines.push(`    implementation(libs.bundles.service.observability)`);
  lines.push(``, `    testImplementation(project(":libraries:common-test"))`, `    "integrationTestImplementation"(project(":libraries:common-test"))`, `}`, ``);
  return lines.join("\n");
}

function application(s) {
  const pkg = pkgOf(s.name);
  const cls = classOf(s.name) + "Application";
  return `package ${pkg};

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** ${s.name}. 책임·비소유는 services/${s.name}/CLAUDE.md 와 docs/architecture/service-map.md. */
@SpringBootApplication
public class ${cls} {
  public static void main(String[] args) {
    SpringApplication.run(${cls}.class, args);
  }
}
`;
}

function applicationYml(s) {
  const f = new Set(s.features);
  const y = [
    `spring:`,
    `  application:`,
    `    name: ${s.name}`,
    `  mvc:`,
    `    problemdetails:`,
    `      enabled: true # Spring MVC 자체 예외도 RFC 9457`,
    `  security:`,
    `    oauth2:`,
    `      resourceserver:`,
    `        jwt:`,
    `          jwk-set-uri: \${PMART_JWKS_URI}`,
  ];
  if (f.has("data")) {
    y.push(
      `  datasource:`,
      `    url: \${PMART_DB_URL}`,
      `    username: \${PMART_DB_USERNAME}`,
      `    password: \${PMART_DB_PASSWORD}`,
      `  jpa:`,
      `    open-in-view: false`,
      `    hibernate:`,
      `      ddl-auto: validate # 스키마 변경은 Flyway 만 (service-architecture.md)`,
      `  flyway:`,
      `    locations: classpath:db/migration # common-messaging 이 classpath:db/migration/common 을 추가한다`,
    );
  }
  if (f.has("kafka")) y.push(`  kafka:`, `    bootstrap-servers: \${PMART_KAFKA_BOOTSTRAP}`);
  if (f.has("redis")) y.push(`  data:`, `    redis:`, `      url: \${PMART_REDIS_URL}`);
  y.push(`server:`, `  port: ${s.port}`);
  if (f.has("kafka")) y.push(`pmart:`, `  messaging:`, `    domain: ${s.domain}`);
  y.push(`management:`, `  zipkin:`, `    tracing:`, `      endpoint: \${PMART_ZIPKIN_ENDPOINT:http://localhost:9411/api/v2/spans}`);
  return y.join("\n") + "\n";
}

function applicationLocalYml(s) {
  const f = new Set(s.features);
  const y = [
    `# 로컬 개발 프로파일 (deploy/local/docker-compose.yml). 로컬 컨테이너 전용 고정값이며 운영은 Secret Manager.`,
    `spring:`,
    `  security:`,
    `    oauth2:`,
    `      resourceserver:`,
    `        jwt:`,
    `          jwk-set-uri: \${PMART_JWKS_URI:http://localhost:8501/.well-known/jwks.json}`,
  ];
  if (f.has("data")) {
    y.push(
      `  datasource:`,
      `    url: jdbc:postgresql://localhost:15432/${dbOf(s.name)}`, // deploy/local 은 15432 (호스트 PostgreSQL 충돌 방지)
      `    username: pmart`,
      `    password: \${PMART_DB_PASSWORD:pmart-local}`,
    );
  }
  if (f.has("kafka")) y.push(`  kafka:`, `    bootstrap-servers: localhost:9092`);
  if (f.has("redis")) y.push(`  data:`, `    redis:`, `      url: redis://localhost:6379`);
  y.push(`logging:`, `  level:`, `    com.pmart: DEBUG`);
  return y.join("\n") + "\n";
}

function migration(s) {
  return `-- ${s.name} 스키마 초기화. 공통(outbox_event, inbox_event)은 common-messaging 의 V0_0_1 이 만든다.
-- 테이블은 업무 구현(4단계)에서 V2 이후로 추가한다. 이미 적용된 파일은 수정하지 않는다.
-- 서비스 간 DB JOIN·FK·직접 테이블 접근 금지 (service-architecture.md).
SELECT 1;
`;
}

function unitTest(s) {
  const pkg = pkgOf(s.name);
  return `package ${pkg};

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

/** 스캐폴딩 자체 검증: 컨트롤러 스텁이 생성된 계약 인터페이스를 구현한다. (업무 테스트는 4단계에서 추가) */
class ContractStubTest {

  @Test
  void controllersImplementGeneratedApiInterfaces() throws Exception {
    var scanner =
        new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(
        new org.springframework.core.type.filter.AnnotationTypeFilter(RestController.class));
    var beans = scanner.findCandidateComponents("${pkg}.api");
    assertThat(beans).as("api 패키지에 @RestController 스텁이 있어야 한다").isNotEmpty();
    for (var bd : beans) {
      Class<?> c = Class.forName(bd.getBeanClassName());
      assertThat(Arrays.stream(c.getInterfaces()).anyMatch(i -> i.getSimpleName().endsWith("Api")))
          .as(c.getName() + " implements *Api")
          .isTrue();
    }
  }
}
`;
}

function bootIT(s) {
  const pkg = pkgOf(s.name);
  const f = new Set(s.features);
  const base = f.has("kafka") ? "PostgresKafkaContainerSupport" : "PostgresContainerSupport";
  const props = [`"spring.main.banner-mode=off"`, `"spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks"`];
  if (!f.has("data")) props.push(`"spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"`);
  // redis 기능: Redis health indicator 가 포함되므로 더미 주소면 health DOWN(503). 실제 Redis 컨테이너(@ServiceConnection)를 쓴다.
  // Boot 는 ConnectionDetails 가 있어도 spring.data.redis.url 을 파싱하므로(urlUsesSsl) 미해결 플레이스홀더를 빈 값으로 덮는다.
  if (f.has("redis")) props.push(`"spring.data.redis.url="`);
  const redisImports = f.has("redis")
    ? "import org.springframework.boot.testcontainers.service.connection.ServiceConnection;\nimport org.testcontainers.containers.GenericContainer;\nimport org.testcontainers.junit.jupiter.Container;\n"
    : "";
  const redisField = f.has("redis")
    ? "  /** Redis health indicator 가 포함되므로 실제 Redis 컨테이너를 쓴다. */\n  @Container @ServiceConnection(name = \"redis\")\n  static final GenericContainer<?> REDIS = new GenericContainer<>(\"redis:7-alpine\").withExposedPorts(6379);\n\n"
    : "";
  return `package ${pkg};

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.${base};
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
${redisImports}import org.springframework.web.client.RestClient;

/** 기동 검증: 실제 PostgreSQL(Flyway 공통+서비스 마이그레이션)·Kafka 위에서 컨텍스트가 뜨고 /actuator/health 가 200. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {${props.join(", ")}})
class ServiceBootIT extends ${base} {

${redisField}  @LocalServerPort int port;

  @Test
  void healthIsUpWithoutAuthentication() {
    var body =
        RestClient.create()
            .get()
            .uri("http://localhost:" + port + "/actuator/health")
            .retrieve()
            .body(String.class);
    assertThat(body).contains("\"status\":\"UP\"");
  }
}
`;
}

function claudeMd(s) {
  return `# ${s.name}

서비스 책임의 정본은 \`docs/architecture/service-map.md\` 의 \`${s.name}\` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: \`contracts/openapi/${s.name}.yaml\` → \`openApiGenerate\` 가 \`${pkgOf(s.name)}.api.generated\` 에 인터페이스·DTO 생성. \`api/*Controller\` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 \`EventPublisher\`(Outbox, Debezium), 소비는 \`InboxGuard\` 안에서.

## 패키지
\`${pkgOf(s.name)}.{api, application, domain, infrastructure}\` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 \`src/main/resources/db/migration/V*.sql\` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
\`\`\`bash
./gradlew :services:${s.name}:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:${s.name}:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
\`\`\`
로컬 포트 ${s.port}.

## 상태
3단계 스캐폴딩(2026-10-23). 업무 로직 미구현 — 모든 엔드포인트는 501 UNSUPPORTED_ACTION.
`;
}

function controller(pkg, apiName) {
  const cls = apiName.replace(/Api$/, "") + "Controller";
  return `package ${pkg}.api;

import ${pkg}.api.generated.${apiName};
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 ${apiName} 의 구현 진입점. 아직 구현하지 않은 메서드는 인터페이스 default 가 501 을 돌려준다. 업무 규칙은
 * application/domain 에 두고 여기서는 요청·응답 변환만 한다.
 */
@RestController
public class ${cls} implements ${apiName} {}
`;
}

// ---------------- phase 1 ----------------
function phase1() {
  const created = [];
  for (const s of services) {
    const dir = path.join(root, "services", s.name);
    const pkgDir = pkgOf(s.name).replace(/\./g, "/");
    const files = {
      [path.join(dir, "build.gradle.kts")]: buildGradle(s),
      [path.join(dir, "CLAUDE.md")]: claudeMd(s),
      [path.join(dir, "src/main/java", pkgDir, classOf(s.name) + "Application.java")]: application(s),
      [path.join(dir, "src/main/resources/application.yml")]: applicationYml(s),
      [path.join(dir, "src/main/resources/application-local.yml")]: applicationLocalYml(s),
      [path.join(dir, "src/test/java", pkgDir, "ContractStubTest.java")]: unitTest(s),
      [path.join(dir, "src/integrationTest/java", pkgDir, "ServiceBootIT.java")]: bootIT(s),
    };
    if (s.features.includes("data")) files[path.join(dir, "src/main/resources/db/migration/V1__init.sql")] = migration(s);
    for (const [f, c] of Object.entries(files)) if (writeIfAbsent(f, c)) created.push(path.relative(root, f));
  }
  // settings.gradle.kts 등록
  const settingsPath = path.join(root, "settings.gradle.kts");
  let settings = fs.readFileSync(settingsPath, "utf8");
  for (const s of services) {
    const entry = `    "${s.name}",`;
    if (!settings.includes(entry)) {
      settings = settings.replace(/val services = listOf<String>\(\n/, (m) => m + entry + "\n");
    }
  }
  fs.writeFileSync(settingsPath, settings);
  // deploy/local/init-db.sql
  const initDb = path.join(root, "deploy/local/init-db.sql");
  let sql = fs.readFileSync(initDb, "utf8");
  for (const s of services.filter((x) => x.features.includes("data"))) {
    const line = `CREATE DATABASE ${dbOf(s.name)};`;
    if (!sql.includes(line)) sql += line + "\n";
  }
  fs.writeFileSync(initDb, sql);
  console.log(`phase1: ${created.length} files created for ${services.length} services`);
}

// ---------------- phase 2 ----------------
function phase2() {
  // 생성은 Gradle 이 한다: 먼저 ./gradlew <:services:x:openApiGenerate ...> 를 실행한 뒤 이 단계를 돌린다.
  const missing = services.filter((s) => !fs.existsSync(path.join(root, "services", s.name, "build/generated/openapi")));
  if (missing.length) {
    console.error("먼저 실행: ./gradlew " + missing.map((s) => `:services:${s.name}:openApiGenerate`).join(" "));
    process.exit(2);
  }
  let created = 0;
  for (const s of services) {
    const pkg = pkgOf(s.name);
    const genDir = path.join(root, "services", s.name, "build/generated/openapi/src/main/java", pkg.replace(/\./g, "/"), "api/generated");
    if (!fs.existsSync(genDir)) {
      console.warn(`  no generated api dir for ${s.name}`);
      continue;
    }
    const apis = fs.readdirSync(genDir).filter((f) => f.endsWith("Api.java") && f !== "ApiUtil.java");
    for (const f of apis) {
      const apiName = f.replace(/\.java$/, "");
      const target = path.join(root, "services", s.name, "src/main/java", pkg.replace(/\./g, "/"), "api", apiName.replace(/Api$/, "") + "Controller.java");
      if (writeIfAbsent(target, controller(pkg, apiName))) created++;
    }
  }
  console.log(`phase2: ${created} controller stubs created`);
}

if (phase === "1" || phase === "all") phase1();
if (phase === "2" || phase === "all") phase2();
