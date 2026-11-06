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

# ---- Kafka KRaft 클러스터(ADR-0037): placement 의 kafka-1·kafka-2·kafka-3 ----
KAFKA_NODES=""; KAFKA_VOTERS=""; KAFKA_BOOTSTRAP=""
for c in kafka-1 kafka-2 kafka-3; do
  [ -n "${WHERE[$c]:-}" ] || continue
  KAFKA_NODES+="$c "
  KAFKA_VOTERS+="${KAFKA_VOTERS:+,}${c#kafka-}@$c:9094"
  KAFKA_BOOTSTRAP+="${KAFKA_BOOTSTRAP:+,}$c:9092"
done
[ -n "${WHERE[kafka]:-}" ] && { echo "placement.conf: kafka 대신 kafka-1·kafka-2·kafka-3 (ADR-0037)" >&2; exit 1; }
[ "$KAFKA_NODES" = "kafka-1 kafka-2 kafka-3 " ] || echo "경고: Kafka 노드가 3개가 아니다($KAFKA_NODES) — 복제 3·min ISR 2 는 3노드 전제" >&2
KAFKA_RF=3; KAFKA_MIN_ISR=2
# 클러스터 ID: 세 노드가 같아야 하고 볼륨을 만든 뒤에는 바꾸면 안 된다. 비밀이 아니므로 여기 고정(2026-11-05 생성, base64url 16바이트)
KAFKA_CLUSTER_ID=mQfULla_AX-vG10Mf2a4kw
# 토픽 크기 지표(ADR-0034)를 재는 노드·PC — 리더 기준이라 한 노드에서 클러스터 전체를 본다
KAFKA_METRICS_NODE=kafka-1

INFRA=" postgres kafka-1 kafka-2 kafka-3 connect redis zipkin s3 es-search es-logs backoffice-web prometheus alertmanager grafana kibana logstash-search logstash-logs log-shipper es-init-search es-init-logs host-disk log-janitor config-relay es-exporter-search es-exporter-logs kafka-exporter "
# extra_hosts 에서 뺄 구성요소(아래 extra_hosts 설명). 새로 붙이는 보조 구성요소는 여기에 둔다
NO_EXTRA_HOST=" es-exporter-search es-exporter-logs kafka-exporter "
ELASTIC=$LAN/elastic
SEARCH_SVC=services/search-service
MON=$LAN/monitoring
# 검색 ES 와 로그 ES 분리(ADR-0038): es-search·logstash-search(검색 색인) / es-logs·logstash-logs·kibana(로그)
for c in elasticsearch logstash; do
  [ -n "${WHERE[$c]:-}" ] && { echo "placement.conf: $c 대신 es-search·es-logs / logstash-search·logstash-logs (ADR-0038)" >&2; exit 1; }
done
# 로그 수집(ADR-0031): es-logs 가 배치돼 있으면 모든 PC 에 log-shipper(Filebeat), 각 ES PC 에 es-init-<종류>(색인·보존 준비, 1회)을
# 자동으로 붙인다(log-janitor 는 아래 host-disk 뒤). 줄 끝에 붙여 기존 구성요소의 고정 IP 가 바뀌지 않는다.
if [ -n "${WHERE[es-logs]:-}" ]; then
  for pc in "${PCS[@]}"; do COMPS[$pc]+=" log-shipper"; done
  COMPS[${WHERE[es-logs]}]+=" es-init-logs"
fi
if [ -n "${WHERE[es-search]:-}" ]; then COMPS[${WHERE[es-search]}]+=" es-init-search"; fi
# 디스크 감시(ADR-0030): prometheus 가 있으면 모든 PC 에 host-disk(node-exporter). Docker Desktop 이 마운트한 Windows C: 의
# 실제 여유 공간을 잰다 — 컨테이너·Elasticsearch 가 보는 디스크는 1TB 가상 디스크라 실제 C: 보다 먼저 차지 않는다.
if [ -n "${WHERE[prometheus]:-}" ]; then
  for pc in "${PCS[@]}"; do COMPS[$pc]+=" host-disk"; done
fi
# 로그 색인 크기 상한(색인 1GB·전체 4GB — ILM 은 기간만 지운다)
if [ -n "${WHERE[es-logs]:-}" ]; then COMPS[${WHERE[es-logs]}]+=" log-janitor"; fi
# GitHub 웹훅 중계(ADR-0033): config-server PC 에 config-relay. 맨 뒤에 붙여 기존 구성요소의 고정 IP 가 바뀌지 않는다
if [ -n "${WHERE[config-server]:-}" ]; then
  COMPS[${WHERE[config-server]}]+=" config-relay"; WHERE[config-relay]=${WHERE[config-server]}
fi
# Elasticsearch 지표(2026-11-03, ADR-0038 로 ES 마다): es-<종류>·prometheus 가 있으면 그 ES PC 에 es-exporter-<종류>. 맨 뒤에 붙여 고정 IP 유지
for k in search logs; do
  if [ -n "${WHERE[es-$k]:-}" ] && [ -n "${WHERE[prometheus]:-}" ]; then
    COMPS[${WHERE[es-$k]}]+=" es-exporter-$k"; WHERE[es-exporter-$k]=${WHERE[es-$k]}
  fi
