#!/usr/bin/env bash
# 온프레미스 에이전트를 이 PC 의 Docker 로 띄운다 (Cloudflare + 클라우드 버스팅 + DB 터널).
#   ./run-agent.sh build      저장소 코드로 에이전트 이미지 빌드
#   ./run-agent.sh            에이전트 시작 (기존 컨테이너는 지우고 다시)
#   ./run-agent.sh deploy     burst-demo 앱을 DB(postgres) 포함으로 배포
#   ./run-agent.sh status     버스팅 상태
#
# 필요한 파일 (git 에 올리지 않는다): cloudflare.env, burst.env, keys/id_ed25519 (DB 터널 개인키)
set -euo pipefail
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1
HERE=$(pwd -W 2>/dev/null || pwd)
KEYS=${TUNNEL_KEY_DIR:-$HERE/keys}
IMAGE=${AGENT_IMAGE:-lily-onprem-agent:burst}
CURL="docker run --rm --network host curlimages/curl:8.10.1 -s"

case "${1:-start}" in
  build)
    docker build -f agent.Dockerfile -t "$IMAGE" ../.. ; exit 0 ;;
  status)
    $CURL http://127.0.0.1:8060/api/burst; echo; exit 0 ;;
  deploy)
    $CURL -X POST http://127.0.0.1:8060/api/jobs -H 'Content-Type: application/json' \
      -d '{"repoUrl":"https://github.com/SoftBank-team-lily/lily-blog-sample","branch":"main","appName":"burst-demo","targetPort":8080,"database":"postgres"}'
    echo; exit 0 ;;
esac

for k in CLOUDFLARE_API_TOKEN CLOUDFLARE_ACCOUNT_ID CLOUDFLARE_ZONE_ID CLOUDFLARE_ZONE_NAME; do
  grep -qE "^$k=.+" cloudflare.env || { echo "cloudflare.env 의 $k 를 채워주세요 (cloudflare.env.example 참고)"; exit 1; }
done
[ -f burst.env ] || { echo "burst.env 가 없습니다 (burst.env.example 참고)"; exit 1; }

docker rm -f lily-agent burst-demo-blue burst-demo-green >/dev/null 2>&1 || true
docker run -d --name lily-agent --network host \
  -v //var/run/docker.sock:/var/run/docker.sock \
  -v "$KEYS:/keys:ro" \
  --env-file "$HERE/cloudflare.env" --env-file "$HERE/burst.env" \
  -e AGENT_ID="${AGENT_ID:-$(hostname | tr 'A-Z' 'a-z')}" -e CLOUDFLARE_ENABLED=true \
  "$IMAGE" >/dev/null
for i in $(seq 1 40); do $CURL http://127.0.0.1:8060/api/burst >/dev/null 2>&1 && break; sleep 2; done
echo "agent up. 로그: docker logs -f lily-agent"
