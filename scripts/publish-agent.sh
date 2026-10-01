#!/usr/bin/env bash
# 사용자 PC 용 에이전트 이미지를 공개 레지스트리에 올린다. lily 화면의 "내 PC 연결" 명령이 이 이미지를 쓴다.
#
#   ./scripts/publish-agent.sh            :latest 와 :{커밋} 태그
#
# 필요한 것: Docker, ~/.aws 의 lily 계정 자격 (ECR Public 은 us-east-1)
set -euo pipefail
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1
REPO=${AGENT_PUBLIC_REPO:-public.ecr.aws/x3w9c9r7/lily-agent}
TAG=$(git rev-parse --short HEAD)
AWS_DIR=$(cd ~/.aws && (pwd -W 2>/dev/null || pwd))

PASSWORD=$(docker run --rm -v "$AWS_DIR:/root/.aws:ro" amazon/aws-cli ecr-public get-login-password --region us-east-1 </dev/null)
docker build -q -f test/burst/agent.Dockerfile -t "$REPO:$TAG" -t "$REPO:latest" . >/dev/null
# Windows 자격 증명 저장소는 ECR Public 토큰(긴 값)을 저장하지 못한다. 로그인과 push 는 리눅스 docker CLI 컨테이너에서 한다
docker run --rm -i -v //var/run/docker.sock:/var/run/docker.sock docker:27-cli sh -c \
  "docker login -u AWS --password-stdin public.ecr.aws >/dev/null && docker push -q $REPO:$TAG && docker push -q $REPO:latest" \
  <<<"$PASSWORD"
echo "pushed $REPO:$TAG (latest)"