done
# Prometheus 수집 대상: 배치된 모든 Spring 앱(인프라 제외) <PC IP>:<포트>/actuator/prometheus
apps_all() { for c in "${!WHERE[@]}"; do [[ "$c" == postgres-* || "$INFRA" == *" $c "* ]] || echo "$c"; done | sort; }
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
  [ -f "$f" ] || { echo "digest 없음: $f (먼저 jib push)" >&2; exit 1; }
  cat "$f"
}

for c in redis zipkin auth-service eureka-server; do
  [ -n "${WHERE[$c]:-}" ] || echo "경고: $c 가 배치되지 않음" >&2
done
if [ -n "${WHERE[config-relay]:-}" ] && [ ! -f "$SECRETS/smee-url" ]; then
  echo "비밀정보 없음: $SECRETS/smee-url ($LAN/init-secrets.sh 먼저 — 웹훅 중계 채널, ADR-0033)" >&2; exit 1
fi

secret() { tr -d '\r\n' < "$SECRETS/$1"; } # Windows openssl 은 CRLF 로 쓴다
DB_PASSWORD="$(secret db-password)"
rm -rf "$OUT"

# ---- 설정 저장소(ADR-0029) ----
# Spring 앱의 설정은 config-server 가 GitHub code-and-pick/pmart-config 에서 준다. 정본은 그 저장소(사람이 고친다) — 여기서 만들지 않는다.
# 이 스크립트는 compose·.env·config-server 비밀만 만들고, 배치(placement.conf)와 저장소가 어긋나지 않는지만 확인한다.
for f in config-encrypt-key config-encrypt-salt config-password config-deploy-key config-webhook-secret eureka-password; do
  [ -f "$SECRETS/$f" ] || { echo "비밀정보 없음: $SECRETS/$f ($LAN/init-secrets.sh 먼저)" >&2; exit 1; }
done
CFG_CLONE=$LAN/.config-repo
[ -f "$CFG_CLONE/application.yml" ] || { echo "설정 저장소 사본 없음: $CFG_CLONE (lan.sh deploy·config-push 가 clone 한다)" >&2; exit 1; }
for pc in "${PCS[@]}"; do
  dir=$OUT/$pc; mkdir -p "$dir"
  comps=" ${COMPS[$pc]} "

  # extra_hosts: 이 PC 에 없는 모든 구성요소
  hosts=""
  for c in "${!WHERE[@]}"; do
    [ "${WHERE[$c]}" = "$pc" ] && continue
    # 다른 PC 가 이름으로 부르지 않는 구성요소(Prometheus 는 IP 로 수집)는 넣지 않는다 — 넣으면 다른 PC 의 x-common 이 바뀌어
    # 그 PC 의 컨테이너(Kafka·Postgres 포함)가 전부 재생성된다(2026-11-03). 이미 배포된 이름은 같은 이유로 그대로 둔다
    [[ " $NO_EXTRA_HOST " == *" $c "* ]] && continue
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
      kafka-[123])
        # KRaft 클러스터 노드(ADR-0037): 노드마다 broker+controller. 이름 kafka-N 의 N 이 node id
        cat <<EOF
  $c:
    <<: *common
    image: apache/kafka:4.1.1
    # 9092 = 클라이언트·브로커 사이, 9094 = 컨트롤러 투표(9093 은 Alertmanager 가 쓴다)
    ports: ["9092:9092", "9094:9094"]
    networks: { default: { ipv4_address: $ip_addr } }
    environment:
      KAFKA_NODE_ID: ${c#kafka-}
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_CONTROLLER_QUORUM_VOTERS: $KAFKA_VOTERS
      # 같은 PC 는 컨테이너 DNS, 다른 PC 는 extra_hosts(kafka-N → 그 PC IP)로 kafka-N:9092·9094 에 닿는다
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9094
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://$c:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: $KAFKA_RF
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: $KAFKA_RF
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: $KAFKA_MIN_ISR
      KAFKA_DEFAULT_REPLICATION_FACTOR: $KAFKA_RF
      KAFKA_MIN_INSYNC_REPLICAS: $KAFKA_MIN_ISR
      # 브로커 자동 생성 끔(ADR-0037 결정 4) — 토픽은 Connect·Stream 바인더·topics-init.sh 가 명시적으로 만든다(메타데이터 요청만으로 생기지 않게)
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"
      KAFKA_LOG_DIRS: /var/lib/kafka/data
      CLUSTER_ID: $KAFKA_CLUSTER_ID
    volumes:
      # 단일 노드 때의 kafkadata 와 다른 이름 — 옛 볼륨은 롤백용으로 남는다(계획 6장)
      - kafkacluster:/var/lib/kafka/data
    mem_limit: 2g
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
      BOOTSTRAP_SERVERS: $KAFKA_BOOTSTRAP
      GROUP_ID: pmart-connect
      CONFIG_STORAGE_TOPIC: _connect.configs
      OFFSET_STORAGE_TOPIC: _connect.offsets
      STATUS_STORAGE_TOPIC: _connect.status
      # 내부 토픽 복제 수는 CONNECT_ 접두어로만 들어간다 — Debezium 엔트리포인트는 *_STORAGE_TOPIC 만 옮기고,
      # 접두어 없는 *_STORAGE_REPLICATION_FACTOR 는 무시해 이미지 기본 파일의 1 로 만들었다(2026-11-06 전환, 재배치로 3 복구)
      CONNECT_CONFIG_STORAGE_REPLICATION_FACTOR: $KAFKA_RF
      CONNECT_OFFSET_STORAGE_REPLICATION_FACTOR: $KAFKA_RF
      CONNECT_STATUS_STORAGE_REPLICATION_FACTOR: $KAFKA_RF
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
    # 마운트한 스크립트를 셸이 계속 열고 있으면 Windows(Docker Desktop)가 호스트 파일 덮어쓰기를 막는다(배포 scp 실패, 2026-11-03) → 복사해서 실행
    entrypoint: ["/bin/sh", "-c", "cp /registrar/connector-registrar.sh /tmp/r.sh && exec /bin/sh /tmp/r.sh"]
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
        # 임시 백오피스(React SPA) 정적 서빙 — CORS 출처 http://d603-16(pmart-config api-gateway-lan.yml)과 같은 PC·80. 파일은 render 가 backoffice/ 에 둔다
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
      es-search|es-logs)
        # Elasticsearch(ADR-0031, Eum 과 같은 판). ADR-0038: es-search = 상품 검색 색인만, es-logs = 앱 로그만. 보안 끔(LAN 시험망).
        # 색인을 자동으로 만들지 않는다 — 검색 ES 는 시스템 색인만, 로그 ES 는 pmart-logs-* 와 시스템 색인만(매핑 없는 색인·엉뚱한 쪽 색인이 생기지 않게).
        # nori(한국어 분석)는 검색 ES 에만 — 시작 때 없으면 설치(인터넷 필요). 로그 ES 는 기본 명령
        if [ "$c" = es-search ]; then
          auto_idx="+.*,-*"
          es_cmd='    command: ["sh", "-c", "bin/elasticsearch-plugin list | grep -q analysis-nori || bin/elasticsearch-plugin install --batch analysis-nori; exec /usr/local/bin/docker-entrypoint.sh eswrapper"]'
        else
          auto_idx="+pmart-logs-*,+.*,-*"; es_cmd=""
        fi
        cat <<EOF
  $c:
    <<: *common
    image: docker.elastic.co/elasticsearch/elasticsearch:8.18.0
