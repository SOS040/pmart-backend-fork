#!/bin/sh
# OR-Tools(CP-SAT·Routing) 시험을 리눅스 컨테이너에서 돌린다 — Windows 스마트 앱 컨트롤이 ortools.dll 을 막는 PC 용(A6, PC3 2026-11-12).
# 사용(Git Bash):  sh tools/ortools-test-docker.sh [gradle 작업 ...]
#   기본 작업: :services:mfc-capacity-service:test  (OR-Tools 를 쓰는 서비스: libs.ortools.java 의존 서비스)
# - 저장소를 읽기 전용으로 붙여 컨테이너 안 /work 로 복사한 뒤 빌드한다(호스트 build/·.gradle/ 와 섞이지 않게 — Windows 데몬과 파일 잠금 충돌 방지).
#   .git·build·.gradle·deploy/lan/.secrets·.config-repo 는 복사하지 않는다.
# - Gradle 캐시는 Docker 볼륨 pmart-ortools-gradle 에 남겨 두 번째부터 빠르다.
# - 결과(test-results·reports)는 build/ortools-docker/<서비스>/ 로 꺼낸다. 종료 코드 = Gradle 종료 코드.
# - 베이스 이미지는 운영 이미지와 같은 digest(build-logic pmart.container-image.gradle.kts)를 읽어 쓴다.
set -eu
cd "$(dirname "$0")/.."

image="$(sed -n 's/.*image = "\(eclipse-temurin:21-jdk@sha256:[0-9a-f]*\)".*/\1/p' build-logic/src/main/kotlin/pmart.container-image.gradle.kts | head -1)"
[ -n "$image" ] || { echo "베이스 이미지 digest 를 build-logic 에서 찾지 못함" >&2; exit 2; }
tasks="${*:-:services:mfc-capacity-service:test}"

# Git Bash 는 /src 같은 인자를 Windows 경로로 바꾼다 — 끄고, 호스트 경로는 Windows 형식으로 넘긴다
src="$(pwd -W 2>/dev/null || pwd)"
mkdir -p build/ortools-docker
out="$(cd build/ortools-docker && (pwd -W 2>/dev/null || pwd))"

MSYS_NO_PATHCONV=1 docker run --rm \
  -v "$src:/src:ro" \
  -v pmart-ortools-gradle:/root/.gradle \
  -v "$out:/out" \
  -e TASKS="$tasks" \
  "$image" sh -c '
    set -u
    mkdir -p /work
    cd /src && tar -cf - \
      --exclude=./.git --exclude=./.gradle --exclude="*/build" --exclude=./build \
      --exclude=./deploy/lan/.secrets --exclude=./deploy/lan/.config-repo --exclude=./.kotlin . \
      | tar -xf - -C /work
    cd /work && sed -i "s/\r$//" gradlew
    sh ./gradlew --no-daemon --console=plain $TASKS; rc=$?
    rm -rf /out/*
    for d in services/*/build platform-services/*/build; do
      [ -d "$d/test-results" ] || continue
      n="$(basename "$(dirname "$d")")"; mkdir -p "/out/$n"
      cp -r "$d/test-results" "/out/$n/" 2>/dev/null; [ -d "$d/reports" ] && cp -r "$d/reports" "/out/$n/"
    done
    exit $rc'
