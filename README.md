# On-Premise Agent Module

**Target Version:** 0.1.0

배포 잡은 에이전트로 들어오고, 파이프라인이 로컬 Docker에 컨테이너를 띄운 뒤 프록시 upstream을 바꿉니다.

```mermaid
flowchart TD
  job["Deploy Job"] --> pipeline["OnPremPipeline"]
  pipeline --> runtime["Local Docker"]
  pipeline --> proxy["Loopback Proxy"]
```

# 배포 방식

## 전제 사항

배포 대상 애플리케이션의 계약(Contract)은 Mock Up Repository인 `lily-blog-sample`에 정의되어 있습니다. 컨테이너 포트, Readiness Probe, `APP_COLOR` 환경변수 등 애플리케이션이 요구하는 런타임 설정은 해당 저장소를 기준으로 합니다.

본 모듈은 사용자 머신에서 동작합니다. GitHub 저장소를 클론하고, 그 머신의 Docker로 이미지를 빌드한 뒤 컨테이너를 기동합니다. 이미지를 레지스트리에 올리지 않으며 Kubernetes 리소스를 만들지 않습니다.

클라우드 경로에서 빌드는 `lily-builder`가, 트래픽 전환은 `lily-cicd`가 담당합니다. 본 모듈은 같은 배포 약속을 클러스터 없이 수행합니다.

사용자에게 전달하는 주소는 플랫폼이 보유한 Cloudflare 존의 `https://{appName}.{zone}`입니다. TLS는 Cloudflare 엣지에서 종료됩니다. 에이전트에서 컨테이너까지의 구간은 `http://127.0.0.1`입니다.

에이전트 하나에는 공개 주소가 하나이고, 그 주소로 트래픽을 받는 앱도 하나입니다.

데이터베이스는 만들지 않습니다. 데이터베이스 준비는 클라우드 경로의 DB 모듈이 담당합니다.

---

## 개요

새 버전이 요청을 처리할 준비가 되기 전에 공개 주소가 그 버전을 가리키면 사용자 요청이 실패합니다. 따라서 새 컨테이너를 기동하는 동안에도 사용자는 기존 슬롯만 이용할 수 있어야 하며, 새 슬롯이 헬스 체크를 통과한 뒤에만 트래픽이 전환되어야 합니다.

이를 위해 본 모듈은 머신 안에서 Blue-Green을 유지합니다. `blue`와 `green` 두 개의 루프백 포트를 두고, 공개 주소가 가리키지 않는 포트에만 새 컨테이너를 띄웁니다. 그 포트가 Ready가 되면 로컬 프록시의 upstream만 바꿉니다. Cloudflare Tunnel은 온보딩 때 프록시에 한 번 연결하고, 배포마다 다시 열지 않습니다. 앱의 호스트이름과 인증서는 Ready 이후, upstream을 바꾸기 전에 Cloudflare API로 등록합니다.

### 목표

* 에이전트마다 `blue`와 `green` 두 개의 슬롯을 유지합니다.
* 새 버전은 공개 주소가 가리키지 않는 슬롯에 배포됩니다.
* 대상 슬롯이 Ready가 되기 전에는 프록시를 바꾸지 않습니다.
* 트래픽 전환은 프록시 upstream 변경만으로 수행합니다.
* 공개 주소는 `https://{appName}.{zone}`이며, 같은 앱을 다시 배포해도 바뀌지 않습니다.
* 배포에 실패하면 기존 슬롯이 계속 트래픽을 받습니다.

파이프라인은 아래 순서로 각 단계를 호출합니다.

```text
OnPremPipeline
  → Workspace.checkout
  → StackAnalyzer.plan
  → ContainerRuntime.build
  → ContainerRuntime.start
  → Readiness.await
  → PublicAddress.ensure
  → TrafficSwitch.route
  → ContainerRuntime.stop (previous)
```

슬롯 이름은 `blue`와 `green`입니다. 컨테이너 이름은 `{appName}-blue`, `{appName}-green`입니다.

---

## 설계 배경

### 프록시 upstream 전환을 선택한 이유

배포가 끝날 때마다 터널을 컨테이너 포트에 다시 열면, 공개 주소가 배포마다 바뀌거나 헬스 체크가 끝나기 전에 새 컨테이너를 가리키게 됩니다.

본 모듈은 입구와 전환을 나눕니다. Tunnel은 프록시 포트에만 연결되고, 프록시는 활성 슬롯의 루프백 포트로만 요청을 전달합니다. 외부 주소는 그대로 두고 upstream만 바꿉니다. 클라우드 경로에서 Ingress는 유지한 채 Service Selector만 바꾸는 구조와 같은 역할입니다.

