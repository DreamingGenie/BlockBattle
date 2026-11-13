# 동시 100방 부하 시험 (T-17)

친구 대전 방 100개(가상 사용자 200명)를 1분 동안 늘린 뒤 10분 유지하면서 합격 기준을 잰다.
기준은 요구사항 REQ-016·REQ-017·REQ-022(NFR-01·NFR-02)다.

| 파일 | 역할 |
| --- | --- |
| `battle.js` | k6 시나리오. VU 1개가 방 1개를 맡고 두 플레이어의 STOMP 연결을 모두 연다 — lock 전송에서 상대 BOARD 수신까지를 같은 시계로 잰다 |
| `bot.js` | 부하 봇의 배치 선택. 서버 검증을 통과하는 자리에만 놓는다. 5초마다 공격·수비 역할을 바꿔 양방향 공격을 만든다 |
| `engine.js` | `frontend/src/engine`의 난수·7-bag·블록 모양·보드 연산을 k6용으로 옮긴 것 |
| `docker-compose.yml` | MySQL·Redis·백엔드(cpus 2·memory 4g)·k6 |
| `run.sh` | 환경을 새로 띄우고 k6와 docker stats 샘플링(5초)을 함께 돌린 뒤 보고서를 만든다 |
| `report.py` | 유지 구간 지표로 기준별 합·불 판정 → `results/<시각>/report.md` |

## 실행

```bash
cd backend && ./gradlew bootJar && cd ..
bash loadtest/run.sh            # 기본: 100방, 램프 60초, 유지 600초
bash loadtest/run.sh 4 10 90    # 짧은 시범
```

필요한 이미지: `grafana/k6:1.3.0`, `eclipse-temurin:21-jre`, `mysql:8.4`, `redis:7.4-alpine`.
포트를 호스트에 열지 않으므로 개발용 `infra/docker-compose.yml`과 동시에 띄워도 된다.

## 측정 방식과 한계

- **로컬 근사 측정이다.** 백엔드 컨테이너만 CPU 2개·메모리 4GB로 제한했고, k6·MySQL·Redis는 같은 호스트에서 돈다.
  같은 리전 2 vCPU·4GB 서버에서의 공식 판정을 대신하지 않는다. 서버가 생기면 `BASE_URL`을 바꿔 같은 시나리오로 다시 잰다.
- CPU는 node exporter 대신 `docker stats`로 잰다. 2 vCPU 기준 사용률 = docker CPU% ÷ 2. 백엔드 단독과 세 컨테이너 합계를 함께 적는다.
- 오류·끊김 비율 = (WebSocket 오류 + STOMP ERROR + 끝내기 전 끊김) ÷ 연결 수(200).
- 봇이 topout하면 재대결을 수락해 이어 간다. 카운트다운 3초 동안은 lock이 멈추므로, 실제 lock 빈도를 보고서에 함께 적는다.
- 공격 프로필('10초마다 2줄 이상 공격이 오가도록')은 10초 구간마다 양방향 모두 2줄 이상 ATTACK이 있었는지로 재고, 그 비율을 보고한다.
