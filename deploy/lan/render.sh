#!/usr/bin/env bash
# placement.conf → PC별 배포 파일 생성: deploy/lan/build/<pc>/{compose.yml,.env,init-db.sql,...}
# - 서비스 포트·필요 인프라(DB/Kafka/Redis/JWKS)는 각 서비스의 src/main/resources/application.yml 에서 읽는다(정본 한 곳).
# - 이미지: docker.io/heeji4445/pmart-<service>@<digest>. digest 는 jib push 결과(build/jib-image.digest). 없으면 중단.
# - 다른 PC 의 구성요소는 extra_hosts(<이름>:<IP>)로 가리킨다. 호스트 포트 = 컨테이너 포트라 주소가 어디서나 같다.
# - 비밀정보(.env)는 deploy/lan/.secrets 에서 읽어 build/ 에만 쓴다(둘 다 git 제외).
# - 컨테이너 IP 고정(SUBNET, 배치 순서대로 .10~): Docker Desktop VM 이 리셋되면 새 VM 이 이전 포트 전달 규칙(옛 컨테이너 IP)을
#   복원하는데 컨테이너는 IP 를 새로 받아 LAN 포트가 엉뚱한 컨테이너로 간다(docs/worklog/2026-10-27.md). IP 가 같으면 복원 규칙이 맞는다.
#   자동 배정은 IP_RANGE 로 몰아 고정 IP 와 겹치지 않게 한다. 네트워크 설정이 바뀌면 deploy 전에 down 이 필요하다.
set -euo pipefail
cd "$(dirname "$0")/../.."
LAN=deploy/lan
OUT=$LAN/build
SECRETS=$LAN/.secrets
REGISTRY="${PMART_IMAGE_REGISTRY:-docker.io/heeji4445}"
SUBNET_PREFIX=172.28.0   # 각 PC 의 기존 Docker 네트워크(172.17~19)와 겹치지 않는 대역

[ -f "$SECRETS/db-password" ] || { echo "비밀정보 없음: $LAN/init-secrets.sh 먼저 실행" >&2; exit 1; }

# ---- placement 읽기 (줄 이어쓰기 \ 처리) ----
declare -a PCS=()
declare -A IP=() SSH=() COMPS=() WHERE=()
while read -r name ip ssh comps; do
  [ -z "${name:-}" ] && continue
  PCS+=("$name"); IP[$name]=$ip; SSH[$name]=$ssh; COMPS[$name]="$comps"
  for c in $comps; do
    if [ "$c" = postgres ]; then WHERE[postgres-$name]=$name; continue; fi
    [ -n "${WHERE[$c]:-}" ] && { echo "중복 배치: $c (${WHERE[$c]}, $name)" >&2; exit 1; }
    WHERE[$c]=$name
  done
done < <(sed -e 's/#.*//' "$LAN/placement.conf" | sed -e ':a' -e '/\\$/N; s/\\\n//; ta' | tr -s ' \t' ' ')

INFRA=" postgres kafka connect redis zipkin s3 opensearch backoffice-web "
src_dir() { for d in services platform-services; do [ -d "$d/$1" ] && { echo "$d/$1"; return; }; done; echo "서비스 없음: $1" >&2; exit 1; }
yml() { echo "$(src_dir "$1")/src/main/resources/application.yml"; }
# 설정 저장소 정본(ADR-0029)으로 옮긴 앱: 저장소 최상위에 <앱>-lan.yml 이 있다. 포트 등 서비스 고유 설정은 <앱>.yml 에 있다
migrated() { [ -f "$LAN/.config-repo/$1-lan.yml" ]; }
port_of() {
  local p; p="$(awk '/^server:/{s=1;next} /^[^ ]/{s=0} s&&/^  port:/{print $2; exit}' "$(yml "$1")")"
  [ -z "$p" ] && [ -f "$LAN/.config-repo/$1.yml" ] && p="$(awk '/^server:/{s=1;next} /^[^ ]/{s=0} s&&/^  port:/{print $2; exit}' "$LAN/.config-repo/$1.yml")"
  echo "$p"
}
needs() { grep -q "\${$2" "$(yml "$1")"; }
# DB 가 필요한 앱 = Flyway 마이그레이션 폴더가 있는 앱(옮긴 앱의 yml 에는 ${PMART_DB_URL} 이 없다)
needs_db() { needs "$1" PMART_DB_URL || [ -d "$(src_dir "$1")/src/main/resources/db/migration" ]; }
db_of() { echo "${1//-/_}"; }
digest_of() {
  local f; f="$(src_dir "$1")/build/jib-image.digest"
  # config-push 는 설정 저장소 내용만 쓰므로 digest 없이 렌더한다(PMART_RENDER_NO_DIGEST=1). deploy 는 지금처럼 멈춘다
  if [ ! -f "$f" ] && [ "${PMART_RENDER_NO_DIGEST:-}" = 1 ]; then echo "sha256:not-pushed"; return; fi
  [ -f "$f" ] || { echo "digest 없음: $f (먼저 jib push)" >&2; exit 1; }
  cat "$f"
}

