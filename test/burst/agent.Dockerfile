# 실주소 버스팅 테스트용 에이전트 이미지: JRE + git + ssh + docker CLI + cloudflared (호스트 Docker 소켓 사용)
# 저장소 루트에서: docker build -f test/burst/agent.Dockerfile -t lily-onprem-agent:burst .
# DB 터널 개인키는 이미지에 넣지 않는다. /keys 로 마운트하면 권한 600 으로 복사해서 쓴다
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew -q bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre
RUN apt-get update -qq && apt-get install -y -qq git openssh-client >/dev/null && rm -rf /var/lib/apt/lists/*
COPY --from=docker:27-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=cloudflare/cloudflared:latest /usr/local/bin/cloudflared /usr/local/bin/cloudflared
COPY --from=build /src/build/libs/app.jar /app/agent.jar
ENTRYPOINT ["sh", "-c", "if [ -f /keys/id_ed25519 ]; then install -m 600 /keys/id_ed25519 /tmp/tunnel_key; fi; exec java -jar /app/agent.jar"]