프록시는 HTTP/1.1 리버스 프록시이며 요청마다 upstream을 정합니다. upstream이 0이면 503을 돌려줍니다. 첫 배포가 Ready가 되기 전에는 공개 주소로 들어온 요청이 앱에 도달하지 않습니다. 전환은 다음 요청부터 적용되고, 처리 중인 요청은 기존 슬롯에서 끝납니다. WebSocket(101) 이후는 바이트를 그대로 잇습니다.

### 플랫폼 존의 호스트이름에 TLS를 붙이는 이유

사용자에게 전달하는 주소는 HTTPS여야 합니다. 인증서를 에이전트 머신에서 발급하면 비밀키가 사용자 PC에 남습니다.

본 모듈은 Cloudflare API로 플랫폼 존에 호스트이름을 등록합니다. 주소는 `https://{appName}.{zone}`입니다. `proxied: true`인 CNAME이 Cloudflare 엣지에서 TLS를 종료합니다. 존의 활성 인증서 범위에 `*.{zone}` 또는 해당 호스트가 있으면 인증서를 추가로 주문하지 않습니다. 범위에 없으면 Advanced Certificate를 주문합니다.

터널 ingress의 origin은 `http://127.0.0.1:{proxyPort}`입니다. 재배포해도 호스트이름과 터널은 유지되고, 바뀌는 값은 프록시 upstream뿐입니다.

API 자격 증명이 없으면 이 호출을 하지 않습니다. 그때의 주소는 루프백 HTTP이거나 `trycloudflare.com`의 임시 HTTPS이며, 플랫폼 도메인이 아닙니다.

### 아웃바운드 WebSocket을 선택한 이유

에이전트는 사용자 네트워크 안에서 동작합니다. 잡을 인바운드로 받으려면 그 머신의 포트를 열어야 합니다. 터널은 그 포트를 열지 않으려고 둡니다.

에이전트는 기동할 때 컨트롤 플레인으로 WebSocket 하나를 엽니다. 잡, 단계 로그, 결과가 이 소켓을 탑니다. 소켓 주소가 비어 있으면 연결하지 않습니다. 이 경우에도 `POST /api/jobs`로 같은 파이프라인을 실행할 수 있습니다.

### Dockerfile을 잡과 에이전트가 나누어 만드는 이유

컨트롤 플레인이 저장소를 분석해 Dockerfile을 만들 수 있습니다. 그 본문이 잡에 있으면 에이전트는 그 내용을 사용합니다. 모델은 사용자 PC에서 호출하지 않으므로 API 키도 두지 않습니다.

잡에 Dockerfile이 없고 저장소에도 없으면, 에이전트가 빌드 파일을 보고 앱 컨테이너용 Dockerfile을 만듭니다. 저장소에 Dockerfile이 있으면 그 파일을 그대로 사용하고 덮어쓰지 않습니다.

우선순위는 다음과 같습니다.

1. 잡에 실린 Dockerfile
2. 저장소의 `Dockerfile`
3. Gradle, Maven, Node, Python 순으로 생성

`docker-compose.yml` 또는 `compose.yaml`만 있는 저장소는 배포하지 않습니다. 슬롯 전환은 컨테이너 하나를 단위로 해야 하기 때문입니다. 이 경우 앱 Dockerfile이 필요하다는 오류와 함께 배포를 중단합니다.

### 트래픽 흐름

사용자 트래픽과 배포 트래픽은 서로 독립적으로 동작합니다.

* 사용자는 Tunnel 또는 로컬 프록시 주소로 접근합니다.
* 컨트롤 플레인은 WebSocket으로 잡을 보냅니다.
* 에이전트는 Docker로 슬롯 컨테이너를 만들고, 헬스 체크는 프록시를 거치지 않고 그 컨테이너의 루프백 포트로 보냅니다.
* Active 슬롯이 `blue`인 동안 사용자 요청은 `127.0.0.1:18080`으로만 전달됩니다.
* 배포 대상인 `green`은 Ready가 될 때까지 프록시에 연결되지 않습니다.

```mermaid
flowchart LR

  user["사용자"]
  plane["컨트롤 플레인"]
  agent["Agent :8060"]
  tunnel["Cloudflare Tunnel"]
  proxy["Proxy :8099"]
  blue["blue :18080"]
  green["green :18081"]

  plane -->|"WebSocket Job"| agent
  agent -->|"docker"| blue
  agent -->|"docker"| green
  agent -->|"upstream"| proxy

  user -->|"공개 주소"| tunnel
  tunnel --> proxy
  user -->|"터널 없음"| proxy

  proxy -->|"active = blue"| blue
  proxy -.->|"선택되지 않음"| green
```