for c in kafka redis zipkin auth-service; do
  [ -n "${WHERE[$c]:-}" ] || echo "경고: $c 가 배치되지 않음" >&2
done

secret() { tr -d '\r\n' < "$SECRETS/$1"; } # Windows openssl 은 CRLF 로 쓴다
# Google OAuth 클라이언트 ID 목록(google-client-ids.conf → 쉼표). 없거나 비면 빈 값 → Google 로그인 CONFIG_REQUIRED
google_client_ids() {
  [ -f "$LAN/google-client-ids.conf" ] || return 0
  sed -e 's/#.*//' -e 's/[[:space:]]//g' "$LAN/google-client-ids.conf" | grep -v '^$' | paste -sd, - || true
}
# 게이트웨이 웹 CORS 허용 출처(cors-origins.conf → 쉼표 목록). 파일이 없거나 비면 빈 값(CORS 헤더 없음)
cors_origins() {
  [ -f "$LAN/cors-origins.conf" ] || return 0
  sed -e 's/#.*//' -e 's/[[:space:]]//g' "$LAN/cors-origins.conf" | grep -v '^$' | paste -sd, - || true
}
DB_PASSWORD="$(secret db-password)"
rm -rf "$OUT"

# ---- 설정 저장소(ADR-0027) ----
# Spring 앱의 설정값은 compose 환경변수가 아니라 config-server(GitHub code-and-pick/pmart-config, lan/)에서 받는다.
# 여기서 그 내용을 $CFG_OUT 에 만든다: application.yml(모든 앱 공통) + <앱>.yml. 키는 대부분 기존 ${PMART_*} 자리표시자 이름이다.
# 비밀값은 '{cipher}…'. 같은 평문의 기존 암호문($CFG_CLONE 사본)은 그대로 써서 저장소에 의미 없는 변경을 만들지 않는다.
# 저장소 반영은 lan.sh config-push(사람이 확인), deploy 는 반영 안 된 내용이 있으면 멈춘다.
for f in config-encrypt-key config-encrypt-salt config-password config-deploy-key; do
  [ -f "$SECRETS/$f" ] || { echo "비밀정보 없음: $SECRETS/$f ($LAN/init-secrets.sh 먼저)" >&2; exit 1; }