${es_cmd:+$es_cmd
}    environment:
      discovery.type: single-node
      cluster.name: pmart-${c#es-}
      xpack.security.enabled: "false"
      action.auto_create_index: "$auto_idx"
      ES_JAVA_OPTS: -Xms768m -Xmx768m
    ports: ["9200:9200"]
    networks: { default: { ipv4_address: $ip_addr } }
    volumes:
      # 분리 전 ES 의 esdata 와 다른 이름 — 옛 볼륨은 롤백용으로 남는다
      - es${c#es-}:/usr/share/elasticsearch/data
    mem_limit: 1536m
    healthcheck:
      test: ["CMD-SHELL", "curl -s -o /dev/null http://127.0.0.1:9200/"]
      interval: 5s
      timeout: 3s
      retries: 120

EOF
        ;;
      prometheus)
        # 지표 수집(ADR-0030). 대상은 아래에서 monitoring/prometheus.yml 로 생성.
        # 보존: 7일 또는 2GB 중 먼저 닿는 쪽(디스크 상한 — 2026-10-25 pc3 Connect 로그 142GB 사고 교훈)
        cat <<EOF
  prometheus:
    <<: *common
    image: prom/prometheus:v3.5.0
    command: ["--config.file=/etc/prometheus/prometheus.yml", "--storage.tsdb.retention.time=7d", "--storage.tsdb.retention.size=2GB", "--web.enable-lifecycle"]
    ports: ["9090:9090"]
    networks: { default: { ipv4_address: $ip_addr } }
    volumes:
      - ./monitoring/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - ./monitoring/alert-rules.yml:/etc/prometheus/alert-rules.yml:ro
      - ./monitoring/eureka-password:/etc/prometheus/secrets/eureka-password:ro
      - promdata:/prometheus
    mem_limit: 1g

EOF
        ;;
      alertmanager)
        cat <<EOF
  alertmanager:
    <<: *common
    image: prom/alertmanager:v0.28.1
    command: ["--config.file=/etc/alertmanager/alertmanager.yml"]
    ports: ["9093:9093"]
    networks: { default: { ipv4_address: $ip_addr } }
    volumes:
      - ./monitoring/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro
      # Slack 웹훅 주소(.secrets/slack-alert-webhook) — 없으면 빈 폴더, 경보 받는 곳은 none
      - ./monitoring/alertmanager-secrets:/etc/alertmanager/secrets:ro

EOF
        ;;
      grafana)
        cat <<EOF
  grafana:
    <<: *common
    image: grafana/grafana:12.0.2
    ports: ["3000:3000"]
    networks: { default: { ipv4_address: $ip_addr } }
    environment:
      GF_SECURITY_ADMIN_USER: admin
      GF_SECURITY_ADMIN_PASSWORD: \${PMART_GRAFANA_ADMIN_PASSWORD}
      GF_USERS_ALLOW_SIGN_UP: "false"
      GF_ANALYTICS_REPORTING_ENABLED: "false"
      GF_DEFAULT_LOCALE: ko-KR
    volumes:
      - ./monitoring/grafana/provisioning:/etc/grafana/provisioning:ro
      - ./monitoring/grafana/dashboards:/var/lib/grafana/dashboards:ro
      - grafanadata:/var/lib/grafana