---

## 배포 절차

기본 슬롯 포트는 `blue = 18080`, `green = 18081`입니다. 프록시 포트는 `8099`입니다.

이번 배포의 대상 슬롯은 프록시가 현재 가리키는 포트로 결정합니다.

```text
upstream = proxy.upstreamPort

if upstream 이 blue 포트:
    target = green
else:
    target = blue

트래픽을 받는 컨테이너를 제외한 나머지 컨테이너를 정리

docker build
docker run (target, 127.0.0.1:targetPort)

wait health(120s)

hostname.ensure(app)
    ingress: https://{app}.{zone} → http://127.0.0.1:{proxyPort}
    CNAME {app}.{zone} → {tunnelId}.cfargotunnel.com  proxied
    certificate: 활성 인증서 범위에 없으면 Advanced Certificate 주문

proxy.upstream = target

이전 컨테이너 정지
```

첫 배포는 upstream이 0이므로 대상은 `blue`입니다. `blue`가 활성인 다음 배포의 대상은 `green`이고, `green`이 활성인 다음 배포의 대상은 `blue`입니다.

다른 앱의 잡도 같은 규칙을 따릅니다. 공개 주소가 가리키는 포트에는 새 앱을 띄우지 않습니다. Ready가 된 뒤에 프록시를 옮기고, 이어서 이전 앱의 컨테이너를 내립니다.

### 처리 순서

```mermaid
flowchart TD

  start["배포 잡"] --> checkout["git clone"]

  checkout -->|"실패"| stopCheckout["배포 중단"]
  checkout -->|"성공"| analyze["Dockerfile 결정"]

  analyze -->|"스택 없음"| stopAnalyze["배포 중단"]
  analyze -->|"성공"| build["로컬 이미지 빌드"]

  build --> run["비활성 슬롯 기동"]
  run --> wait["헬스 체크"]

  wait -->|"타임아웃"| drop["후보 컨테이너 삭제"]
  wait -->|"성공"| host["호스트이름·인증서"]

  host -->|"API 실패"| dropHost["후보 컨테이너 삭제"]
  host -->|"성공"| cut["프록시 upstream 변경"]

  cut -->|"실패"| dropCut["후보 컨테이너 삭제"]
  cut -->|"성공"| retire["이전 슬롯 정지"]

  retire --> done["공개 주소 유지"]
```

### 실패 처리 원칙

#### Ready 이전 실패

헬스 체크에 실패하면 후보 컨테이너를 삭제합니다.

이 시점에는 프록시 upstream이 바뀌지 않았으므로 사용자 트래픽은 계속 기존 슬롯으로 전달됩니다. 첫 배포에서 Ready 이전에는 공개 주소로 들어온 요청에 프록시가 503을 돌려줍니다.

체크아웃, Dockerfile 결정, 이미지 빌드에서 실패해도 프록시는 바꾸지 않습니다.

호스트이름 API가 실패해도 upstream은 아직 이전 슬롯을 가리킵니다. 후보 컨테이너를 삭제합니다.

#### upstream 변경 이후 실패

프록시 upstream이 바뀐 뒤에는 사용자 트래픽이 새 슬롯으로 전달됩니다.

이전 슬롯을 정지하다 오류가 나도 새 슬롯은 삭제하지 않습니다. 새 슬롯을 제거하면 이미 넘어간 트래픽이 바로 끊기기 때문입니다.

프록시 변경 자체가 실패하면 upstream은 아직 이전 슬롯을 가리키므로, 후보 컨테이너를 삭제합니다.

---

## Active Slot

Active Slot은 프록시 upstream이 가리키는 루프백 포트입니다.

| Active | Target | 후보 포트 |
| ------ | ------ | --------- |
| 없음   | blue   | 18080     |
| blue   | green  | 18081     |
| green  | blue   | 18080     |

컨테이너의 `APP_COLOR`는 Target Slot 이름입니다. `APP_VERSION`은 잡 id입니다. 요청의 `env`로는 이 두 값을 바꿀 수 없습니다.

`GET /version` 응답의 `color`가 Active Slot과 같으면 트래픽 전환이 끝난 상태입니다. 이 기준은 `lily-blog-sample`의 계약입니다.

이미지 태그는 `lily-onprem/{appName}:{jobId}`입니다. 태그에는 레지스트리 호스트가 없습니다.

---

## DeployJob

컨트롤 플레인과 로컬 API는 배포 한 건을 `DeployJob`으로 넘깁니다.

프로젝트 목록과 GitHub 연동 상태는 이 객체에 없습니다. 그 기록은 컨트롤 플레인이 보관하고, 에이전트는 잡만 실행합니다.