done
CFG_OUT=$OUT/config-repo/lan
CFG_CLONE=$LAN/.config-repo
mkdir -p "$CFG_OUT"
CIPHER_IN=$OUT/.cipher-in.tsv; CIPHER_OUT=$OUT/.cipher-out.tsv; : > "$CIPHER_IN"
cipher_n=0
yquote() { local v="$1"; printf "'%s'" "${v//\'/\'\'}"; } # YAML 작은따옴표 문자열
# cfg <파일> <들여쓰기+키> <값>: 평문 한 줄
cfg() { echo "$2: $(yquote "$3")" >> "$CFG_OUT/$1"; }
# cfg_secret <파일> <들여쓰기+키> <평문>: 자리표시자를 쓰고 암호화 목록에 넣는다(평문은 build/ 임시 파일에만, 끝나면 지운다)
cfg_secret() {
  local file="$1" key="$2" plain="$3" prev=""
  cipher_n=$((cipher_n + 1))
  # 기존 암호문: 같은 파일에서 먼저, 없으면 다른 파일의 같은 키(키를 다른 파일로 옮겨도 재사용 — 같은 평문일 때만 쓰인다)
  if [ -d "$CFG_CLONE/lan" ]; then
    prev="$( { [ -f "$CFG_CLONE/lan/$file" ] && grep -hF -- "$key: '{cipher}" "$CFG_CLONE/lan/$file"; \
      grep -hF -- "$(echo "$key" | sed 's/^ *//'): '{cipher}" "$CFG_CLONE"/lan/*.yml; } 2>/dev/null \
      | head -1 | sed -e "s/^.*'{cipher}//" -e "s/'.*$//" || true)"
  fi
  printf '%s\t%s\t%s\n' "S$cipher_n" "$(printf '%s' "$plain" | base64 -w0)" "$prev" >> "$CIPHER_IN"
  echo "$key: '{cipher}@@S$cipher_n@@'" >> "$CFG_OUT/$file"
}
cfg_header() { echo "# 생성 파일(pmart-backend deploy/lan/render.sh) — 여기서 고치지 말고 render.sh·placement.conf·.secrets 를 고친다. ADR-0027" > "$CFG_OUT/$1"; }

# 공통 파일에는 거의 모든 앱이 쓰는 비밀 아닌 값만. DB·Redis·OpenSearch 처럼 일부만 쓰는 값(특히 비밀)은 쓰는 앱 파일에만 둔다
cfg_header application.yml
cfg application.yml PMART_KAFKA_BOOTSTRAP kafka:9092
cfg application.yml PMART_JWKS_URI "http://auth-service:$(port_of auth-service)/api/v1/internal/jwks"
cfg application.yml PMART_ZIPKIN_ENDPOINT http://zipkin:9411/api/v2/spans
for pc in "${PCS[@]}"; do
  dir=$OUT/$pc; mkdir -p "$dir"
  comps=" ${COMPS[$pc]} "

  # extra_hosts: 이 PC 에 없는 모든 구성요소
  hosts=""
  for c in "${!WHERE[@]}"; do
    [ "${WHERE[$c]}" = "$pc" ] && continue
    hosts+="      - \"$c:${IP[${WHERE[$c]}]}\""$'\n'
  done
  hosts="$(printf '%s' "$hosts" | sort)"

  {
    echo "# 생성 파일(deploy/lan/render.sh) — 직접 고치지 말고 placement.conf·render.sh 를 고친다."
    echo "# PC: $pc (${IP[$pc]})"
    echo "name: pmart-lan"
    echo
    echo "x-common: &common"
    echo "  restart: unless-stopped"
    echo "  logging: { driver: json-file, options: { max-size: \"20m\", max-file: \"3\" } }"
    if [ -n "$hosts" ]; then echo "  extra_hosts:"; echo "$hosts" | sed 's/^      /    /'; fi
    echo
    echo "services:"

    n=10
    for c in ${COMPS[$pc]}; do
      ip_addr="$SUBNET_PREFIX.$n"; n=$((n + 1))
      case "$c" in
      postgres)
        cat <<EOF
  postgres:
    <<: *common
    image: postgres:17-alpine
    # 컨테이너 안에서도 15432 로 듣는다 → 같은 PC(별칭 postgres-$pc)와 다른 PC(extra_hosts) 모두 postgres-$pc:15432
    command: ["postgres", "-p", "15432", "-c", "wal_level=logical", "-c", "max_wal_senders=20", "-c", "max_replication_slots=20", "-c", "max_connections=200"]
    environment:
      POSTGRES_USER: pmart
      POSTGRES_PASSWORD: \${PMART_DB_PASSWORD}
      POSTGRES_DB: pmart
    ports: ["15432:15432"]
    networks: { default: { aliases: [postgres-$pc], ipv4_address: $ip_addr } }
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./init-db.sql:/docker-entrypoint-initdb.d/10-init-db.sql:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U pmart -p 15432"]
      interval: 5s
      timeout: 3s
      retries: 30