EOF
        ;;
      kibana)
        # 로그 화면(ADR-0031, OpenSearch Dashboards 대체)
        cat <<EOF
  kibana:
    <<: *common
    image: docker.elastic.co/kibana/kibana:8.18.0
    ports: ["5601:5601"]
    networks: { default: { ipv4_address: $ip_addr } }
    environment:
      ELASTICSEARCH_HOSTS: '["http://es-logs:9200"]'
      TELEMETRY_OPTIN: "false"
    mem_limit: 1g

EOF
        ;;
      logstash-search|logstash-logs)
        # ADR-0038: logstash-search = 검색 색인(도메인 이벤트 Kafka → es-search, search-service 소유 파이프라인),
        #           logstash-logs = 로그(Filebeat 5044 → es-logs). 디스크: 영구 큐 256MB·DLQ 64MB(각각)
        k="${c#logstash-}"
        echo "  $c:"
        echo "    <<: *common"
        echo "    image: docker.elastic.co/logstash/logstash:8.18.0"
        [ "$k" = logs ] && echo '    ports: ["5044:5044"]'
        cat <<EOF
    networks: { default: { ipv4_address: $ip_addr } }
    environment:
      PMART_ES_URL: http://es-$k:9200
      LS_JAVA_OPTS: -Xms512m -Xmx512m
EOF
        if [ "$k" = search ]; then
          echo "      PMART_KAFKA_BOOTSTRAP: $KAFKA_BOOTSTRAP"
          echo "      PMART_CONTENT_BASE_URL: http://product-content-service:$(port_of product-content-service)"
          # 검색 색인 대상 색인·소비 그룹·DLT(elastic/search-index.env — 재색인 때만 바꾼다, elastic/REINDEX.md)
          grep -E '^PMART_SEARCH_[A-Z_]+=' "$LAN/elastic/search-index.env" | sed 's/^\([^=]*\)=\(.*\)$/      \1: "\2"/'
        fi
        cat <<EOF
    volumes:
      - ./elastic/logstash/logstash.yml:/usr/share/logstash/config/logstash.yml:ro
      - ./elastic/logstash/pipelines-$k.yml:/usr/share/logstash/config/pipelines.yml:ro
      - ./elastic/logstash/pipeline:/usr/share/logstash/pipeline:ro
      # 분리 전 logstash 의 lsdata 와 다른 이름(영구 큐·DLQ) — 옛 볼륨은 롤백용으로 남는다
      - ls$k:/usr/share/logstash/data
    mem_limit: 1536m
    depends_on:
      es-init-$k: { condition: service_completed_successfully }

EOF
        ;;
      log-shipper)
        # PC 마다 하나: Docker Desktop VM 안의 컨테이너 로그 파일 → Logstash(ADR-0031). 앱 로그만·초당 500건·메모리 큐
        cat <<EOF
  log-shipper:
    <<: *common
    image: docker.elastic.co/beats/filebeat:8.18.0
    user: root
    command: ["filebeat", "-e", "--strict.perms=false"]
    networks: { default: { ipv4_address: $ip_addr } }
    environment:
      PMART_PC: $pc
      PMART_LOGSTASH_HOST: logstash-logs
    volumes:
      - /var/lib/docker/containers:/var/lib/docker/containers:ro
      - ./elastic/filebeat.yml:/usr/share/filebeat/filebeat.yml:ro
      - fbdata:/usr/share/filebeat/data
    mem_limit: 256m

EOF
        ;;
      host-disk)
        # Windows C: 여유 공간(9p drvfs 마운트, 읽기 전용) → node_filesystem_avail_bytes{mountpoint="/host/windows-c"}
        # 토픽 지표 노드(kafka-1)가 있는 PC 만: watch.sh 가 쓰는 metrics/kafka_topics.prom(토픽 크기·오프셋·보관 설정, ADR-0034)도 내보낸다(textfile)
        textfile_args=""; textfile_vol=""
        if [[ " ${COMPS[$pc]} " == *" $KAFKA_METRICS_NODE "* ]]; then
          textfile_args=', "--collector.textfile", "--collector.textfile.directory=/textfile"'
          textfile_vol=$'\n      - ./metrics:/textfile:ro'
        fi
        cat <<EOF
  host-disk:
    <<: *common
    image: prom/node-exporter:v1.9.1
    command: ["--collector.disable-defaults", "--collector.filesystem", "--collector.filesystem.mount-points-exclude=^/(dev|proc|sys|etc)(\$\$|/)"$textfile_args]
    ports: ["9100:9100"]
    networks: { default: { ipv4_address: $ip_addr } }
    volumes:
      - /run/desktop/mnt/host/c:/host/windows-c:ro$textfile_vol
    mem_limit: 64m