| 필드           | 설명                                              |
| ------------ | ----------------------------------------------- |
| `id`         | 잡 id. 비우면 에이전트가 만듭니다                            |
| `repoUrl`    | `https://github.com/{owner}/{repo}`            |
| `branch`     | 클론 브랜치. 기본값은 `main`                            |
| `token`      | private 저장소 토큰. 클론에만 쓰고 기록에는 남기지 않습니다          |
| `appName`    | 컨테이너 이름 접두사. 소문자로 시작하는 영숫자와 하이픈                 |
| `targetPort` | 컨테이너 포트. 기본값 8080                               |
| `healthPath` | 헬스 경로. 비우면 스택에 따라 에이전트가 정합니다                    |
| `rootDir`    | 저장소 안의 빌드 디렉터리                                  |
| `dockerfile` | 컨트롤 플레인이 만든 Dockerfile 본문. 비우면 저장소 파일 또는 생성 규칙을 사용 |
| `env`        | 컨테이너 환경변수. `APP_COLOR`, `APP_VERSION`은 거절합니다   |

`repoUrl`에 사용자 정보가 들어 있으면 거절합니다. 토큰은 `token` 필드로만 받습니다.

토큰은 `https://x-access-token:{token}@github.com/...` 형태의 클론 주소에만 들어갑니다. 상태 메시지와 예외 문자열에서는 제거합니다.

---

## 모듈별 책임

### Workspace

잡을 받은 뒤, 빌드 전에 실행됩니다.

* `git clone --depth 1`로 잡 id 디렉터리에 받습니다
* `rootDir` 값이 있으면 그 디렉터리를 빌드 컨텍스트로 사용합니다
* 클론에 실패하면 배포를 중단합니다

### Stack Analyzer

클론 이후에 실행됩니다.

* 잡의 Dockerfile, 저장소의 Dockerfile, 생성 규칙 순으로 결정합니다
* Gradle과 Maven으로 생성한 이미지의 기본 헬스 경로는 `/actuator/health/readiness`입니다
* Node와 Python으로 생성한 이미지의 기본 헬스 경로는 `/`입니다
* 저장소에 Dockerfile이 있거나 잡이 본문을 담은 경우, 경로를 비우면 `/actuator/health/readiness`를 사용합니다
* 결정에 실패하면 배포를 중단합니다

### Container Runtime

분석 이후에 실행됩니다.

* `docker build`로 로컬 태그를 만듭니다
* `docker run`은 `127.0.0.1:{slotPort}:{targetPort}`만 엽니다
* 지우려는 컨테이너가 없으면 슬롯이 이미 비어 있는 것으로 보고 계속 진행합니다
* Docker 데몬 오류는 배포 실패로 처리합니다

### Readiness

컨테이너를 기동한 뒤에 실행됩니다.

* 프록시를 거치지 않고 후보 슬롯의 루프백 포트에 HTTP GET을 보냅니다
* 2xx가 올 때까지 기다립니다. 기본 제한 시간은 120초입니다
* 시간이 초과되면 후보 컨테이너를 삭제하고 배포를 중단합니다

### Exposure

프로세스가 기동될 때 한 번 실행됩니다. 배포 잡마다 터널을 다시 열지 않습니다.

* 루프백 프록시를 엽니다
* API 토큰, 계정 ID, 존 ID, 존 이름이 모두 있으면 터널 `lily-{agentId}`를 만들거나 찾아서, 발급된 터널 토큰으로 cloudflared를 실행합니다. 앱 공개 주소는 첫 배포가 끝날 때 정해집니다
* 네 값 중 일부만 있으면 기동을 중단합니다
* API 설정이 없고 `CLOUDFLARE_ENABLED=false`이면 공개 주소는 `http://127.0.0.1:{proxyPort}`입니다
* API 설정이 없고 터널 토큰도 없으면 quick tunnel 주소를 cloudflared 출력에서 읽습니다. 호스트는 `trycloudflare.com`이며, 프로세스를 다시 실행하면 바뀝니다
* API 설정이 없고 `CLOUDFLARE_TUNNEL_TOKEN`이 있으면 그 토큰으로 터널을 실행합니다. 공개 주소는 `PUBLIC_URL`이며, 이 값이 없으면 기동을 중단합니다

### Public Address

헬스가 통과한 뒤, 프록시를 바꾸기 전에 실행됩니다. API 자격 증명이 있을 때만 Cloudflare를 호출합니다.

호출 순서는 다음과 같습니다.

