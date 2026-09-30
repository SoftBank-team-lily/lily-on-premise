# 클라우드 버스팅 실주소 테스트

이 PC 를 온프레미스로 쓰고, Cloudflare 공개 주소로 버스팅과 DB 공유를 확인한다.
개발 과정과 측정값은 [docs/버스팅-개발기록.md](../../docs/버스팅-개발기록.md) 에 있다.

## 준비

| 파일 | 내용 |
|---|---|
| `cloudflare.env` | `cloudflare.env.example` 복사 후 Account API 토큰, 계정 ID, 존 ID |
| `burst.env` | `burst.env.example` 복사 후 버스팅 토큰, RDS 엔드포인트 |
| `keys/id_ed25519` | DB 터널 개인키 (lily-server `lily-tunnel` 계정에 등록된 키) |

세 개 모두 `.gitignore` 에 있다. 채팅이나 git 에 올리지 않는다.

## 순서

```bash
./run-agent.sh build     # 저장소 코드로 에이전트 이미지
./run-agent.sh           # 에이전트 시작: 터널 lily-{AGENT_ID} 생성, cloudflared 실행
./run-agent.sh deploy    # burst-demo 배포 (온프레미스 + 클라우드 대기 배포, DB 공유)
./run-agent.sh status    # phase 가 IDLE 이면 준비 완료
./load.sh https://burst-demo.lilycloud.kr 20 70 300
```

`load.sh` 는 `/chaos/slow?ms=300` 에 동시 20 으로 70초 부하를 걸고, 5초마다 상태를 남기고,
부하 중과 부하 후에 누가 응답했는지 센다 (온프레미스는 컨테이너 ID, 클라우드는 `burst-demo-*` Pod 이름).

## 기대 결과 (로컬 한도 4)

- `IDLE → SCALING`(약 5초) `→ OVERFLOWING`(약 17초) `→ IDLE`(부하 종료 후 cooldown)
- 넘김 중 `localActive` 는 한도 이하, 나머지는 `remoteActive`
- 오류 0, `fallbackTotal` 0
- 부하 후 응답은 모두 온프레미스

## 주의

- `/whoami` 처럼 수 ms 로 끝나는 요청은 앱을 포화시키지 못해 버스팅이 켜지지 않는다. 정상이다
- Cloudflare 속도 제한·봇 차단에 걸리지 않게 동시 수를 과하게 올리지 않는다
- 끝나면 `docker rm -f lily-agent burst-demo-blue burst-demo-green`. 클라우드는 cooldown 뒤 레플리카 0 으로 내려간다
