# Helm (deploy/helm)

Kubernetes Staging/Production 배포용(ci-cd.md CD 단계). 서비스마다 같은 차트 `pmart-service` 를 쓰고 값만 다르다.

| 경로 | 내용 |
|---|---|
| `pmart-service/` | 공통 차트: Deployment, Service(이름 = 서비스명 → Service DNS), PDB. 선택: Ingress, HPA, ServiceAccount |
| `values/common.yaml` | 모든 서비스 공통 환경변수(Kafka·JWKS·Zipkin·Redis 주소) |
| `values/<service>.yaml` | 서비스별(이름·포트·이미지 저장소·필요 Secret). `gen-values.sh` 가 `application.yml` 에서 생성 — 직접 고치지 않는다 |

## 배포
```bash
helm upgrade --install product-service deploy/helm/pmart-service \
  -f deploy/helm/values/common.yaml -f deploy/helm/values/product-service.yaml \
  -f <환경별 values> --set image.digest=sha256:...   # jib push 결과(build/jib-image.digest)
```
- 이미지는 **digest 로만** 지정한다. 비었거나 `sha256:` 형식이 아니면 렌더링이 실패한다.
- 기동 확인은 `/actuator/health/readiness`(startup·liveness·readiness probe 설정됨).

## Secret (차트가 만들지 않는다)
`secretEnvFrom` 의 이름을 가진 Secret 의 모든 키가 환경변수로 들어간다. Secret Manager 연동 방식은 플랫폼 결정 `[팀 확인 필요]`.

| Secret | 키 | 대상 |
|---|---|---|
| `pmart-<service>-db` | `PMART_DB_URL`, `PMART_DB_USERNAME`, `PMART_DB_PASSWORD` | DB 를 쓰는 서비스 |
| `pmart-auth-signing` | `PMART_AUTH_SIGNING_KEYS_0_KID`, `PMART_AUTH_SIGNING_KEYS_0_PRIVATEKEY` | auth-service |

## 기본 보안·운영 설정
- 비루트(uid 1000, eclipse-temurin 베이스의 ubuntu — ADR-0028), 읽기 전용 루트 파일시스템(`/tmp` 만 emptyDir), capability 전부 제거, seccomp RuntimeDefault, SA 토큰 미마운트
- 무중단 롤링(maxUnavailable 0), graceful shutdown + preStop sleep 5초(`sleep` 액션: Kubernetes 1.30+)
- 메모리 limit 768Mi(JVM `MaxRAMPercentage=75`), PDB minAvailable 1, replica 2

## 검증 (2026-10-24, 클러스터 없음)
- `helm lint --strict`, 32개 서비스 `helm template` → kubeconform `-strict` 96개 리소스 유효
- 같은 보안 조건(`--read-only --tmpfs /tmp --user 65532 --cap-drop ALL`)으로 이미지 기동 → readiness/liveness UP, graceful shutdown 확인
- 실제 클러스터 배포는 아직 하지 않았다.

## 정해야 할 것 `[팀 확인 필요]`
클러스터·네임스페이스, Ingress(호스트·TLS·IngressClass), Kafka·Redis·Zipkin·PostgreSQL 을 클러스터 안에 둘지 관리형으로 쓸지, Secret Manager 연동(External Secrets 등), 리소스 수치.