1. `PUT /accounts/{account}/cfd_tunnel/{tunnel}/configurations`
   ingress는 `{app}.{zone}`에서 `http://127.0.0.1:{proxyPort}`로 보내고, 마지막 규칙은 `http_status:404`입니다
2. `POST` 또는 `PUT /zones/{zone}/dns_records`
   CNAME `{app}.{zone}`는 `{tunnelId}.cfargotunnel.com`을 가리키고, `proxied`는 `true`입니다
3. `GET /zones/{zone}/ssl/certificate_packs`
   활성 인증서나 발급 중인 인증서의 범위에 그 호스트 또는 `*.{zone}`이 있으면 주문을 생략합니다
4. 범위에 없으면 `POST /zones/{zone}/ssl/certificate_packs/order`
   해당 호스트의 Advanced Certificate를 주문합니다

같은 앱을 다시 배포할 때, CNAME이 이미 그 터널을 가리키고 프록시되어 있으면 레코드는 유지합니다. ingress의 origin만 프록시 포트로 갱신합니다. API가 실패하면 후보 컨테이너를 지우고 upstream은 바꾸지 않습니다.

이어서 `TrafficSwitch.route`가 프록시 upstream만 바꿉니다. 이 호출이 실패하면 후보를 삭제합니다.

API 토큰과 터널 토큰은 로그와 예외에 남기지 않습니다. `AGENT_ID`는 터널 이름에 들어가므로 고정해야 합니다. 비워 두면 프로세스를 다시 실행할 때마다 터널 이름이 바뀝니다.

### Control Session

Exposure가 열린 뒤에 실행됩니다.

* `CONTROL_PLANE_URL`이 있으면 `ws://` 또는 `wss://`로 접속합니다
* 접속 직후 `hello`를 보냅니다. 에이전트 id, 공개 주소, 버전을 담습니다
* 잡 메시지의 `type`은 `job`입니다
* 단계마다 `status`를 보냅니다. 상태, 로그 한 줄, 공개 주소, 활성 슬롯을 담습니다
* 연결이 끊기면 5초 간격으로 다시 접속합니다
* 전송에 실패해도 로컬에 쌓인 잡 기록은 지우지 않습니다

### Logging

단계 이름은 `JobRecord.Status`입니다.

```text
QUEUED → CHECKOUT → ANALYZE → BUILDING → STARTING → HEALTH → SWITCHING → SUCCEEDED
```

실패하면 마지막 상태가 `FAILED`이고, 로그에 이유가 남습니다. 이 머신의 잡 목록은 프로세스가 실행 중인 동안만 유지됩니다.

---

## 상태 전이 예시

```mermaid
stateDiagram-v2

  direction LR

  [*] --> 없음

  없음 --> Blue : v1 Ready
  Blue --> Blue : v2 Timeout
  Blue --> Green : v2 Ready
  Green --> Blue : v3 Ready
```

### 상태 1

최초 배포입니다.

* Active 없음
* Target = blue
* 프록시 upstream = 0

`{app}-blue`가 Ready가 되면 upstream은 `18080`이 됩니다.

사용자는 `v1`만 볼 수 있습니다. Ready 이전의 요청은 프록시에서 끊깁니다.

### 상태 2

`v2` 배포를 시작합니다.

* Active = blue
* Target = green

`green` 컨테이너는 `18081`에서 기동되고, 프록시는 계속 `18080`을 가리킵니다.

### 상태 3

헬스 체크 시간이 초과됩니다.

후보 컨테이너를 삭제합니다.

프록시는 `blue`를 유지합니다.

사용자는 기존 버전을 계속 이용합니다.

### 상태 4

재배포가 성공합니다.

`green`이 Ready가 되면 upstream은 `18081`이 됩니다.

이 시점부터 새 요청은 `v2`로 전달됩니다. 이어서 `blue` 컨테이너를 정지합니다.

### 상태 5

이전 슬롯 정지에 실패합니다.

upstream은 이미 `green`입니다.

트래픽은 `v2`에 도달하고, `green` 컨테이너는 유지됩니다.

### 상태 6

다음 버전을 배포합니다.

* Active = green
* Target = blue

`18080`에 새 컨테이너를 띄웁니다.

Ready가 된 뒤 upstream은 다시 `blue`가 됩니다.

---

## 온보딩

에이전트는 Docker와 Git이 있는 호스트에서 실행합니다. 에이전트를 컨테이너 안에서 실행하면 그 호스트의 Docker 소켓과 cloudflared에 직접 접근하지 못합니다.

터널 없이 기동하면 이 머신의 프록시가 공개 주소입니다. 첫 배포가 성공하기 전에는 `8099`로 들어온 요청에 503을 돌려줍니다.

```text
java -jar app.jar
```

