# ADR-0012 이미지 저장소는 Docker Hub 개인 계정의 공개 저장소로 한다

- 상태: 제안 (2026-10-24)
- 관련: `docs/architecture/tech-stack.md`(이전 "Private Docker Registry"), `.claude/rules/ci-cd.md`, `build-logic/src/main/kotlin/pmart.container-image.gradle.kts`

## 배경
기술 스택은 사설 레지스트리를 전제했지만, 개발 환경(같은 LAN의 PC 3대)에서 사설 레지스트리나 유료 비공개 저장소를 운영하지 않기로 했다(2026-10-24 사용자 결정). 이미지 이름 규칙 `{registry}/pmart/{service}`는 세 단계 경로라서, `namespace/repository` 두 단계만 허용하는 Docker Hub에서는 push할 수 없다.

## 결정
1. 이미지는 Docker Hub **개인 계정의 공개 저장소**에 올린다. 서비스마다 저장소 하나를 쓴다.
2. 이미지 이름은 `{registry}/pmart-{service}:{git-sha}`로 바꾼다. Docker Hub에서는 `-PcontainerRegistry=docker.io/<계정>` → `docker.io/<계정>/pmart-<service>:<git-sha>`.
3. 태그·배포 원칙은 그대로 둔다. 태그는 git SHA 하나, `latest` 없음, 배포는 push 결과 digest.
4. 인증은 계정 비밀번호 대신 Docker Hub 액세스 토큰을 쓴다. push용 `pmart-ci-push`는 Read & Write(삭제 없음), 실행 PC용 `pmart-runtime-pull`은 Public Repo Read-only. 비공개 저장소로 바꾸면 pull 토큰을 Read-only로 다시 만든다. 주입 경로는 지금처럼 `REGISTRY_USERNAME`/`REGISTRY_PASSWORD` 환경변수다.
5. 실행 PC는 공개 이미지라도 **로그인한 상태로 pull**한다. 같은 공인 IP를 쓰는 PC들이 익명 pull 횟수 제한을 함께 소진하기 때문이다.

## 대안
- 사설 레지스트리(LAN 컨테이너): 운영 부담, `insecure-registries` 또는 인증서 배포가 필요하다. 채택하지 않음.
- Docker Hub 유료 플랜 비공개 저장소: 비용이 든다. 채택하지 않음.
- 비공개 저장소 1개에 모든 서비스를 넣고 태그로 구분(`<계정>/pmart:<service>-<sha>`): 비용 없이 비공개를 유지할 수 있다. 다만 태그 규칙이 바뀌고 정리가 번거롭다. 공개를 받아들이기로 해서 채택하지 않음.

## 결과
- 이미지는 누구나 pull할 수 있다. 이미지 안의 클래스(업무 로직)와 리소스 파일이 공개된다.
  - 운영 설정값은 기본값 없는 환경변수(`${PMART_...}`)로만 받는다(기존 규칙).
  - `application-local.yml`의 로컬 고정값(DB 비밀번호 `pmart-local`, 서비스 secret 해시)도 이미지에 들어간다. local 프로파일은 개발자 PC의 docker compose 안에서만 쓰고, 공유 환경에서는 쓰지 않는다.
- `insecure-registries`·레지스트리 컨테이너·인증서 배포가 필요 없다.
- Windows 실행 PC에 SSH 키 인증으로 접속한 세션은 Windows 자격 증명 관리자에 접근하지 못한다(`docker login` → "A specified logon session does not exist", 2026-10-24 PC 2·3에서 확인). 원격 실행은 다음처럼 한다.
  - 전용 설정 폴더 `%USERPROFILE%\.docker-pmart`를 둔다. 권한은 해당 사용자·SYSTEM·Administrators만 갖는다.
  - Docker CLI가 자격 증명 도우미를 자동으로 찾지 못하게 Docker bin 폴더를 PATH에서 빼고, `docker.exe`를 전체 경로와 `--config <폴더>`로 실행한다. 그러면 토큰이 그 폴더의 `config.json`에 평문(base64)으로 저장된다.
  - 그래서 그 파일에는 **읽기 전용 토큰만** 둔다. push 토큰은 빌드하는 곳(PC 1·Jenkins)의 환경변수로만 쓴다.
- 로컬 기본값 `localhost:5000`은 그대로 두며, 이름만 `localhost:5000/pmart-<service>`로 바뀐다.
- Kubernetes로 옮길 때 이미지 경로는 Helm 값 `image.repository`로만 바꾼다. 비공개 저장소로 전환하면 이 ADR을 새 ADR로 대체한다.