EOF
        ;;
      kafka)
        cat <<EOF
  kafka:
    <<: *common
    image: apache/kafka:4.1.1
    ports: ["9092:9092"]
    networks: { default: { ipv4_address: $ip_addr } }
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@localhost:9093
      # 리스너 하나: 같은 PC 는 컨테이너 DNS, 다른 PC 는 extra_hosts(kafka → ${IP[$pc]}) 로 kafka:9092 에 닿는다
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"
      KAFKA_LOG_DIRS: /var/lib/kafka/data
      CLUSTER_ID: pmart-lan-cluster-00000000001
    volumes:
      - kafkadata:/var/lib/kafka/data
    healthcheck:
      test: ["CMD-SHELL", "/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1"]
      interval: 10s
      timeout: 10s
      retries: 30

EOF
        ;;
      connect)
        db_hosts=""
        for p in "${PCS[@]}"; do [ -n "${WHERE[postgres-$p]:-}" ] && db_hosts+="postgres-$p:15432 "; done
        cat <<EOF
  connect:
    <<: *common
    image: quay.io/debezium/connect:3.7
    ports: ["8083:8083"]
    networks: { default: { ipv4_address: $ip_addr } }
    environment:
      # 로그는 stdout 만(파일 appender 끔) → 위 json-file 크기 제한을 받는다. 파일 appender 는 회전만 하고 지우지 않아
      # Kafka 불통 때 AdminClient rebootstrap 반복 로그로 /kafka/logs 가 142GB 까지 찼다(2026-10-25~26).
      CONNECT_LOG4J_LOGGERS: "INFO, stdout"
      # 같은 반복 로그(AdminMetadataManager "Rebootstrapping …")의 출처. 이름은 소문자 패키지만 가능(엔트리포인트가 소문자로 바꾼다)
      CONNECT_LOG4J_LOGGER_ORG_APACHE_KAFKA_CLIENTS_ADMIN_INTERNALS: WARN
      BOOTSTRAP_SERVERS: kafka:9092
      GROUP_ID: pmart-connect
      CONFIG_STORAGE_TOPIC: _connect.configs
      OFFSET_STORAGE_TOPIC: _connect.offsets
      STATUS_STORAGE_TOPIC: _connect.status
      CONFIG_STORAGE_REPLICATION_FACTOR: 1
      OFFSET_STORAGE_REPLICATION_FACTOR: 1
      STATUS_STORAGE_REPLICATION_FACTOR: 1
      KEY_CONVERTER: org.apache.kafka.connect.storage.StringConverter
      VALUE_CONVERTER: org.apache.kafka.connect.storage.StringConverter
      CONNECT_REST_ADVERTISED_HOST_NAME: connect
    healthcheck:
      test: ["CMD-SHELL", "curl -sf http://localhost:8083/connectors >/dev/null"]
      interval: 10s
      timeout: 5s
      retries: 30

  connector-registrar:
    <<: *common
    image: postgres:17-alpine
    depends_on:
      connect: { condition: service_healthy }
    entrypoint: ["/bin/sh", "/registrar/connector-registrar.sh"]
    environment:
      CONNECT_URL: http://connect:8083
      DB_HOSTS: "${db_hosts% }"
      DB_USER: pmart
      DB_PASSWORD: \${PMART_DB_PASSWORD}
      INTERVAL: "15"
    volumes:
      - ./connector-registrar.sh:/registrar/connector-registrar.sh:ro
      - ./connectors:/connectors:ro

EOF
        ;;
      redis)
        cat <<EOF
  redis:
    <<: *common
    # 게이트웨이 처리율 제한·delivery-tracking 위치 캐시. 원장 용도 금지.
    image: redis:7-alpine
    ports: ["6379:6379"]
    networks: { default: { ipv4_address: $ip_addr } }
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 20

