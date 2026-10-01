#!/usr/bin/env bash
# 이 PC 에서 에이전트를 띄워 lily 플랫폼(lily-frontend 의 "내 PC")에 연결한다.
#
#   LILY_AGENT_TOKEN=<화면에서 받은 토큰> ./scripts/agent.sh        빌드 + 시작 (기존 컨테이너는 지우고 다시)
#   ./scripts/agent.sh logs                                          로그
#   ./scripts/agent.sh stop                                          중지
#
# 설정 파일은 버스팅 테스트와 같은 test/burst/ 의 것을 쓴다 (git 에 올리지 않는다)
# 공개 주소
#   test/burst/cloudflare.env 가 있으면 플랫폼 존 주소 https://{앱}.{zone} (팀 PC 전용, cloudflare.env.example 참고)
#   없으면 quick tunnel 주소 https://임의이름.trycloudflare.com (기동할 때마다 바뀐다)
# DB
#   test/burst/burst.env 와 keys/id_ed25519 가 있으면 클라우드 RDS 를 SSH 터널로 붙인다 (test/burst/README.md)
#   없으면 DB 가 필요한 앱도 DB 없이 배포된다
#
# 필요한 것: Docker (Docker Desktop 이면 Settings > Resources > Network 의 host networking 켜기)
set -euo pipefail
cd "$(dirname "$0")/../test/burst"
export MSYS_NO_PATHCONV=1
HERE=$(pwd -W 2>/dev/null || pwd)
IMAGE=${AGENT_IMAGE:-lily-agent:local}
NAME=lily-agent
CONTROL=${LILY_CONTROL_URL:-wss://builder.apps.lilycloud.kr/api/agents/connect}

case "${1:-start}" in
  logs) exec docker logs -f "$NAME" ;;
  stop) docker rm -f "$NAME" >/dev/null 2>&1 || true; echo "stopped"; exit 0 ;;
esac

[ -n "${LILY_AGENT_TOKEN:-}" ] || { echo "LILY_AGENT_TOKEN 이 필요합니다 (lily 화면의 '내 PC 연결'에서 발급)"; exit 1; }

docker build -q -f agent.Dockerfile -t "$IMAGE" ../.. >/dev/null

ARGS=(-e "CONTROL_PLANE_URL=$CONTROL?token=$LILY_AGENT_TOKEN" -e CLOUDFLARE_ENABLED=true
      -e "AGENT_ID=${AGENT_ID:-$(hostname | tr 'A-Z' 'a-z' | tr -c 'a-z0-9\n' '-')}")
[ -f cloudflare.env ] && ARGS+=(--env-file "$HERE/cloudflare.env")
[ -f burst.env ] && ARGS+=(--env-file "$HERE/burst.env")
[ -d keys ] && ARGS+=(-v "$HERE/keys:/keys:ro")

docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" --restart unless-stopped --network host \
  -v //var/run/docker.sock:/var/run/docker.sock "${ARGS[@]}" "$IMAGE" >/dev/null
echo "에이전트를 시작했습니다. 화면에서 '연결됨'이 보이면 됩니다. 로그: ./scripts/agent.sh logs"
