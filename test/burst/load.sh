#!/usr/bin/env bash
# 공개 주소로 부하를 걸고, 버스팅 상태와 누가 응답했는지 기록한다.
#   ./load.sh https://burst-demo.lilycloud.kr [동시 수] [초] [요청당 ms]
# 요청당 ms 만큼 앱이 잠드는 /chaos/slow 를 쓴다 (lily-blog-sample). /whoami 처럼 수 ms 로 끝나는 요청은
# 앱을 포화시키지 못해 버스팅이 켜지지 않는다.
set -euo pipefail
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1
BASE=${1:?공개 주소}
CONCURRENCY=${2:-20}
SECONDS_=${3:-70}
SLEEP_MS=${4:-300}
URL="$BASE/chaos/slow?ms=$SLEEP_MS"
CURL="docker run --rm --network host curlimages/curl:8.10.1 -s"

sample() {
  echo "$(date +%T) $($CURL http://127.0.0.1:8060/api/burst | grep -oE '"phase":"[A-Z]*"|"localActive":[0-9]+|"remoteActive":[0-9]+|"overflowedTotal":[0-9]+|"fallbackTotal":[0-9]+' | tr '\n' ' ')"
}
# 응답한 인스턴스별 개수. 200 이 아니면 상태 코드로 센다
who() {
  for _ in $(seq 1 "$1"); do
    body=$(curl -s -w '\n%{http_code}' "$URL" || true)
    code=${body##*$'\n'}
    if [ "$code" = 200 ]; then
      echo "$body" | grep -o '"instance":"[^"]*"' || echo "200 (instance 없음)"
    else
      echo "HTTP $code"
    fi
  done | sort | uniq -c
}

( for _ in $(seq 1 $(( (SECONDS_ + 60) / 5 ))); do sample; sleep 5; done ) > status.log 2>&1 &
docker run --rm williamyeh/hey -z "${SECONDS_}s" -c "$CONCURRENCY" "$URL" > hey.log 2>&1 &
HEY=$!
sleep $(( SECONDS_ * 2 / 3 ))
echo "부하 중 응답:"; who 30
wait "$HEY" || true
sleep 30
echo "부하 후 응답:"; who 15
wait
echo "--- 상태 (5초 간격)"; cat status.log
echo "--- 부하 결과"; grep -E "Requests/sec|Average|Slowest|\[[0-9]{3}\]" hey.log; grep -A5 "Error distribution" hey.log || true