상태 API는 `http://127.0.0.1:8060`입니다.

플랫폼 존의 HTTPS 주소를 발급하려면 API 토큰에 Cloudflare Tunnel Edit 권한과 DNS Edit 권한이 필요합니다. 인증서 주문이 필요하면 SSL and Certificates Edit 권한도 필요합니다. `AGENT_ID`는 고정합니다.

```text
AGENT_ID=edge-1 CLOUDFLARE_API_TOKEN=... CLOUDFLARE_ACCOUNT_ID=... CLOUDFLARE_ZONE_ID=... CLOUDFLARE_ZONE_NAME=lily.dev java -jar app.jar
```

첫 배포가 Ready가 되면 사용자 주소는 `https://blog.lily.dev`입니다. 존의 Universal SSL 범위에 `*.lily.dev`가 있으면 추가 주문 없이 그 인증서를 사용합니다.

API 없이 quick tunnel만 켜면 주소는 `https://임의이름.trycloudflare.com`입니다. 플랫폼 도메인이 아니며, 기동할 때마다 바뀝니다.

```text
CLOUDFLARE_ENABLED=true java -jar app.jar
```

대시보드에서 미리 만든 named tunnel을 쓰려면 터널 토큰과 공개 주소를 직접 넣습니다. origin은 `http://127.0.0.1:8099`입니다.

```text
CLOUDFLARE_ENABLED=true CLOUDFLARE_TUNNEL_TOKEN=... PUBLIC_URL=https://blog.example.com java -jar app.jar
```

컨트롤 플레인에 연결할 때는 소켓 주소를 지정합니다.

```text
CONTROL_PLANE_URL=wss://control.example/agents java -jar app.jar
```

접속 직후 에이전트가 보내는 메시지입니다.

```json
{"type":"hello","agentId":"edge-1","publicUrl":"","version":"0.1.0"}
```

컨트롤 플레인이 같은 소켓으로 보내는 잡입니다.

```json
{
  "type": "job",
  "id": "job1",
  "repoUrl": "https://github.com/acme/blog",
  "branch": "main",
  "token": "",
  "appName": "blog",
  "targetPort": 8080,
  "healthPath": "/actuator/health/readiness",
  "rootDir": "",
  "dockerfile": "",
  "env": {"SPRING_PROFILES_ACTIVE": "local"}
}
```

단계 응답입니다.

```json
{"type":"status","agentId":"edge-1","id":"job1","status":"SUCCEEDED","line":"done: https://blog.lily.dev","url":"https://blog.lily.dev","activeSlot":"blue"}
```

---

## 로컬 API

WebSocket 없이 같은 파이프라인을 실행할 때 사용합니다. 본문은 `DeployJob`과 같고 `type`은 없어도 됩니다.

| Method | Path           | 설명                          |
| ------ | -------------- | --------------------------- |
| POST   | `/api/jobs`    | 배포를 시작합니다. `202`와 기록을 반환합니다 |
| GET    | `/api/jobs`    | 이 프로세스의 잡 목록입니다. 최신순입니다    |
| GET    | `/api/jobs/{id}` | 상태와 단계 로그를 반환합니다          |
| GET    | `/api/agent`   | 에이전트 id, 소켓 연결 여부, 공개 주소를 반환합니다 |

이 머신에서는 한 번에 하나의 잡만 슬롯을 바꿉니다. 같은 id의 잡이 이미 있으면 거절합니다.

---

## 설정

| 환경변수                         | 기본값     | 설명                                      |
| ---------------------------- | ------- | --------------------------------------- |
| `AGENT_ID`                     | 기동마다 생성 | 터널 이름 `lily-{agentId}`입니다. 주소를 유지하려면 값을 고정합니다 |
| `CONTROL_PLANE_URL`            | 비움      | `ws://` 또는 `wss://`입니다. 비우면 소켓을 열지 않습니다 |
| `AGENT_PROXY_PORT`             | `8099`  | 터널이 연결되는 포트입니다. 슬롯 포트와 다릅니다            |
| `AGENT_BLUE_PORT`              | `18080` | blue 슬롯입니다. 루프백만 엽니다                    |
| `AGENT_GREEN_PORT`             | `18081` | green 슬롯입니다. 루프백만 엽니다                   |
| `AGENT_HEALTH_TIMEOUT_SECONDS` | `120`   | Ready를 기다리는 시간(초)입니다                    |
| `PUBLIC_URL`                   | 비움      | API 없이 named tunnel을 쓸 때의 고정 주소입니다     |
| `CLOUDFLARE_ENABLED`           | `false` | API 없이 cloudflared를 실행하려면 `true`입니다    |
| `CLOUDFLARE_TUNNEL_TOKEN`      | 비움      | API 없이, 이미 만든 터널의 토큰입니다                 |
| `CLOUDFLARE_API_TOKEN`         | 비움      | Tunnel Edit, DNS Edit 권한이 필요합니다. 로그에 남기지 않습니다 |
| `CLOUDFLARE_ACCOUNT_ID`        | 비움      | 터널을 만들 Cloudflare 계정입니다                 |
| `CLOUDFLARE_ZONE_ID`           | 비움      | DNS 레코드를 만들 존입니다                        |
| `CLOUDFLARE_ZONE_NAME`         | 비움      | 예: `lily.dev`. 사용자 주소는 `https://{app}.{zone}`입니다 |
| `AGENT_WORKSPACE`              | 임시 디렉터리 | git clone 위치입니다                         |

