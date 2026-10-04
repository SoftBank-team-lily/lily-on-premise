#!/usr/bin/env bash
# 사용자 PC 용 에이전트 이미지를 공개 레지스트리에 올린다. lily 화면의 "내 PC 연결" 명령이 이 이미지를 쓴다.
#
#   ./scripts/publish-agent.sh            :latest 와 :{커밋} 태그 (linux/amd64, linux/arm64 두 벌)
#
# 필요한 것: Docker (containerd 이미지 저장소, arm64 는 qemu 로 빌드), ~/.aws 의 lily 계정 자격 (ECR Public 은 us-east-1)
set -euo pipefail
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1
REPO=${AGENT_PUBLIC_REPO:-public.ecr.aws/x3w9c9r7/lily-agent}
PLATFORMS=${AGENT_PLATFORMS:-linux/amd64,linux/arm64}
TAG=$(git rev-parse --short HEAD)
AWS_DIR=$(cd ~/.aws && (pwd -W 2>/dev/null || pwd))

PASSWORD=$(docker run --rm -v "$AWS_DIR:/root/.aws:ro" amazon/aws-cli ecr-public get-login-password --region us-east-1 </dev/null)
# Apple Silicon Mac 등 arm64 PC 가 에뮬레이션 없이 받도록 한 태그에 두 벌을 묶는다 (pull 할 때 Docker 가 고른다)
docker buildx build -q --platform "$PLATFORMS" -f test/burst/agent.Dockerfile -t "$REPO:$TAG" -t "$REPO:latest" --load . >/dev/null
# Windows 자격 증명 저장소는 ECR Public 토큰(긴 값)을 저장하지 못한다. 로그인과 push 는 리눅스 docker CLI 컨테이너에서 한다
docker run --rm -i -v //var/run/docker.sock:/var/run/docker.sock docker:27-cli sh -c \
  "docker login -u AWS --password-stdin public.ecr.aws >/dev/null && docker push -q $REPO:$TAG && docker push -q $REPO:latest" \
  <<<"$PASSWORD"
echo "pushed $REPO:$TAG (latest)"