EOF
        ;;
      es-init-search|es-init-logs)
        # 한 번 돌고 끝난다. 배포 때마다 다시 돌아도 같은 결과. 같은 종류의 Logstash 는 이것 뒤에 뜬다(ADR-0038)
        #   search: 상품 검색 색인·별칭 / logs: 로그 ILM·색인 틀·Kibana 데이터 뷰
        k="${c#es-init-}"
        cat <<EOF
  $c:
    image: curlimages/curl:8.14.1
    restart: "no"
    logging: { driver: json-file, options: { max-size: "1m", max-file: "1" } }
    entrypoint: ["sh", "/init/es-init.sh"]
    networks: { default: { ipv4_address: $ip_addr } }
    environment:
      PMART_ES_INIT: $k
      PMART_ES_URL: http://es-$k:9200
    volumes:
      - ./elastic/es-init.sh:/init/es-init.sh:ro
EOF
        [ "$k" = search ] && echo "      - ./elastic/indices:/init/indices:ro"
        cat <<EOF
    depends_on:
      es-$k: { condition: service_healthy }

EOF
        ;;
      log-janitor)
        # 로그 색인 크기 상한(색인 1GB·전체 4GB, 5분마다)
        cat <<EOF
  log-janitor:
    <<: *common
    image: curlimages/curl:8.14.1
    entrypoint: ["sh", "-c", "cp /init/log-janitor.sh /tmp/j.sh && exec sh /tmp/j.sh"] # 호스트 파일을 잠그지 않게 복사해서 실행(위 connector-registrar 와 같음)
    networks: { default: { ipv4_address: $ip_addr } }
    environment:
      PMART_ES_URL: http://es-logs:9200
    volumes:
      - ./elastic/log-janitor.sh:/init/log-janitor.sh:ro
    mem_limit: 32m

EOF
        ;;
      es-exporter-search|es-exporter-logs)
        # Elasticsearch 지표 → Prometheus(클러스터 상태·노드 JVM·디스크). 색인별 지표는 끔(시계열 수). ES 마다 하나(ADR-0038)
        k="${c#es-exporter-}"
        cat <<EOF
  $c:
    <<: *common
    image: quay.io/prometheuscommunity/elasticsearch-exporter:v1.10.0
    command: ["--es.uri=http://es-$k:9200", "--es.timeout=5s"]
    ports: ["9114:9114"]
    networks: { default: { ipv4_address: $ip_addr } }
    mem_limit: 64m
    depends_on: [es-$k]

EOF
        ;;
      kafka-exporter)
        # Kafka 클러스터 지표 → Prometheus(ADR-0037). 브로커에서 메타데이터·오프셋을 읽기만 한다.
        # kafka.version: 기본 2.0.0 이면 옛 프로토콜 판으로 요청한다(Kafka 4 는 2.1 미만 판을 지웠다) — 3.9.0 으로 올린다.
        #   v1.10.0 이 4.x 문자열을 받는지 확인하지 못했다(2026-11-05) — 전환 뒤 /metrics 의 kafka_brokers 로 확인한다
        # 컨트롤러 지표는 없다 — 과반을 잃으면(2노드 정지) 브로커 수·수집 실패로 드러난다(alert-rules.yml)
        echo "  kafka-exporter:"
        echo "    <<: *common"
        echo "    image: danielqsj/kafka-exporter:v1.10.0"
        printf '    command: ['
        for kn in $KAFKA_NODES; do printf '"--kafka.server=%s:9092", ' "$kn"; done
        echo '"--kafka.version=3.9.0", "--web.listen-address=:9308"]'
        cat <<EOF
    ports: ["9308:9308"]
    networks: { default: { ipv4_address: $ip_addr } }
    mem_limit: 128m

EOF
        ;;
      config-relay)
        # GitHub 웹훅 중계(ADR-0033): smee.io 채널에 먼저 연결해 두고(나가는 연결만) 받은 push 웹훅을 config-server /monitor 로 넘긴다.
        # 서명(X-Hub-Signature-256)은 config-server 가 검사한다. 채널 주소는 .env 의 PMART_SMEE_URL(.secrets/smee-url)
        cat <<EOF
  config-relay:
    <<: *common
    image: node:22.23.4-alpine
    user: node
    command: ["sh", "-c", "exec npx -y smee-client@5.0.0 --url \"\$\$PMART_SMEE_URL\" --target http://config-server:$(port_of config-server)/monitor"]
    environment:
      PMART_SMEE_URL: \${PMART_SMEE_URL}
    networks: { default: { ipv4_address: $ip_addr } }
    mem_limit: 128m
    depends_on: [config-server]

