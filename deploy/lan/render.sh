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

INFRA=" postgres kafka connect redis zipkin s3 opensearch "
src_dir() { for d in services platform-services; do [ -d "$d/$1" ] && { echo "$d/$1"; return; }; done; echo "서비스 없음: $1" >&2; exit 1; }
yml() { echo "$(src_dir "$1")/src/main/resources/application.yml"; }
port_of() { awk '/^server:/{s=1;next} /^[^ ]/{s=0} s&&/^  port:/{print $2; exit}' "$(yml "$1")"; }
needs() { grep -q "\${$2" "$(yml "$1")"; }
db_of() { echo "${1//-/_}"; }
digest_of() {
  local f; f="$(src_dir "$1")/build/jib-image.digest"
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
        -s3.port.iceberg=0 -s3.port.lance=0 -bucket=pmart-product-content
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
        if needs "$c" PMART_DB_URL && [[ "$comps" == *" postgres "* ]]; then
          echo "    depends_on:"
          echo "      postgres: { condition: service_healthy }"
        fi
        echo "    environment:"
        if needs "$c" PMART_DB_URL; then
          [ -n "${WHERE[postgres-$pc]:-}" ] || { echo "$c 는 DB 가 필요한데 $pc 에 postgres 가 없음" >&2; exit 1; }
          echo "      PMART_DB_URL: jdbc:postgresql://postgres-$pc:15432/$(db_of "$c")"
          echo "      PMART_DB_USERNAME: pmart"
          echo "      PMART_DB_PASSWORD: \${PMART_DB_PASSWORD}"
          # PC 하나의 PostgreSQL 을 서비스 십여 개가 나눠 쓴다(max_connections 200)
          echo "      SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE: \"5\""
        fi
        needs "$c" PMART_KAFKA_BOOTSTRAP && echo "      PMART_KAFKA_BOOTSTRAP: kafka:9092"
        needs "$c" PMART_JWKS_URI && echo "      PMART_JWKS_URI: http://auth-service:$(port_of auth-service)/api/v1/internal/jwks"
        needs "$c" PMART_REDIS_URL && echo "      PMART_REDIS_URL: redis://redis:6379"
        needs "$c" PMART_OPENSEARCH_URL && echo "      PMART_OPENSEARCH_URL: http://opensearch:9200"
        echo "      PMART_ZIPKIN_ENDPOINT: http://zipkin:9411/api/v2/spans"
        if [ "$c" = auth-service ]; then
          echo "      PMART_AUTH_ISSUER: http://auth-service:$(port_of auth-service)"
          echo "      PMART_AUTH_SIGNING_KEYS_0_KID: \${PMART_AUTH_SIGNING_KID}"
          echo "      PMART_AUTH_SIGNING_KEYS_0_PRIVATEKEY: \${PMART_AUTH_SIGNING_KEY}"
          # 첫 관리자(ADR-0018). 해시만 넘긴다(원문은 .secrets/operator-admin-password)
          echo "      PMART_AUTH_OPERATOR_BOOTSTRAP_LOGINID: \${PMART_AUTH_OPERATOR_BOOTSTRAP_LOGINID}"
          echo "      PMART_AUTH_OPERATOR_BOOTSTRAP_PASSWORDHASH: \${PMART_AUTH_OPERATOR_BOOTSTRAP_PASSWORDHASH}"
          # 고객 Google 로그인 허용 aud(google-client-ids.conf). 클라이언트 ID 는 비밀이 아니고 secret 은 넘기지 않는다
          echo "      PMART_AUTH_CUSTOMER_GOOGLE_CLIENTIDS: \"$(google_client_ids)\""
        fi
        if needs "$c" PMART_S3_BUCKET; then
          [ -n "${WHERE[s3]:-}" ] || { echo "$c 는 s3 가 필요한데 배치에 s3 가 없음" >&2; exit 1; }
          echo "      PMART_S3_ENDPOINT: http://s3:8333"
          echo "      PMART_S3_BUCKET: pmart-product-content"
          echo "      PMART_S3_ACCESS_KEY: \${PMART_S3_ACCESS_KEY}"
          echo "      PMART_S3_SECRET_KEY: \${PMART_S3_SECRET_KEY}"
        fi
        if [ "$c" = api-gateway ]; then
          echo "      PMART_GATEWAY_CORS_ALLOWEDORIGINS: \"$(cors_origins)\""
        fi
        if [ "$c" = payment-service ]; then
          # 토스 결제위젯 테스트 키(D5, .secrets/toss-*). 운영 키(live_)는 서비스가 기동을 거부한다
          echo "      PMART_TOSS_SECRET_KEY: \${PMART_TOSS_SECRET_KEY}"
          echo "      PMART_TOSS_CLIENT_KEY: \${PMART_TOSS_CLIENT_KEY}"
        fi
        echo
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
      needs "$c" PMART_DB_URL \
        && echo "SELECT 'CREATE DATABASE $(db_of "$c")' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = '$(db_of "$c")')\\gexec"
    done
  } > "$dir/init-db.sql"

  if [[ "$comps" == *" connect "* ]]; then
    cp deploy/local/connector-registrar.sh "$dir/"
    mkdir -p "$dir/connectors" && cp deploy/local/connectors/*.json "$dir/connectors/"
  fi

  {
    echo "PMART_DB_PASSWORD=$DB_PASSWORD"
    if [[ "$comps" == *" auth-service "* ]]; then
      echo "PMART_AUTH_SIGNING_KID=$(secret signing-kid)"
      echo "PMART_AUTH_SIGNING_KEY=$(grep -v -- '-----' "$SECRETS/signing-key.pem" | tr -d '\r\n')"
      echo "PMART_AUTH_OPERATOR_BOOTSTRAP_LOGINID=$(secret operator-admin-login)"
      # bcrypt 해시에 $ 가 있어 작은따옴표(compose .env 의 리터럴 값)
      echo "PMART_AUTH_OPERATOR_BOOTSTRAP_PASSWORDHASH='$(secret operator-admin-hash)'"
    fi
    if [[ "$comps" == *" s3 "* || "$comps" == *" product-content-service "* ]]; then
      echo "PMART_S3_ACCESS_KEY=$(secret s3-access-key)"
      echo "PMART_S3_SECRET_KEY=$(secret s3-secret-key)"
    fi
    if [[ "$comps" == *" payment-service "* ]]; then
      # 키 파일이 없으면 빈 값 → 결제 시작은 CONFIG_REQUIRED(서비스는 뜬다)
      echo "PMART_TOSS_SECRET_KEY=$([ -f "$SECRETS/toss-secret-key" ] && secret toss-secret-key)"
      echo "PMART_TOSS_CLIENT_KEY=$([ -f "$SECRETS/toss-client-key" ] && secret toss-client-key)"
    fi
  } > "$dir/.env"

  echo "$pc ${IP[$pc]} ${SSH[$pc]}" > "$dir/target"
  echo "rendered $dir ($(echo ${COMPS[$pc]} | wc -w) components)"
done