---

## 애플리케이션 요구 사항

`lily-blog-sample`을 이 에이전트로 배포할 때의 기준입니다.

| 항목             | 값                            |
| -------------- | ---------------------------- |
| Container Port | 8080                         |
| Readiness      | `/actuator/health/readiness` |
| APP_COLOR      | Target Slot                  |
| APP_VERSION    | 잡 id                         |
| 이미지 빌드         | 저장소 루트의 `Dockerfile`         |

PostgreSQL이 없으면 Readiness가 실패하고 프록시는 바뀌지 않습니다.

이 머신에서 H2로 확인하려면 잡 환경변수에 다음 값을 넣습니다.

```text
SPRING_PROFILES_ACTIVE=local
```

---

## 주요 구현 위치

| 내용            | 위치                         |
| ------------- | -------------------------- |
| 배포 파이프라인      | `OnPremPipeline`           |
| 잡             | `DeployJob`                |
| 활성 슬롯         | `SlotBook`                 |
| 스택 분석         | `StackAnalyzer`            |
| 로컬 Docker     | `CliContainerRuntime`      |
| 프록시           | `UpstreamProxy`            |
| 터널            | `CloudflareLauncher`       |
| 호스트이름·인증서    | `CloudflareHostnameProvisioner` |
| 공개 입구         | `LocalExposure`            |
| 컨트롤 플레인 연결    | `WebSocketControlSession`  |
| 로컬 API        | `POST /api/jobs`           |
| 배포 테스트        | `OnPremPipelineTest`, `CloudflareHostnameProvisionerTest` |
| 실행            | `./gradlew test`           |

테스트는 Docker, cloudflared, 컨트롤 플레인에 연결하지 않은 상태에서 슬롯 선택과 트래픽 전환 순서만 확인합니다.

---

## 클라우드 버스팅

온프레미스가 감당하지 못하는 요청을 클라우드(k3s)로 넘긴다. 공개 주소(Cloudflare)는 그대로이고, 에이전트 프록시 뒤에서 나눈다.

```
사용자 → https://{app}.{zone} → Tunnel → 에이전트 프록시 ─┬─ 로컬 슬롯 (처리 중 요청 localLimit 까지)
                                                          └─ 클라우드 Ingress → k3s Pod (넘칠 때)
```

| 단계 | 조건 | 동작 |
|---|---|---|
| STANDBY | 온프레미스 배포 성공 | lily-builder `/api/burst` 로 같은 레포를 클라우드에 빌드·배포하고 레플리카 0 으로 대기. Ingress 호스트는 공개 주소와 같다 |
| WARMING | 대기 배포 완료 | 클라우드 레플리카 `warmReplicas` 로. Ingress 로 Pod 까지 닿으면 넘김을 켠다 (`warmReplicas` 0 이면 건너뜀) |
| IDLE | 대기 완료 | 로컬이 한도 안에서 처리. 대기 Pod 가 있으면 한도를 넘는 요청은 바로 클라우드로 |
| SCALING | 대기 Pod 없이 로컬 처리 중 요청이 한도 이상인 채로 `scaleUpAfterSeconds` 초 | 클라우드 레플리카 `replicas` 로 |
| OVERFLOWING | 클라우드 Ready 1 이상, 또는 대기 Pod 가 있는 상태에서 과부하 | 로컬이 한도에 닿으면 그 요청을 클라우드로 (헤더 그대로, Host 유지). 대기 Pod 가 있으면 SCALING 없이 바로 여기로 오고 레플리카만 `replicas` 로 올린다 |
| IDLE | `cooldownSeconds` 초 동안 한가 | 클라우드를 `warmReplicas` 로. 대기 Pod 가 없으면 넘김 끄고 0 |