EOF
        ;;
      zipkin)
        cat <<EOF
  zipkin:
    <<: *common
    image: openzipkin/zipkin:3
    ports: ["9411:9411"]
    networks: { default: { ipv4_address: $ip_addr } }
    # 메모리 저장소. 기본(힙 160m·span 50만)은 힙이 차서 OOM 재시작을 반복했다(2026-11-05 확인: 약 20시간 113번).
    # span 상한을 줄이고 힙을 조금 늘린다. 대가: 오래된 추적은 먼저 지워진다. mem_limit 으로 다른 컨테이너를 보호
    mem_limit: 512m
    environment:
      JAVA_OPTS: "-Xms256m -Xmx256m -XX:+ExitOnOutOfMemoryError"
      MEM_MAX_SPANS: "50000"

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
          # 서버 자신의 비밀은 configtree 디렉터리(파일 이름 = 속성 이름). 저장소 주소는 application.yml 기본값(GitHub)
          echo "      PMART_ZIPKIN_ENDPOINT: http://zipkin:9411/api/v2/spans"
          # /monitor 가 받은 웹훅을 Bus(Kafka springCloudBus)로 알린다(ADR-0033). 서버는 설정 저장소를 읽지 않아 환경변수로 켠다
          echo "      SPRING_CLOUD_BUS_ENABLED: \"true\""
          echo "      SPRING_KAFKA_BOOTSTRAP_SERVERS: $KAFKA_BOOTSTRAP"
          # GitHub fetch 는 15초에 한 번만(기본 0 = 요청마다). 앱 35개가 한꺼번에 뜨면 요청마다 fetch(약 10초, 직렬)가 밀려
          # 30초 시간 초과 → 재시작 → 다시 요청이 반복됐다(2026-11-04 배포). 대가: push 뒤 최대 15초는 이전 설정을 줄 수 있다
          echo "      SPRING_CLOUD_CONFIG_SERVER_GIT_REFRESH_RATE: \"15\""
          echo "    volumes:"
          echo "      - ./config-server-secrets:/run/secrets/config-server:ro"
          echo
          continue
        fi
        # 설정은 config-server 에서(ADR-0027·0029). 받지 못하면 기동하지 않는다(fail-fast → restart 로 다시 시도)
        echo "      SPRING_CLOUD_CONFIG_ENABLED: \"true\""
        echo "      SPRING_CLOUD_CONFIG_URI: http://config-server:$(port_of config-server)"
        echo "      SPRING_CLOUD_CONFIG_FAIL_FAST: \"true\""
        echo "      SPRING_CLOUD_CONFIG_USERNAME: pmart"
        echo "      SPRING_CLOUD_CONFIG_PASSWORD: \${PMART_CONFIG_PASSWORD}"
        # 서비스 설정은 설정 저장소의 <앱>.yml · <앱>-lan.yml (lan 프로필). 없으면 뜰 수 없다
        migrated "$c" || { echo "설정 저장소에 $c-lan.yml 이 없음($CFG_CLONE)" >&2; exit 1; }
        echo "      SPRING_PROFILES_ACTIVE: lan"
        # Eureka(ADR-0032): 이 PC 의 LAN IP 로 등록한다(컨테이너 IP 는 다른 PC 에서 닿지 않는다). 설정은 application-lan.yml
        echo "      PMART_HOST_IP: ${IP[$pc]}"
        echo "      PMART_EUREKA_PASSWORD: \${PMART_EUREKA_PASSWORD}"
        # Bus 소비 그룹 이름 고정(bus-<앱>-<PC>, application-lan.yml) — 재시작마다 anonymous.* 그룹이 늘지 않게(2026-11-04)
        echo "      PMART_BUS_INSTANCE: $pc"
        echo
        # 배치 대조: DB 를 쓰는 앱의 lan DB 주소가 이 PC 의 postgres 인가(placement.conf 를 바꾸면 저장소도 바꿔야 한다)
        if needs_db "$c"; then
          [ -n "${WHERE[postgres-$pc]:-}" ] || { echo "$c 는 DB 가 필요한데 $pc 에 postgres 가 없음" >&2; exit 1; }
          want="jdbc:postgresql://postgres-$pc:15432/$(db_of "$c")"
          grep -qF "$want" "$CFG_CLONE/$c-lan.yml" \
            || { echo "배치와 설정 저장소가 다름: $c 는 $pc 인데 $c-lan.yml 의 DB 주소가 $want 가 아니다" >&2; exit 1; }
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
    [[ "$comps" == *" kafka-"[123]" "* ]] && vols+="  kafkacluster:"$'\n'
    [[ "$comps" == *" s3 "* ]] && vols+="  s3data:"$'\n'
    for k in search logs; do
      [[ "$comps" == *" es-$k "* ]] && vols+="  es$k:"$'\n'
      [[ "$comps" == *" logstash-$k "* ]] && vols+="  ls$k:"$'\n'
    done
    [[ " ${COMPS[$pc]} " == *" prometheus "* ]] && vols+="  promdata:"$'\n'
    [[ " ${COMPS[$pc]} " == *" grafana "* ]] && vols+="  grafanadata:"$'\n'
    [[ " ${COMPS[$pc]} " == *" log-shipper "* ]] && vols+="  fbdata:"$'\n'
    [ -n "$vols" ] && printf 'volumes:\n%s' "$vols"
  } > "$dir/compose.yml"

  # Elastic 구성 파일(ADR-0031): 이 PC 에 있는 것만. 검색 색인 파이프라인·색인 정의는 search-service 소유(정본) — 복사한다
  allc=" ${COMPS[$pc]} "
  if [[ "$allc" =~ \ (logstash-search|logstash-logs|log-shipper|es-init-search|es-init-logs|log-janitor)\  ]]; then
    mkdir -p "$dir/elastic"
    [[ "$allc" == *" log-shipper "* ]] && cp "$ELASTIC/filebeat.yml" "$dir/elastic/"
    # Logstash(ADR-0038): 종류마다 파이프라인 목록(pipelines-<종류>.yml)과 그 파이프라인 파일만
    if [[ "$allc" == *" logstash-search "* ]]; then
      mkdir -p "$dir/elastic/logstash/pipeline"
      cp "$ELASTIC/logstash/logstash.yml" "$ELASTIC/logstash/pipelines-search.yml" "$dir/elastic/logstash/"
      cp "$SEARCH_SVC/src/main/logstash/search-index.conf" "$dir/elastic/logstash/pipeline/"
    fi
    if [[ "$allc" == *" logstash-logs "* ]]; then
      mkdir -p "$dir/elastic/logstash/pipeline"
      cp "$ELASTIC/logstash/logstash.yml" "$ELASTIC/logstash/pipelines-logs.yml" "$dir/elastic/logstash/"
      cp "$ELASTIC/logstash/logs.conf" "$dir/elastic/logstash/pipeline/"
    fi
    if [[ "$allc" =~ \ es-init-(search|logs)\  ]]; then
      tr -d '\r' < "$ELASTIC/es-init.sh" > "$dir/elastic/es-init.sh"
    fi
    if [[ "$allc" == *" es-init-search "* ]]; then
      mkdir -p "$dir/elastic/indices" && cp "$SEARCH_SVC"/src/main/resources/elasticsearch/*.json "$dir/elastic/indices/"
    fi
    [[ "$allc" == *" log-janitor "* ]] && tr -d '\r' < "$ELASTIC/log-janitor.sh" > "$dir/elastic/log-janitor.sh"
  fi

  # Kafka 토픽 준비(ADR-0034·0037): lan.sh deploy 가 세 노드를 (재생성 없이) 먼저 올린 뒤 한 노드 컨테이너 안에서 실행한다.
  # metrics/ = watch.sh 가 쓰는 토픽 크기 지표(textfile) — 지표 노드(kafka-1) PC 의 host-disk 가 읽는다
  if [[ " ${COMPS[$pc]} " == *" kafka-"[123]" "* ]]; then
    mkdir -p "$dir/kafka"
    tr -d '\r' < "$LAN/kafka/topics-init.sh" > "$dir/kafka/topics-init.sh"
  fi
  if [[ " ${COMPS[$pc]} " == *" $KAFKA_METRICS_NODE "* ]]; then
    mkdir -p "$dir/metrics"
    echo "watch.sh(PC 1)가 kafka_topics.prom 을 쓴다. host-disk(node-exporter textfile)가 읽는다 — ADR-0034" > "$dir/metrics/README.txt"
  fi

  # 관측 구성 파일(ADR-0030): 이 PC 에 있는 것만
  allc=" ${COMPS[$pc]} "
  if [[ "$allc" =~ \ (prometheus|alertmanager|grafana)\  ]]; then
    mkdir -p "$dir/monitoring"
    [[ "$allc" == *" prometheus "* ]] && cp "$MON/alert-rules.yml" "$dir/monitoring/"
    if [[ "$allc" == *" alertmanager "* ]]; then
      cp "$MON/alertmanager.yml" "$dir/monitoring/"
      mkdir -p "$dir/monitoring/alertmanager-secrets"
      # 경보 받는 곳(Slack): 웹훅 주소 파일이 있을 때만. 없으면 none(어디에도 보내지 않음)
      if [ -s "$SECRETS/slack-alert-webhook" ]; then
        secret slack-alert-webhook > "$dir/monitoring/alertmanager-secrets/slack-alert-webhook"
      else
        sed -i 's/^  receiver: slack$/  receiver: none/' "$dir/monitoring/alertmanager.yml"
      fi
    fi
    [[ "$allc" == *" grafana "* ]] && cp -r "$MON/grafana" "$dir/monitoring/"
    if [[ "$allc" == *" prometheus "* ]]; then
      {
        echo "# 생성 파일(deploy/lan/render.sh). ADR-0030. 앱 수집 대상은 Eureka 등록부에서 찾는다(eureka_sd, 2026-11-05)."
        echo "# 인프라(host-disk·es-exporter)와 Eureka 에 등록하지 않는 eureka-server 만 고정 목록"
        echo "global: { scrape_interval: 15s, evaluation_interval: 15s }"
        echo "rule_files: [/etc/prometheus/alert-rules.yml]"
        if [ -n "${WHERE[alertmanager]:-}" ]; then
          echo "alerting: { alertmanagers: [ { static_configs: [ { targets: [\"${IP[${WHERE[alertmanager]}]}:9093\"] } ] } ] }"
        fi
        echo "scrape_configs:"
        echo "  - job_name: prometheus"
        echo "    static_configs: [ { targets: [\"localhost:9090\"] } ]"
        echo "  - job_name: host-disk"
        echo "    static_configs:"
        for p in "${PCS[@]}"; do echo "      - { targets: [\"${IP[$p]}:9100\"], labels: { pc: $p } }"; done
        # ES 마다 exporter 하나(ADR-0038) — es 레이블(search·logs)로 구분한다(경보·대시보드). exporter 가 붙이는 cluster 레이블(ES cluster.name)과 겹치지 않게 es
        if [ -n "${WHERE[es-exporter-search]:-}${WHERE[es-exporter-logs]:-}" ]; then
          echo "  - job_name: elasticsearch"
          echo "    static_configs:"
          for k in search logs; do
            [ -n "${WHERE[es-exporter-$k]:-}" ] && echo "      - { targets: [\"${IP[${WHERE[es-exporter-$k]}]}:9114\"], labels: { pc: ${WHERE[es-exporter-$k]}, es: $k } }"
          done
        fi
        # Kafka 클러스터 지표(ADR-0037): kafka-exporter(브로커 수·파티션 리더·ISR·소비 그룹 지연)
        if [ -n "${WHERE[kafka-exporter]:-}" ]; then
          echo "  - job_name: kafka"
          echo "    static_configs: [ { targets: [\"${IP[${WHERE[kafka-exporter]}]}:9308\"], labels: { pc: ${WHERE[kafka-exporter]} } } ]"
        fi
        # eureka-server 는 자기 자신을 등록하지 않는다(register-with-eureka: false) — 고정
        if [ -n "${WHERE[eureka-server]:-}" ]; then
          echo "  - job_name: eureka-server"
          echo "    metrics_path: /actuator/prometheus"
          echo "    static_configs: [ { targets: [\"${IP[${WHERE[eureka-server]}]}:$(port_of eureka-server)\"], labels: { pc: ${WHERE[eureka-server]} } } ]"
          # 그 밖의 앱: Eureka 등록부(ADR-0032)에서 찾는다. 앱은 PC 의 LAN IP + 공개 포트로 등록하고(prefer-ip-address),
          # 지표 포트는 메타데이터 management.port. job = 앱 이름 소문자(전처럼 search-service 등 — 대시보드·경보 그대로).
          # config-server 는 Eureka 에 등록하지 않는다(eureka.client.enabled: false) — 지표도 열지 않았으므로 수집 대상 아님.
          # Eureka 를 못 읽으면 Prometheus 는 마지막으로 찾은 대상을 유지한다. 등록부에서 사라진 앱은 AppMissingFromDiscovery 가 잡는다.
          echo "  - job_name: eureka-apps"
          echo "    metrics_path: /actuator/prometheus"
          echo "    eureka_sd_configs:"
          echo "      - server: http://${IP[${WHERE[eureka-server]}]}:$(port_of eureka-server)/eureka"
          echo "        refresh_interval: 30s"
          echo "        basic_auth: { username: pmart, password_file: /etc/prometheus/secrets/eureka-password }"
          echo "    relabel_configs:"
          echo "      - { source_labels: [__meta_eureka_app_instance_ip_addr, __meta_eureka_app_instance_metadata_management_port], regex: '(.+);(.+)', target_label: __address__, replacement: '\$1:\$2' }"
          # instance 는 전처럼 IP:포트(eureka_sd 기본값은 인스턴스 ID — 바꾸면 기존 시계열과 끊긴다)
          echo "      - { source_labels: [__address__], target_label: instance }"
          echo "      - { source_labels: [__meta_eureka_app_name], target_label: job, action: lowercase }"
          # 상태(UP·DOWN 등)는 태그로 붙이지 않는다 — 값이 바뀌면 다른 시계열이 되어 그래프·경보가 끊긴다
          echo "      - { target_label: discovery, replacement: eureka }"
          # IP → PC 이름(PC 목록 표, 서비스 배치와 무관). 정규식의 점은 글자 그대로
          for p in "${PCS[@]}"; do
            ipre="$(printf '%s' "${IP[$p]}" | sed 's/\./\\./g')"
            echo "      - { source_labels: [__meta_eureka_app_instance_ip_addr], regex: '$ipre', target_label: pc, replacement: $p }"
          done
        fi
      } > "$dir/monitoring/prometheus.yml"
      # Eureka 조회 비밀번호(basic_auth password_file). build/ 에만 쓴다(git 제외, 설정 파일에 넣지 않는다)
      secret eureka-password > "$dir/monitoring/eureka-password"
    fi
  fi

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

  # .env: Spring 이 아닌 컨테이너(postgres·connector-registrar·s3)의 비밀 + config-server·eureka-server 접속 비밀번호만.
  # Spring 앱의 설정·비밀은 config-server 에서 받는다(ADR-0029, 설정 저장소).
  {
    echo "PMART_DB_PASSWORD=$DB_PASSWORD"
    echo "PMART_CONFIG_PASSWORD=$(secret config-password)"
    echo "PMART_EUREKA_PASSWORD=$(secret eureka-password)"
    [[ "$comps" == *" config-relay "* ]] && echo "PMART_SMEE_URL=$(secret smee-url)"
    [[ "$comps" == *" grafana "* ]] && echo "PMART_GRAFANA_ADMIN_PASSWORD=$(secret grafana-admin-password)"
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
    secret config-webhook-secret > "$d/spring.cloud.config.server.monitor.github.webhook-secret" # GitHub 웹훅 서명 비밀(ADR-0033)
    tr -d '\r' < "$SECRETS/config-deploy-key" > "$d/spring.cloud.config.server.git.private-key"
    hk="$(sed -e 's/#.*//' "$LAN/config-server/github-host-key.conf" | grep -v '^[[:space:]]*$' | head -1)"
    printf '%s' "${hk%% *}" > "$d/spring.cloud.config.server.git.host-key-algorithm"
    printf '%s' "${hk#* }" > "$d/spring.cloud.config.server.git.host-key"
  fi

  echo "$pc ${IP[$pc]} ${SSH[$pc]}" > "$dir/target"
  echo "rendered $dir ($(echo ${COMPS[$pc]} | wc -w) components)"
done