EOF
        ;;
      s3)
        # 상품 이미지 저장소(SeaweedFS mini, ADR-0016). deploy/local 과 같은 이미지·설정. 자격 증명은 .secrets 에서, 시작 시 파일로 쓴다.
        cat <<EOF
  s3:
    <<: *common
    image: chrislusf/seaweedfs:4.48
    environment:
      S3_ACCESS_KEY: \${PMART_S3_ACCESS_KEY}
      S3_SECRET_KEY: \${PMART_S3_SECRET_KEY}
    entrypoint:
      - sh
      - -c
      - >-
        umask 077 &&
        printf '{"identities":[{"name":"app","credentials":[{"accessKey":"%s","secretKey":"%s"}],"actions":["Admin","Read","List","Tagging","Write"]}]}'
        "\$\$S3_ACCESS_KEY" "\$\$S3_SECRET_KEY" > /tmp/s3.json &&
        chown seaweed /tmp/s3.json &&
        exec /entrypoint.sh mini -dir=/data -s3.config=/tmp/s3.json -admin.ui=false
        -s3.port.iceberg=0 -s3.port.lance=0 -bucket=pmart-product-content,pmart-customer
    ports: ["8333:8333"]
    networks: { default: { ipv4_address: $ip_addr } }
    volumes:
      - s3data:/data
    healthcheck:
      test: ["CMD-SHELL", "curl -s -o /dev/null http://127.0.0.1:8333/"]
      interval: 5s
      timeout: 3s
      retries: 30

EOF
        ;;
      backoffice-web)
        # 임시 백오피스(React SPA) 정적 서빙 — CORS 출처 http://d603-16(cors-origins.conf)과 같은 PC·80. 파일은 render 가 backoffice/ 에 둔다
        cat <<EOF
  backoffice-web:
    <<: *common
    image: nginx:1.27-alpine
    ports: ["80:80"]
    networks: { default: { ipv4_address: $ip_addr } }
    volumes:
      - ./backoffice:/usr/share/nginx/html:ro
      - ./backoffice-nginx.conf:/etc/nginx/conf.d/default.conf:ro
    healthcheck:
      test: ["CMD-SHELL", "wget -q -O /dev/null http://127.0.0.1/ || exit 1"]
      interval: 10s
      timeout: 3s
      retries: 10

EOF
        ;;
      opensearch)
        # 상품 검색 색인(search-service 전용). deploy/local 과 같은 이미지·설정. 다른 PC 의 search-service 가 부르므로 9200 을 연다
        # (보안 플러그인 끔 — LAN 시험망, 공개 상품 정보만 색인).
        cat <<EOF
  opensearch:
    <<: *common
    image: opensearchproject/opensearch:2.19.1
    environment:
      discovery.type: single-node
      DISABLE_SECURITY_PLUGIN: "true"
      DISABLE_INSTALL_DEMO_CONFIG: "true"
      OPENSEARCH_JAVA_OPTS: -Xms512m -Xmx512m
    ports: ["9200:9200"]
    networks: { default: { ipv4_address: $ip_addr } }
    volumes:
      - osdata:/usr/share/opensearch/data
    healthcheck:
      test: ["CMD-SHELL", "curl -s -o /dev/null http://127.0.0.1:9200/"]
      interval: 5s
      timeout: 3s
      retries: 60

EOF
        ;;
      zipkin)
        cat <<EOF
  zipkin:
    <<: *common
    image: openzipkin/zipkin:3
    ports: ["9411:9411"]
    networks: { default: { ipv4_address: $ip_addr } }

