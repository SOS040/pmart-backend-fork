#!/usr/bin/env bash
# 서비스별 Helm values 생성: deploy/helm/values/<service>.yaml
# 포트·필요 Secret 은 각 서비스 src/main/resources/application.yml 에서 읽는다(deploy/lan/render.sh 와 같은 규칙).
# 생성 파일은 커밋한다. 서비스·포트·필요 인프라가 바뀌면 다시 실행한다.
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=deploy/helm/values
REGISTRY="${PMART_IMAGE_REGISTRY:-docker.io/heeji4445}"

names=$(sed -n '/^val services = listOf/,/^)/p;/^val platformServices = listOf/,/^)/p' settings.gradle.kts | grep -oE '^\s+"[a-z-]+"' | tr -d ' "')
for s in $names; do
  dir=services/$s; [ -d "$dir" ] || dir=platform-services/$s
  yml=$dir/src/main/resources/application.yml
  port=$(awk '/^server:/{f=1;next} /^[^ ]/{f=0} f&&/^  port:/{print $2; exit}' "$yml")
  secrets=()
  grep -q '${PMART_DB_URL' "$yml" && secrets+=("pmart-$s-db")                 # PMART_DB_URL/USERNAME/PASSWORD
  [ "$s" = auth-service ] && secrets+=("pmart-auth-signing")                   # PMART_AUTH_SIGNING_KEYS_0_KID/PRIVATEKEY
  {
    echo "# 생성 파일(deploy/helm/gen-values.sh). 환경 공통 값은 common.yaml."
    echo "name: $s"
    echo "port: $port"
    echo "image:"
    echo "  repository: $REGISTRY/pmart-$s"
    if [ ${#secrets[@]} -gt 0 ]; then
      echo "secretEnvFrom:"
      for x in "${secrets[@]}"; do echo "  - $x"; done
    fi
    if [ "$s" = auth-service ]; then
      echo "env:"
      echo "  PMART_AUTH_ISSUER: http://auth-service:$port"
    fi
    if [ "$s" = api-gateway ]; then
      echo "# 외부 진입점. 호스트·TLS·IngressClass 는 플랫폼 결정 후 환경별 values 에서 켠다 [팀 확인 필요]."
      echo "ingress:"
      echo "  enabled: false"
    fi
  } > "$OUT/$s.yaml"
done
echo "generated $(echo $names | wc -w) files in $OUT"