대기 Pod 를 두는 이유: 0 에서 띄우면 Spring 앱 기준 Ingress 가 Pod 까지 닿는 데 20~40초 걸리고, 그동안 넘기지 못한다. 대기 Pod 가 180초 안에 안 뜨면 0 으로 내리고 넘길 때 띄우는 방식으로 동작한다.

- 상태: `GET /api/burst` (단계, 로컬·클라우드 처리 중 요청, 최근 이벤트)
- 요청 단위로 나눈다. cloudflared 는 keep-alive 연결 몇 개에 요청을 몰아 보내므로 연결 단위로는 분배를 정할 수 없다
- 로컬 자리는 CAS 로 잡아 동시에 들어온 요청이 한도를 넘지 않는다
- 클라우드에 붙지 못하면 그 요청은 로컬로 보낸다
- WebSocket 은 연결이 끝날 때까지 처음 정한 쪽에 붙는다
- 클라우드 빌드는 레포의 Dockerfile 을 쓴다 (에이전트가 만든 Dockerfile 은 클라우드로 가지 않는다)

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `BURST_ENABLED` | `false` | |
| `BURST_BUILDER_URL` | | lily-builder 공개 주소. 예: `http://builder.43.200.152.53.nip.io` |
| `BURST_API_TOKEN` | | lily-builder 의 `BURST_API_TOKEN` (k3s Secret `lily-system/lily-burst`) |
| `BURST_INGRESS_HOST` / `BURST_INGRESS_PORT` | / `80` | 넘길 클라우드 Ingress |
| `BURST_PUBLIC_HOST` | `{app}.{zone}` | 사용자가 여는 호스트. `{app}` 은 앱 이름 |
| `BURST_LOCAL_LIMIT` | `8` | 로컬 슬롯이 동시에 처리할 요청 수 |
| `BURST_SCALE_UP_AFTER_SECONDS` | `3` | |
| `BURST_COOLDOWN_SECONDS` | `30` | |
| `BURST_REPLICAS` | `2` | DB 를 붙이면 (로컬 1 + 클라우드 N) x 풀 3 이 20 이하 |
| `BURST_WARM_REPLICAS` | `1` | 평소에 띄워 둘 클라우드 Pod. 0 이면 넘길 때 띄운다 (Pod 당 놀 때 메모리 약 280Mi) |
| `BURST_DATABASE` | | 클라우드 대기 배포에 붙일 DB (`postgres` / `mysql`) |

검증 (2026-09-30): 로컬 한도 4, 동시 16 연결로 60초 부하 → 3초 뒤 클라우드 2 Pod 로 스케일, 약 16초 뒤 넘김 시작, 부하 종료 20초 뒤 0 으로. 2,822 요청 모두 200.

### DB 공유 (클라우드와 같은 RDS)

배포 요청에 `"database": "postgres"` 를 넣으면 온프레미스 앱과 클라우드 대기 배포가 **같은 DB** 를 쓴다.

```
에이전트 ── ssh -L 172.17.0.1:15432 → lily-server(lily-tunnel, RDS:5432 포워딩만) → RDS
   └ lily-builder /api/burst/apps/{app}/database → 프로비저너: DB 찾기/만들기, 터널 주소로 접속 정보
```

- projectId = appName 이라 lily-cicd 의 클라우드 배포도 같은 DB 를 받는다
- 터널은 Docker 브리지 게이트웨이(172.17.0.1)에만 열린다. 컨테이너에서만 닿고 LAN 에는 노출되지 않는다
- 배스천 계정은 lily-db-provisioner 의 `deploy/k3s/cluster/db-tunnel-user.sh` 로 만든다 (셸 없음, RDS:5432 포워딩만)
- 커넥션: 온프레미스 슬롯 최대 2 + 클라우드 `BURST_REPLICAS`, 각 풀 3 → 20 이하

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `DB_TUNNEL_SSH_HOST` | | 배스천 (lily-server 공개 IP) |
| `DB_TUNNEL_SSH_USER` | `lily-tunnel` | |
| `DB_TUNNEL_SSH_KEY` | | 개인키 경로 (권한 600) |
| `DB_TUNNEL_REMOTE_HOST` / `_PORT` | / `5432` | RDS 엔드포인트 |
| `DB_TUNNEL_BIND_HOST` / `_PORT` | `172.17.0.1` / `15432` | |

DB 는 `BURST_BUILDER_URL`, `BURST_API_TOKEN` 도 필요하다 (lily-builder 를 거쳐 받는다).

검증 (2026-09-30): 온프레미스에서 쓴 글을 버스팅된 클라우드 Pod 가 읽고, 클라우드 Pod 에 쓴 글을 스케일 다운 뒤 온프레미스에서 읽음.