EOF
        ;;
      *)
        port="$(port_of "$c")"; [ -n "$port" ] || { echo "포트 없음: $c" >&2; exit 1; }
        echo "  $c:"
        echo "    <<: *common"
        echo "    image: $REGISTRY/pmart-$c@$(digest_of "$c")"
        echo "    ports: [\"$port:$port\"]"
        echo "    networks: { default: { ipv4_address: $ip_addr } }"
        echo "    mem_limit: 768m"
        if needs_db "$c" && [[ "$comps" == *" postgres "* ]]; then
          echo "    depends_on:"
          echo "      postgres: { condition: service_healthy }"
        fi
        echo "    environment:"
        if [ "$c" = config-server ]; then
          # 서버 자신의 비밀은 configtree 디렉터리(파일 이름 = 속성 이름). 저장소 주소·폴더는 application.yml 기본값(GitHub, lan)
          echo "      PMART_ZIPKIN_ENDPOINT: http://zipkin:9411/api/v2/spans"
          echo "    volumes:"
          echo "      - ./config-server-secrets:/run/secrets/config-server:ro"
          echo
          continue
        fi
        # 설정은 config-server 에서(ADR-0027). 받지 못하면 기동하지 않는다(fail-fast → restart 로 다시 시도)
        echo "      SPRING_CLOUD_CONFIG_ENABLED: \"true\""
        echo "      SPRING_CLOUD_CONFIG_URI: http://config-server:$(port_of config-server)"
        echo "      SPRING_CLOUD_CONFIG_FAIL_FAST: \"true\""
        echo "      SPRING_CLOUD_CONFIG_USERNAME: pmart"
        echo "      SPRING_CLOUD_CONFIG_PASSWORD: \${PMART_CONFIG_PASSWORD}"
        if migrated "$c"; then
          # 저장소 정본으로 옮긴 앱(ADR-0029): lan 프로필로 <앱>-lan.yml·application-lan.yml 을 받는다. lan/<앱>.yml 은 만들지 않는다
          echo "      SPRING_PROFILES_ACTIVE: lan"
          echo
          continue
        fi
        echo

        # ---- (옮기기 전 앱만) 이 앱의 설정 파일(lan/<앱>.yml). 공통값은 lan/application.yml — ADR-0027 방식 ----
        f="$c.yml"; cfg_header "$f"
        if needs "$c" PMART_DB_URL; then
          [ -n "${WHERE[postgres-$pc]:-}" ] || { echo "$c 는 DB 가 필요한데 $pc 에 postgres 가 없음" >&2; exit 1; }
          cfg "$f" PMART_DB_URL "jdbc:postgresql://postgres-$pc:15432/$(db_of "$c")"
          cfg "$f" PMART_DB_USERNAME pmart
          cfg_secret "$f" PMART_DB_PASSWORD "$DB_PASSWORD"
          {
            echo "spring:"
            echo "  datasource:"
            echo "    hikari:"
            echo "      maximum-pool-size: 5 # PC 하나의 PostgreSQL 을 서비스 십여 개가 나눠 쓴다(max_connections 200)"
          } >> "$CFG_OUT/$f"
        fi
        needs "$c" PMART_REDIS_URL && cfg "$f" PMART_REDIS_URL redis://redis:6379
        needs "$c" PMART_OPENSEARCH_URL && cfg "$f" PMART_OPENSEARCH_URL http://opensearch:9200
        if [ "$c" = auth-service ]; then
          cfg "$f" PMART_AUTH_ISSUER "http://auth-service:$(port_of auth-service)"
          # 고객 Google 로그인 허용 aud(google-client-ids.conf). 클라이언트 ID 는 비밀이 아니고 secret 은 넘기지 않는다
          cfg "$f" PMART_AUTH_CUSTOMER_GOOGLE_CLIENTIDS "$(google_client_ids)"
          # 서명키·첫 관리자는 자리표시자가 아니라 속성 바인딩이라 정식 속성 이름으로 둔다(첫 관리자는 해시만, ADR-0018)
          {
            echo "pmart:"
            echo "  auth:"
            echo "    signing:"
            echo "      keys:"
          } >> "$CFG_OUT/$f"
          cfg "$f" "        - kid" "$(secret signing-kid)"
          cfg_secret "$f" "          private-key" "$(grep -v -- '-----' "$SECRETS/signing-key.pem" | tr -d '\r\n')"
          { echo "    operator:"; echo "      bootstrap:"; } >> "$CFG_OUT/$f"
          cfg "$f" "        login-id" "$(secret operator-admin-login)"
          cfg_secret "$f" "        password-hash" "$(secret operator-admin-hash)"
        fi
        if needs "$c" PMART_S3_BUCKET; then
          [ -n "${WHERE[s3]:-}" ] || { echo "$c 는 s3 가 필요한데 배치에 s3 가 없음" >&2; exit 1; }
          cfg "$f" PMART_S3_ENDPOINT http://s3:8333
          # 서비스마다 버킷 하나: pmart-<서비스 이름에서 -service 뺀 것> (product-content·customer). s3 의 -bucket= 목록과 맞춘다
          cfg "$f" PMART_S3_BUCKET "pmart-${c%-service}"
          cfg_secret "$f" PMART_S3_ACCESS_KEY "$(secret s3-access-key)"
          cfg_secret "$f" PMART_S3_SECRET_KEY "$(secret s3-secret-key)"
        fi
        [ "$c" = api-gateway ] && cfg "$f" PMART_GATEWAY_CORS_ALLOWEDORIGINS "$(cors_origins)"
        # 예약 슬롯 1시간당 정원 — LAN 시험 설정값(사용자 결정 2026-10-31). 운영은 승인된 정책값
        [ "$c" = delivery-capacity-service ] && cfg "$f" PMART_CAPACITY_SLOTCAPACITY 20
        # MFC 작업 여력: 주문 1건 = 1단위, 1시간 30단위 — LAN 시험 설정값(사용자 결정 2026-10-30). 운영은 승인된 정책값
        [ "$c" = mfc-capacity-service ] && cfg "$f" PMART_CAPACITY_UNITSPERHOUR 30
        if [ "$c" = delivery-serviceability-service ]; then
          # 카카오 로컬 REST API 키(ADR-0023). 파일이 없으면 빈 값 → 주소 변환·판정이 CONFIG_REQUIRED
          if [ -f "$SECRETS/kakao-rest-api-key" ]; then
            cfg_secret "$f" PMART_KAKAO_REST_API_KEY "$(secret kakao-rest-api-key)"
          else
            cfg "$f" PMART_KAKAO_REST_API_KEY ""
          fi
        fi
        if [ "$c" = payment-service ]; then
          # 토스 결제위젯 테스트 키(D5). 키 파일이 없으면 빈 값 → 결제 시작 CONFIG_REQUIRED. 운영 키(live_)는 서비스가 기동을 거부한다
          if [ -f "$SECRETS/toss-secret-key" ]; then
            cfg_secret "$f" PMART_TOSS_SECRET_KEY "$(secret toss-secret-key)"
          else
            cfg "$f" PMART_TOSS_SECRET_KEY ""
          fi
          # 클라이언트 키(gck)는 공개 키라 평문
          cfg "$f" PMART_TOSS_CLIENT_KEY "$([ -f "$SECRETS/toss-client-key" ] && secret toss-client-key)"
        fi
        ;;
      esac
    done

    echo "networks:"
    echo "  default:"
    echo "    ipam:"
    echo "      config:"
    echo "        - subnet: $SUBNET_PREFIX.0/24"
    echo "          ip_range: $SUBNET_PREFIX.192/26 # 자동 배정(connector-registrar 등)은 여기서만"
    echo

    vols=""
    [[ "$comps" == *" postgres "* ]] && vols+="  pgdata:"$'\n'
    [[ "$comps" == *" kafka "* ]] && vols+="  kafkadata:"$'\n'
    [[ "$comps" == *" s3 "* ]] && vols+="  s3data:"$'\n'
    [[ "$comps" == *" opensearch "* ]] && vols+="  osdata:"$'\n'
    [ -n "$vols" ] && printf 'volumes:\n%s' "$vols"
  } > "$dir/compose.yml"

  # init-db.sql: 이 PC 서비스 중 DB 가 필요한 것만
  {
    echo "-- 생성 파일(deploy/lan/render.sh). 볼륨이 처음 만들어질 때 실행되고, lan.sh deploy 가 매번 다시 실행한다(없는 DB 만 만든다)."
    for c in ${COMPS[$pc]}; do
      [[ "$INFRA" == *" $c "* ]] && continue
      needs_db "$c" \
        && echo "SELECT 'CREATE DATABASE $(db_of "$c")' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = '$(db_of "$c")')\\gexec"
    done
  } > "$dir/init-db.sql"

  if [[ "$comps" == *" connect "* ]]; then
    cp deploy/local/connector-registrar.sh "$dir/"
    mkdir -p "$dir/connectors" && cp deploy/local/connectors/*.json "$dir/connectors/"
  fi

  # 임시 백오피스 정적 파일: lan.sh publish-backoffice 가 둔 빌드(backoffice-dist, git 제외). 없으면 안내 페이지
  if [[ "$comps" == *" backoffice-web "* ]]; then
    cp "$LAN/backoffice/nginx.conf" "$dir/backoffice-nginx.conf"
    mkdir -p "$dir/backoffice"
    if [ -f "$LAN/backoffice-dist/index.html" ]; then
      cp -r "$LAN/backoffice-dist/." "$dir/backoffice/"
    else
      cp "$LAN/backoffice/placeholder.html" "$dir/backoffice/index.html"
    fi
  fi

  # .env: Spring 이 아닌 컨테이너(postgres·connector-registrar·s3)의 비밀 + config-server 접속 비밀번호만.
  # Spring 앱의 설정·비밀은 config-server 에서 받는다(ADR-0027).
  {
    echo "PMART_DB_PASSWORD=$DB_PASSWORD"
    echo "PMART_CONFIG_PASSWORD=$(secret config-password)"
    if [[ "$comps" == *" s3 "* ]]; then
      echo "PMART_S3_ACCESS_KEY=$(secret s3-access-key)"
      echo "PMART_S3_SECRET_KEY=$(secret s3-secret-key)"
    fi
  } > "$dir/.env"

  # config-server 자신의 비밀(configtree: 파일 이름 = 속성 이름). build/ 에만 쓴다(git 제외)
  if [[ "$comps" == *" config-server "* ]]; then
    d="$dir/config-server-secrets"; mkdir -p "$d"
    secret config-encrypt-key > "$d/encrypt.key"
    secret config-encrypt-salt > "$d/encrypt.salt"
    secret config-password > "$d/spring.security.user.password"
    tr -d '\r' < "$SECRETS/config-deploy-key" > "$d/spring.cloud.config.server.git.private-key"
    hk="$(sed -e 's/#.*//' "$LAN/config-server/github-host-key.conf" | grep -v '^[[:space:]]*$' | head -1)"
    printf '%s' "${hk%% *}" > "$d/spring.cloud.config.server.git.host-key-algorithm"
    printf '%s' "${hk#* }" > "$d/spring.cloud.config.server.git.host-key"
  fi

  echo "$pc ${IP[$pc]} ${SSH[$pc]}" > "$dir/target"
  echo "rendered $dir ($(echo ${COMPS[$pc]} | wc -w) components)"
done

# ---- 설정 저장소 비밀값 암호화: 자리표시자 @@S<n>@@ → 암호문 ----
# 서버와 같은 EncryptorFactory(ConfigCipherTool). 경로는 Windows Java 가 읽는 형식으로 넘긴다(Git Bash pwd -W)
root_w="$(pwd -W 2>/dev/null || pwd)"
./gradlew -q :platform-services:config-server:configCipher \
  -PcipherIn="$root_w/$CIPHER_IN" -PcipherOut="$root_w/$CIPHER_OUT" \
  -PcipherKeyFile="$root_w/$SECRETS/config-encrypt-key" -PcipherSaltFile="$root_w/$SECRETS/config-encrypt-salt"
rm -f "$CIPHER_IN"
while IFS=$'\t' read -r id c; do
  c="${c%$'\r'}"; [ -n "$id" ] || continue # Windows 줄 끝 방어
  # 암호문은 16진수라 sed 구분자와 겹치지 않는다
  sed -i "s|{cipher}@@$id@@|{cipher}$c|" "$CFG_OUT"/*.yml
done < "$CIPHER_OUT"
rm -f "$CIPHER_OUT"
if grep -l '@@S[0-9]*@@' "$CFG_OUT"/*.yml >/dev/null 2>&1; then
  echo "설정 저장소 암호화 누락: $(grep -l '@@S[0-9]*@@' "$CFG_OUT"/*.yml)" >&2; exit 1
fi
# 앱별 설정이 없으면 파일을 두지 않는다(서버는 공통 파일만 돌려준다)
for f in "$CFG_OUT"/*.yml; do
  [ "$(basename "$f")" = application.yml ] && continue
  grep -qv '^#' "$f" || rm -f "$f"
done
echo "rendered $CFG_OUT ($(ls "$CFG_OUT" | wc -l) files) — 저장소 반영: deploy/lan/lan.sh config-push"
