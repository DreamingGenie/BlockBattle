# beez-tetris 부하·보안 취약점 점검 (2026-11-16)

> 커밋하지 않는 분석 메모다. 티켓으로 옮길 항목을 고르는 데 쓴다.

## 0. 점검 조건

- **기준 코드**: `main` `bf7239d`(T-29 AWS 배포까지). 미커밋 파일(`docs/README.md`, `docs/original/`, `docs/run/`, `share/`)은 코드가 아니라 결론에 영향이 없다.
- **방법**: 정적 분석만 했다. 배포된 EC2(sslip.io 주소)와 로컬 서버를 포함해 어디에도 요청을 보내지 않았다. 코드 수정·커밋·티켓 생성도 없다. 부하 수치는 저장소에 있는 `loadtest/results/*`를 그대로 인용했다.
- **확인 수준**: 발견마다 붙인다.
  - **코드 확인**: 해당 줄을 직접 읽어 확인했다.
  - **추정**: 프레임워크 기본 동작, 서버 실제 상태, 실측이 없는 시간·메모리 수치에 기대는 판단이다.
- **비밀 값**: 옮기지 않고 위치만 적었다. 저장소와 이력에 커밋된 운영 비밀은 없다(`git ls-files`와 `.env`·`.pem` 추가 이력 0건).
- **경로 약어**: `BB/` = `backend/src/main/java/com/blockbattle/`. 그 밖의 경로는 beez-tetris 루트 기준이다.

## 1. 요약

| ID | 심각도 | 종류 | 제목 | 규모 | 확인 |
|---|---|---|---|---|---|
| H-1 | 높음 | 보안 | 싱글 랭킹 점수를 요청 하나로 위조할 수 있음 | 작음(단기) / 큼(재생 검증) | 코드 확인 |
| H-2 | 높음 | 보안 | STOMP 권한 검사가 `/topic` 구독과 `/app/rooms` 발행에만 걸려 있음 (가짜 메시지 방송, 남의 개인 큐 엿듣기) | 작음 | 인터셉터는 코드 확인, 브로커 전달은 추정 |
| H-3 | 높음 | 부하·보안 | 요청 한도가 어디에도 없고 Redis·컨테이너 메모리 상한도 없음 (4GB 서버 한 대) | 작음~중간 | 설정 부재는 코드 확인, 수치는 추정 |
| M-1 | 중간 | 부하 | AI 서버 CPU 고갈: 계산 기한·동시성 제한·한도 없음, 같은 2 vCPU를 나눠 씀 | 중간 | 구조는 코드 확인, 수치는 추정 |
| M-2 | 중간 | 부하 | STOMP 하트비트가 꺼져 있음 → 반쯤 열린 연결을 감지하지 못하고, 살아 있는 쪽이 질 수 있음 | 작음 | 코드 확인, 감지 지연 시간은 추정 |
| M-3 | 중간 | 부하 | inbound 4스레드를 모든 방이 같이 쓰고, lock마다 Redis 11~14번 동기 왕복 | 중간 | 코드와 시험 로그로 확인, 정지 시나리오는 추정 |
| M-4 | 중간 | 부하 | 부하 시험 환경이 배포와 달라 NFR-02 여유가 과대평가됨 | 작음(측정) | 환경 차이는 코드 확인, 70% 초과는 추정 |
| M-5 | 중간 | 보안 | 친구 대전 lock은 이동 경로를 검사하지 않고, T-spin은 클라이언트가 스스로 신고함 | 중간 | 코드 확인 |
| M-6 | 중간 | 보안 | 카카오 연결 API에 state(CSRF) 대조 장치가 없음 | 작음~중간 | 추정(콜백 화면 미구현) |
| L-1~L-17 | 낮음 | — | §4 표 참고 | — | — |

---

## 2. 높음

### H-1. 싱글 랭킹 점수를 요청 하나로 위조할 수 있음 — 보안 · 높음

- **근거(코드 확인)**
  - `BB/match/MatchService.java:70-74,86`: 점수·지운 줄·놓은 블록·`maxCombo`·`durationMs`를 모두 클라이언트 값으로 받아 상한 검사에 넣고, 통과하면 `raiseSoloBest`로 랭킹을 올린다.
  - `BB/match/MatchService.java:98-118` `validate`: 음수가 아닌지만 본다.
  - `BB/match/ResultLimits.java:15-20`: 검사 ③은 `pieces ≤ durationMs/100`인데, `durationMs`를 서버의 `started_at`(`MatchService.java:53-57`)과 비교하지 않는다. 검사 ④의 `maxCombo`에는 상한이 없다.
  - 프론트는 `frontend/src/screens/SoloScreen.tsx:81,105`에서 플레이 시간을 직접 계산해 보낸다.
- **악용 시나리오**: 게스트 토큰은 누구나 받는다(`POST /auth/guest`). 그 토큰으로 `POST /matches {mode:SOLO}` 직후 결과를 보낸다. 예를 들어 `durationMs=1000, piecesPlaced=10, linesCleared=4, level=1, maxCombo=50000000, score=2147483647`이면 ④의 상한이 약 10¹⁰이 되어 통과한다. 결과는 `ranked=true`로 저장되고 랭킹 1위가 된다. H-3 때문에 계정을 무제한 만들 수 있으니 랭킹 전체를 도배할 수도 있다. BR-07·POL-007(점수 상한으로 조작 차단)이 실제로는 막지 못한다.
  - vs AI도 같은 구조다. `result:"WIN"`을 자기 신고하면 `ai_wins`가 오른다(`MatchService.java:77-83`). AI 랭킹이 없어 피해는 개인 전적에 그친다(L-6).
- **고치는 방향**
  - 단기:
    - `durationMs ≤ (now − started_at) + 여유`를 검사한다. 일시정지 시간은 빠지므로 정상 경기는 항상 통과한다.
    - `maxCombo ≤ piecesPlaced`를 검사한다.
    - 위반하면 `ranked=false`로 저장한다(기존 정책과 같다).
  - 근본: 시드와 입력 기록을 받아 서버가 재생해 검증한다. 문서에서 보류한 항목이다(`docs/README.md:38`).
- **규모**: 단기 작음, 재생 검증 큼

### H-2. STOMP 권한 검사가 `/topic` 구독과 `/app/rooms` 발행에만 걸려 있음 — 보안 · 높음

- **근거(인터셉터는 코드 확인)**
  - `BB/realtime/StompAuthInterceptor.java:64`: 목적지가 `/user/`로 시작하면 검사 없이 통과한다.
  - 같은 파일 `:66-70`: 검사 대상(`guarded`)은 SUBSCRIBE면 `/topic/`, SEND면 `/app/rooms/`뿐이다. 다른 목적지는 모두 통과한다.
  - `BB/realtime/WebSocketConfig.java:35-37`: `enableSimpleBroker("/topic", "/queue")`, user 접두어 `/user`
- **악용 시나리오(브로커 전달은 Spring SimpleBroker 기본 동작에 근거한 추정)**
  1. **가짜 방송**: `SEND /topic/rooms/{code}`는 검사 대상이 아니다. 그래서 브로커가 그 방 구독자 전원에게 가짜 `GAME_OVER`·`BOARD`·`ATTACK` 봉투를 전달한다. 방 코드만 알면 된다. 초대 링크에 들어 있다.
  2. **특정 사용자 교란**: `SEND /user/{userId}/queue/state` 또는 `/queue/errors`로 아무 사용자에게나 가짜 RESYNC·INVALID_LOCK을 넣는다. userId는 순번이고 방·랭킹 응답에 노출된다. 피해자 화면은 보드와 시드가 바뀌어 동기가 깨진다.
  3. **엿듣기**: SimpleBroker는 구독 목적지에 패턴을 허용한다. 그래서 `SUBSCRIBE /queue/**`로 모든 사용자의 개인 큐(`/queue/state-user…`)를 받아 본다. 다른 경기의 보드·시드·점수가 여기에 실린다. 시드를 알면 상대의 다음 블록 순서도 안다.
  4. 권한은 SUBSCRIBE 순간에만 검사한다. 그래서 방에서 나간 뒤에도 `/topic/rooms/{code}` 구독이 남아 이벤트를 계속 받는다(`BB/room/RoomService.java:120-146` 퇴장 처리에 구독 해제 없음).
  - 서버의 판정 상태(보드·점수·승패)는 바뀌지 않는다. 다만 상대 화면을 속이거나 망가뜨릴 수 있다. NFR-04(방 미참가자의 구독·발행 거부)를 어긴다.
- **고치는 방향**: 허용 목록 방식으로 바꾼다.
  - SEND: `/app/rooms/{내 방}/(ready|lock|topout|rematch)`만 허용한다. `/topic`·`/queue`·`/user`로의 SEND는 모두 거부한다.
  - SUBSCRIBE: `/topic/rooms/{내 방}`과 정확히 `/user/queue/(errors|state)`만 허용한다. `*`·`?`가 든 목적지는 거부한다.
  - 대안: Spring Security messaging(`simpDestMatchers`)을 도입한다.
  - 시험: 비참가자 SEND `/topic/...`, `SUBSCRIBE /queue/**`가 버려지는지 확인하는 회귀 시험을 넣는다.
- **규모**: 작음

### H-3. 요청 한도가 어디에도 없고 Redis·컨테이너 메모리 상한도 없음 — 부하·보안 · 높음

- **근거(설정 부재는 코드 확인)**
  - `deploy/nginx.conf.template` 전체에 `limit_req`·`limit_conn`이 없다. 백엔드에도 rate·bucket 구현이 없다.
  - `BB/auth/AuthController.java:56-58`, `BB/auth/GuestAccountService.java:41-58`: 인증 없는 `POST /auth/guest`가 요청마다 `users`·`user_stats` 행과 Refresh 키(TTL 7일, `BB/store/redis/RedisKeys.java:18`)를 만든다.
  - `BB/match/MatchController.java:45-52`: `POST /matches`가 경기 행을 만든다.
  - `BB/room/RoomService.java:62-86`: 방마다 Redis 키 3개와 `game_rooms` 행을 만든다.
  - `BB/retention/RetentionService.java:17`: 보존 배치는 `users`를 지우지 않는다.
  - `deploy/docker-compose.yml:26`: Redis는 `--appendonly yes`만 있고 `--maxmemory`가 없다. 서비스 전체에 `mem_limit`·`cpus`·로그 회전 설정이 없다.
- **장애·악용 시나리오(수치는 추정)**
  - 한 IP가 `/auth/guest`를 초당 수백 번 부르면 시간당 사용자 행과 Refresh 키가 수십만~백만 개 쌓인다. Redis는 시간당 수백 MB씩 커진다.
  - 4GB 서버 한 대에 JVM(힙 768MB)·MySQL·Redis·AI·nginx가 함께 있다. Redis가 상한 없이 커지면 swap(2GB)을 거쳐 OOM killer가 엉뚱한 프로세스를 죽인다. 그 순간 진행 중인 친구 대전이 모두 끊긴다.
  - 같은 닉네임으로 10,000번 만들면 그 닉네임의 태그가 소진된다(`GuestAccountService.java:52-54`, `NICKNAME_TAG_EXHAUSTED`). 그 뒤로는 누구도 그 닉네임을 못 쓴다. 상위 랭커와 같은 닉네임에 다른 태그를 붙여 사칭할 수도 있다.
  - H-1과 결합하면 랭킹 도배, M-1과 결합하면 AI CPU 고갈로 이어진다.
- **고치는 방향**
  - nginx에 `limit_req_zone $binary_remote_addr`를 둔다. `/api/v1/auth/`(예: 분당 10회), `/api/v1/rooms`·`/api/v1/matches` 생성, `/ai/`를 따로 제한한다. `/ws`에는 `limit_conn`을 둔다.
  - Redis에 `--maxmemory 256mb --maxmemory-policy volatile-lru`를 둔다. 다만 `ranking:solo`·`ranking:solo:seq`·`presence:*`에는 TTL이 없어서, volatile-lru에서는 이 키들이 메모리가 차도 밀려나지 않는다(`RedisKeys.java:12-18`). (2026-11-22 정정 — 처음 판의 TTL 설명이 틀렸다.)
  - 서비스별 `mem_limit`·`cpus`, json-file 로그 회전(`max-size: 10m`, `max-file: "3"`)을 둔다.
  - 오래된 게스트 계정 정리 배치를 둘지 검토한다.
- **규모**: 작음(nginx·compose)~중간(정리 배치)

---

## 3. 중간

### M-1. AI 서버 CPU 고갈 — 부하(+보안)

- **근거(구조는 코드 확인)**
  - `ai/Dockerfile:17`: `--workers 1`
  - `ai/app/main.py:73`: 동기 `def move`라 스레드풀에서 돌지만, GIL 때문에 사실상 1코어만 쓴다.
  - `ai/app/search.py:120-157`: HARD는 홀드를 포함해 BFS를 최대 2회 돌리고, 빔 10개마다 1회씩 더 돈다. 시간 기한이나 중간 중단이 없다.
  - `frontend/src/ai/aiClient.ts:52-58`: 클라이언트는 150ms에 abort하지만 서버는 계산을 끝까지 한다.
  - 인증은 서명과 exp만 본다(`ai/app/main.py:54-65`). 게스트 토큰은 누구나 받는다.
  - `/ai/`에는 `limit_req`가 없고(`deploy/nginx.conf.template:56-60`), ai 컨테이너에 `cpus` 제한도 없다.
- **시나리오(추정)**
  - AI 대전이 동시에 수십 판이면, 블록마다 요청이 와서 초당 수십~100건이 된다. 요청 하나가 수십 ms를 쓰면 1코어가 포화된다.
  - 그러면 모든 요청이 150ms를 넘겨 FE 규칙 AI로 떨어진다. 그런데 서버는 버려진 계산을 계속한다. 자기 증폭이다.
  - 같은 2 vCPU를 나눠 쓰는 JVM의 친구 대전 lock 지연까지 오른다.
  - vs AI 모드 자체는 FE 대체 AI 덕분에 유지된다(NFR-05).
- **고치는 방향**
  - `search.choose`에 기한(예: 120ms)을 두고, 넘으면 그때까지의 최선을 돌려준다.
  - 세마포어나 uvicorn `--limit-concurrency`로 동시 계산 수를 제한하고, 넘치면 즉시 503을 준다.
  - nginx `/ai/`에 `limit_req`를 둔다(H-3과 같이). compose에서 ai에 `cpus: "1"`을 건다.
  - FE에서 연속 시간 초과가 N회면 서버 호출을 잠시 멈춘다(회로 차단).
- **규모**: 중간

### M-2. STOMP 하트비트가 꺼져 있음 — 부하

- **근거(코드 확인)**
  - `BB/realtime/WebSocketConfig.java:35`: `enableSimpleBroker`에 `setHeartbeatValue`·`setTaskScheduler`가 없다.
  - `frontend/src/realtime/stomp.ts:110`: 클라이언트 CONNECT 헤더가 `'heart-beat': '0,0'`이다.
  - `deploy/nginx.conf.template:52-53`: WebSocket 프록시 타임아웃이 3600s다.
- **시나리오**
  - 모바일 망처럼 TCP close 없이 끊기면 `SessionDisconnectEvent`가 늦게 온다(추정, 최대 nginx 1시간 또는 TCP 재전송 한계). 그동안 10초 유예·DISCONNECT 판정(`BB/battle/ConnectionEvents.java:93-110`)이 시작되지 않는다.
  - 끊긴 쪽은 topout을 보내지 않는다. 그래서 살아 있는 쪽이 먼저 topout하면 `BB/battle/LockService.java:92-101` `decideTopout`에 따라 상대 topout 기록이 없으니 **살아 있는 쪽이 진다**(코드 확인).
  - 죽은 소켓으로 계속 방송하면 outbound 4스레드 중 하나가 송신 대기(기본 10초)에 묶일 수 있다(추정).
- **고치는 방향**
  - 서버 하트비트를 10초·10초로 켜고 `TaskScheduler`를 붙인다. 클라이언트도 `10000,10000`으로 맞춘다.
  - nginx `proxy_read_timeout`을 하트비트보다 조금 긴 값(예: 60초)으로 줄인다.
  - 재연결은 지수 백오프와 지터로 바꾼다(L-12).
- **규모**: 작음

### M-3. inbound 4스레드를 모든 방이 같이 씀 — 부하

- **근거**
  - `BB/realtime/WebSocketConfig.java:41-43`: inbound에 executor를 지정하지 않았다. 시험 로그 `loadtest/results/20261113-162951/backend.log`에 `inboundChannel[pool size = 4 ...]`가 찍혀 있다.
  - lock 한 번에 Redis를 11번 동기로 왕복한다. 공격이 나가면 14번이다(코드로 셈).
    - 인터셉터 참가자 확인 HGETALL(`StompAuthInterceptor.java:68`)
    - `getRoom`·`getPlayers`(`LockService.java:105,109`)
    - 상태 load와 save(HMSET + EXPIRE)
    - `rooms.touch` 5번(`BB/store/redis/RoomStateStore.java:126-132`)
  - 같은 스레드에서 DB도 부른다: 준비 완료 시 라운드 시작 INSERT 3개 + UPDATE 1개(`BB/room/RoundStarter.java:37-45`), 경기별 `synchronized` 안의 `seedOf` SELECT(`LockService.java:236-237`). Hikari는 기본 10개다.
- **시나리오(추정)**
  - 정상 상태의 시험 p95는 16~18ms라 여유가 있다.
  - 하지만 Redis가 멈칫하거나(AOF fsync, rewrite fork), H-3 남용으로 Hikari가 고갈되면 4스레드가 모두 대기에 걸린다. 그러면 100방 전체의 방송이 함께 멈춘다. 한 방의 지연이 모든 방으로 번지는 구조다.
- **고치는 방향**
  - lock 처리 Redis 호출을 Lua 하나나 파이프라인으로 묶는다. `touch`는 N초에 한 번만 한다.
  - 참가 방을 세션 속성에 캐시해 인터셉터의 HGETALL을 없앤다(H-2 수정과 같이).
  - inbound executor 크기와 큐 상한을 명시한다. 라운드 시작 DB 작업은 별도 executor로 넘긴다.
- **규모**: 중간

### M-4. 부하 시험 환경이 배포와 달라 NFR-02 여유가 과대평가됨 — 부하

| 항목 | 시험(`loadtest/docker-compose.yml`) | 배포(`deploy/`) |
|---|---|---|
| CPU 2개 제한 | 백엔드만(`:43-44`). MySQL·Redis·k6는 호스트 22코어를 씀 | 백엔드·MySQL·Redis·AI·nginx(TLS)가 2 vCPU를 나눠 씀 |
| JVM 힙 | `MaxRAMPercentage=75`(약 3GB, `:34`) | `-Xmx768m`(`backend/Dockerfile:5`) |
| MySQL | tmpfs(`:16`) | 디스크, 버퍼 풀 256M |
| 경로 | nginx·TLS 없음 | nginx·TLS 경유 |

- **결과 수치**(`loadtest/results/20261113-162951/report.md`): 100방·200명, 600초 유지. lock → 상대 BOARD p95 18.0ms, 오류 0.00%. CPU는 백엔드 52.2%, 세 컨테이너 합계 68.6%. lock은 플레이어당 초당 1.80회로 목표 2회보다 낮았다.
- **시나리오(추정)**: 실제 서버에서는 68.6%에 TLS(WebSocket 200개)와 AI가 더해진다. lock 빈도를 목표대로 맞추면 70%를 넘을 가능성이 크다.
- **빠진 측정**: EC2 실측, nginx·TLS 경유, AI 동시 부하, REST 혼합(로그인·랭킹·기록), 재접속 폭주·배포 중 재시작, 반쯤 열린 클라이언트, 장시간 soak(L-8 누수), 남용(H-3), Redis·MySQL 지연 주입, 배포 힙 768MB.
- **고치는 방향**: §6 실측 제안대로 EC2에서 다시 잰다.
- **규모**: 작음(측정)

### M-5. 친구 대전 lock 경로 미검사와 T-spin 자기 신고 — 보안(공정성)

- **근거(코드 확인)**
  - `BB/battle/LockService.java:189-194`: 겹치지 않는지와 바닥에 닿았는지만 본다. 주석(`:23-26`)도 경로를 검사하지 않는다고 적고 있다.
  - `"ROTATE".equals(msg.lastMove())`를 그대로 믿는다. `BB/engine/LockRules.java:34-39`의 T-spin 조건은 `lastActionRotate && 모서리 3칸`이다.
  - lock 빈도에도 제한이 없다.
- **시나리오**
  - 위가 막힌 구멍처럼 도달할 수 없는 빈칸에 블록을 "순간이동"해 놓을 수 있다.
  - T 블록을 모서리 3칸이 막힌 자리에 놓고 `lastMove=ROTATE`라고 보내면 T-spin 공격 줄을 받는다.
  - 봇은 지연 없이 연속으로 lock할 수 있다.
  - 블록 종류·순서·지운 줄 수·점수·공격 줄 수는 서버가 다시 계산하므로 위조할 수 없다.
- **고치는 방향**
  - 생성 위치에서 목표 위치까지 BFS로 도달 가능한지 검사한다. AI 서버의 BFS(`ai/app/search.py`)와 같은 방식이다.
  - T-spin은 "회전으로만 도달 가능한 위치인가"를 서버가 판정한다.
  - lock 사이 최소 간격 또는 경기 시간 대비 블록 수 상한을 둔다.
- **규모**: 중간

### M-6. 카카오 연결 API에 state 대조 장치가 없음 — 보안

- **근거**
  - `BB/auth/AuthController.java:67-80`: 요청 본문이 `authorizationCode`, `redirectUri`뿐이다.
  - `BB/auth/kakao/HttpKakaoClient.java:31-41`
  - 프론트에 카카오 코드가 아직 없다(README "알려진 한계").
- **시나리오(추정, 미구현 부분)**: 콜백 화면을 만들 때 state 대조를 빠뜨리면 다음 공격이 된다.
  1. 공격자가 자기 카카오 인가 코드가 든 콜백 URL을 피해자에게 열게 한다.
  2. 피해자의 게스트 계정에 공격자의 카카오가 연결된다(`/kakao/link`).
  3. 공격자가 `/kakao/login`으로 피해자 계정에 로그인한다.
- **고치는 방향**: 인가를 시작할 때 서버가 무작위 state를 발급·저장하고, link·login에서 대조한다. 프론트에만 맡기지 않고 API 계약에 넣는다. 카카오를 켜기 전에 L-9(트랜잭션 안 외부 호출·타임아웃 없음)도 같이 고친다.
- **규모**: 작음~중간

---

## 4. 낮음

| ID | 종류 | 제목 | 근거 | 시나리오 | 고치는 방향 | 규모 | 확인 |
|---|---|---|---|---|---|---|---|
| L-1 | 보안 | nginx 보안 헤더 없음(HSTS·CSP·X-Frame-Options·nosniff·Referrer-Policy), `server_tokens off` 없음 | `deploy/nginx.conf.template:23-73` | 첫 http 접속에서 SSL strip, 게임 화면 iframe 클릭재킹. XSS 지점이 없어 CSP는 다층 방어 | 443 server에 헤더 추가. `/assets/`(`:64-65`)·`/`(`:70-71`)에 이미 `add_header`가 있어 상속이 끊기므로 include 파일로 각 location에도 넣음 | 작음 | 코드 확인 |
| L-2 | 보안 | 컨테이너가 root로 실행됨 | `backend/Dockerfile:1-7`, `ai/Dockerfile:9-17`(`USER` 없음) | 앱 RCE 때 컨테이너 root가 되고, 같은 망의 비밀번호 없는 Redis에 바로 접근 | 비root 사용자와 `USER` 지정 | 작음 | 코드 확인 |
| L-3 | 보안 | JWT 기본 키가 저장소에 있고, AI와 HS256 대칭키를 공유함 | `backend/src/main/resources/application.yml:21`, `ai/app/main.py:16`(같은 공개 기본값), `deploy/docker-compose.yml:43`(백엔드 `:?` 있음), `:56`(ai `:?` 없음) | compose 밖에서 키 없이 띄우면 누구나 토큰을 위조. AI 서버가 털리면 모든 사용자 토큰 발급 가능 | 기본값 제거와 기동 검증, ai에도 `:?`. `aud` 클레임 추가 또는 비대칭키 | 작음(비대칭은 중간) | 코드 확인 |
| L-4 | 보안 | Refresh 재사용 탐지 없음 | `BB/auth/RefreshTokens.java:38-45`, `BB/store/redis/RefreshTokenStore.java:27-42` | 탈취한 토큰을 공격자가 먼저 쓰면 피해자만 만료되고 공격자 세션은 유지됨 | 직전 해시를 보관하고, 옛 토큰이 오면 `refresh:{userId}` 전체 폐기 | 작음 | 코드 확인 |
| L-5 | 보안 | Access 필터가 디코딩 전 URI로 대상을 판단함 | `BB/auth/web/AccessTokenFilter.java:43-45`, `BB/room/RoomController.java:53-56` | `/api/%761/rooms/ABC123`, `/api/v1;x/...`가 필터를 건너뛰고 컨트롤러에 닿으면 방 조회를 토큰 없이 할 수 있음. 나머지는 500과 ERROR 로그 오염 | "공개 경로만 예외"로 뒤집고, 정규화 경로로 판단, `;`·인코딩 문자 거부 | 작음 | 추정(실행 안 함) |
| L-6 | 보안 | vs AI 승패·AI 성적 자기 신고 | `BB/match/MatchService.java:77-83,103-106` | 시작 직후 `WIN`을 보내 `ai_wins`를 부풀림(개인 전적만) | H-1과 같은 시간·상한 검사 | 작음 | 코드 확인 |
| L-7 | 보안·부하 | WebSocket Origin `*`, CONNECT 때만 토큰 확인, 메시지 크기·빈도 제한 없음, 구독마다 RESYNC 예약 | `BB/realtime/WebSocketConfig.java:27-43`, `StompAuthInterceptor.java:56-58`, `BB/battle/ConnectionEvents.java:129-135` | 헤더 JWT라 CSWSH 영향은 작음. 토큰이 만료되거나 로그아웃해도 연결이 무기한 유지됨. 구독·lock을 대량으로 보내면 Redis·스케줄러 부하 증가 | Origin을 `PUBLIC_BASE_URL`로, `setMessageSizeLimit` 수 KB, 세션별 초당 메시지 상한, 토큰 exp가 지나면 끊기 | 작음 | 코드 확인 |
| L-8 | 부하 | JVM 맵이 정리되지 않음 | `BB/battle/LockService.java:51-52`(`matchLocks`·`seeds`), `BB/battle/MatchOutcomeService.java:50,155`, `BB/battle/ConnectionEvents.java:45,98` | 시험 속도(시간당 약 7,900경기)가 이어지면 하루 약 28MB. `-Xmx768m`에서 수 주 뒤 문제 | 경기 종료·방 닫힘 때 `remove` | 작음 | 누수는 코드 확인, 속도는 추정 |
| L-9 | 부하 | 카카오 호출이 트랜잭션 안에서 돌고 타임아웃이 없음 | `BB/auth/kakao/KakaoAccountService.java:31-33,45-47`, `HttpKakaoClient.java:20`(`RestClient.create()`) | 카카오를 켠 뒤 응답이 느려지면 Hikari 10개가 묶여 M-3 연쇄 정지. 지금은 키가 없어 호출 전에 실패 | 외부 호출을 트랜잭션 밖으로, 타임아웃 2~3초 | 작음 | 코드 확인 |
| L-10 | 부하 | 방 만료 잡이 30초마다 `status <> 'CLOSED'` 풀 스캔, 열린 방마다 HGETALL | `BB/room/RoomExpiryJob.java:16`, `BB/room/RoomRepository.java:36-38`, `V1__init_schema.sql:33-43`(status 인덱스 없음) | 1년 보존이라 행이 쌓이고, 남용으로 열린 방이 늘면 비례해 증가. @Scheduled가 브로커 스케줄러 2스레드를 공유할 가능성(추정) | `(status)` 인덱스, 스케줄러 풀 분리 | 작음 | 코드 확인, 스케줄러는 추정 |
| L-11 | 부하 | 서버 재시작 때 메모리 예약 작업이 사라짐 | `BB/battle/BattleScheduler.java:20` | 배포 순간 라운드 사이였던 방이 PLAYING인데 경기가 없는 상태로 30분 동안 멈춤 | 기동 때 Redis를 스캔해 멈춘 방 복구 | 중간 | 코드 확인 |
| L-12 | 부하 | STOMP 재연결이 1초 고정이고 지터가 없음 | `frontend/src/realtime/stomp.ts:162-169` | 배포·재시작 때 모든 클라이언트가 같은 박자로 재접속(200명이면 가벼운 편) | 지수 백오프(1→2→4…최대 10초) + 지터 | 작음 | 코드 확인 |
| L-13 | 부하 | 랭킹 캐시 복구가 동시에 여러 번 돌 수 있고 행마다 ZADD | `BB/ranking/SoloRankingCache.java:38-48` | Redis 데이터를 잃었을 때 기록 보유자 수만큼 왕복이 동시 요청 수만큼 반복됨 | 다건 ZADD·파이프라인, 한 번만 도는 잠금 | 작음 | 추정 |
| L-14 | 부하 | 기록 목록 page 상한 없음, 요청마다 COUNT | `BB/match/MatchHistoryController.java:37`, `BB/match/MatchHistoryService.java:53-70` | 사용자 경기 수에 비례. 지금 규모에서는 무해 | page 상한 또는 키셋 페이지 | 작음 | 코드 확인 |
| L-15 | 운영·부하 | 인증서 갱신을 standalone으로 하고(갱신 때 nginx 정지), 이메일 없이 등록함 | `deploy/server-setup.sh:40-51`, `deploy/nginx.conf.template:14-16`(webroot 경로가 있지만 안 씀) | 갱신 순간 진행 중인 대전이 모두 끊김. 갱신 실패 알림이 없고, 만료되면 Secure 쿠키라 서비스 전체가 멈춤 | `--webroot` + deploy-hook `nginx -s reload`, 이메일 등록 | 작음 | 코드 확인(타이머 존재는 추정) |
| L-16 | 보안 | 열거·감사: 경기 존재 여부 오라클, 보존 배치가 최고 점수 근거 경기까지 지움 | `BB/match/MatchHistoryService.java:93,106`(없음 vs 비참가자 응답이 다름), `BB/retention/RetentionService.java:41-69` | 남의 경기 ID 존재 확인. 1년 뒤 `solo_best_score`의 근거가 사라져 H-1류 조작을 감사할 수 없음. 방 코드는 SecureRandom 32^6이라 대입은 비현실적 | 비참가자에게도 404, 최고 점수 경기는 보존 대상에서 제외, 1회 삭제 상한 | 작음 | 코드 확인 |
| L-17 | 보안 | 로컬 infra compose가 DB·Redis를 0.0.0.0에 열고, 의존성·이미지 고정이 느슨함 | `infra/docker-compose.yml:11-12,22-23`, `ai/Dockerfile:12`(직접 의존성 3개만 고정), 기반 이미지 태그만 고정 | 공용망 개발 PC에서 외부 접근(운영 compose는 포트를 열지 않음). 빌드마다 전이 의존성이 바뀜 | `127.0.0.1:` 바인드, 운영 lock 파일, 이미지 digest, 배포 전 `npm audit`·`pip-audit` | 작음 | 코드 확인(취약 버전 여부는 미확인) |

---

## 5. 이미 막혀 있는 것 (중복 지적 방지)

| 대책 | 위치 | 덮지 못하는 부분 |
|---|---|---|
| Access 토큰은 메모리에만 둠. localStorage에는 연출 설정만 | `frontend/src/api/client.ts:4,41`, `frontend/src/game/settings.ts:49-61` | — |
| Refresh 쿠키 HttpOnly·Secure·SameSite=Strict·Path=/api/v1/auth. Redis에 SHA-256 해시만 저장, Lua 원자 회전, 로그아웃 때 폐기 | `BB/auth/RefreshCookie.java:25-27`, `BB/auth/RefreshTokens.java:33`, `BB/store/redis/RefreshTokenStore.java:27-49` | 재사용 탐지(L-4) |
| JWT HS256 고정, alg 바꿔치기·none 거부, 만료 1시간 | `BB/auth/jwt/JwtProvider.java:27-57` | 기본 키·대칭 공유(L-3) |
| XSS 지점 없음(`dangerouslySetInnerHTML`·`innerHTML`·`eval` 0건). 닉네임은 `^[가-힣A-Za-z0-9]{2,10}$`, 초대 경로는 `[A-Za-z0-9]{6}`만 | `BB/auth/GuestAccountService.java:27,43`, `frontend/src/realtime/room.ts:78` | — |
| STOMP CONNECT 때 JWT 필수. `/topic/rooms/{code}` 구독·`/app/rooms/{code}/*` 발행은 참가자만 | `BB/realtime/StompAuthInterceptor.java:31-32,56-75` | `/topic`·`/user` SEND, `/queue/**` 구독(H-2), 만료 뒤 연결 유지(L-7) |
| 행위자는 항상 Principal(JWT sub). 남의 userId를 넣을 칸이 없음 | `BB/room/RoomMessageController.java:32-56`, `BB/room/RoomController.java:59-68` | — |
| 친구 대전 보드·블록 순서·지운 줄·점수·공격 줄·구멍 열은 서버가 재계산. topout은 서버 보드로 판정 | `BB/battle/LockService.java:81-83,114-120,155-224` | 경로·T-spin 신고(M-5) |
| 결과 중복 저장 방지(`FOR UPDATE` + PLAYING, 친구 대전은 조건부 UPDATE). 싱글·AI 결과는 주인만, FRIEND 결과 저장 거부 | `BB/match/MatchService.java:63-67`, `BB/battle/MatchOutcomeService.java:93-95` | 값 자체의 진위(H-1·L-6) |
| 경기 상세는 참가자 전용 | `BB/match/MatchHistoryService.java:105-107` | 존재 여부 오라클(L-16) |
| SQL은 모두 바인딩. 문자열 연결은 고정 컬럼 이름뿐. 랭킹 limit·기록 size ≤ 50 | `BB/user/UserRepository.java:96-105`, `BB/ranking/RankingController.java:53`, `BB/match/MatchHistoryController.java:37` | 기록 page 상한(L-14) |
| 방 코드 SecureRandom, 정원·상태는 Lua 원자 처리, 한 사람 한 방 SETNX, 경기 단위 `synchronized`(방끼리 경합 없음) | `BB/room/RoomCodeGenerator.java`, `BB/store/redis/RoomStateStore.java:36-51,113`, `LockService.java:112` | 요청 한도(H-3) |
| Redis 키 TTL: 방 30분, 경기 상태 2시간, 끊김 10초, refresh 7일 | `BB/store/redis/RedisKeys.java:12-18` | maxmemory 없음(H-3) |
| 보존 배치는 1,000건씩 끊어 처리(큰 DELETE 없음), 진행 중 경기·사용자는 제외 | `BB/retention/RetentionService.java:25,41-63` | 최고 점수 근거 삭제(L-16) |
| 예상 못 한 예외는 고정 문구 500. actuator·Spring Security 의존성 없음, 같은 출처라 CORS 설정 없음 | `BB/common/error/GlobalExceptionHandler.java:39-46`, `backend/build.gradle:23-36` | 우회 경로 500(L-5) |
| 운영 compose: MySQL·Redis·백엔드·AI 포트를 외부에 열지 않음(nginx 80·443만). `JWT_SECRET`·`PUBLIC_HOST`가 없으면 기동 거부. TLS 1.2/1.3 | `deploy/docker-compose.yml:42-43,61-63`, `deploy/nginx.conf.template:30` | ai 쪽 `:?` 없음(L-3), 한도 없음(H-3) |
| AI 인증을 본문 파싱 전에 검사, sub·exp 필수, `/docs`는 외부 비노출. 입력은 보드 22×10·0/1, next ≤ 5, 난이도 열거형 | `ai/app/main.py:24-40,54-65`, `deploy/nginx.conf.template:56` | 계산 기한·동시성(M-1) |
| AI가 실패하거나 150ms를 넘기면 FE 규칙 AI로 대체해 게임 유지. 백엔드는 AI를 부르지 않음 | `frontend/src/ai/aiClient.ts:41-80`, `frontend/src/ai/types.ts:11` | 서버는 버려진 계산을 계속함(M-1) |
| 운영 비밀은 `openssl rand -hex`로 만들고 umask 077, 출력하지 않음. deploy.sh는 `.env`·pem을 묶지 않음. swap 2GB | `deploy/server-setup.sh:16-35`, `deploy/deploy.sh:17-23` | — |
| 카카오: 이미 연결된 계정 재연결 거부, 같은 카카오 ID 중복은 UNIQUE로 거부 | `BB/auth/kakao/KakaoAccountService.java:32-52` | state 없음(M-6) |

## 6. 실측 제안 (실행하지 않음, 승인 필요)

배포된 EC2에 직접 부하를 주는 방법이므로 전진님 승인을 받은 뒤, 사용자가 없는 시간에 한다.

1. **NFR-02 재측정(M-4)**: `deploy/` compose 그대로 올린 EC2에, 다른 인스턴스의 k6로 같은 시나리오(100방·200명)를 돌린다. lock은 목표 2회로 맞추고, CPU는 node exporter로 잰다.
2. **지연 전파(M-3)**: 시험 중 `redis-cli DEBUG SLEEP 0.5`로 지연을 주입해 다른 방의 p95가 같이 오르는지 본다. 로컬 시험 환경에서만 한다.
3. **AI 포화(M-1)**: AI 동시 10·30·50판에서 `elapsedMs` 분포와 친구 대전 p95를 같이 본다.
4. **끊김 감지(M-2)**: 클라이언트 일부를 iptables DROP으로 끊고 DISCONNECT 판정까지 걸린 시간을 잰다. 로컬에서 한다.
5. **soak(L-8)**: 1시간 이상 돌리며 힙 추세를 본다.
6. **H-1·H-2 회귀**: 고친 뒤 로컬에서 위조 결과 제출과 `/topic` SEND·`/queue/**` SUBSCRIBE가 거부되는지 시험으로 고정한다. 운영 서버에는 공격성 요청을 보내지 않는다.

## 7. 우선순위 제안

1. **H-1 점수 상한 보강**과 **H-2 STOMP 허용 목록**: 각각 작은 수정이고, 게임의 공정성과 NFR-04를 바로 회복한다.
2. **H-3 nginx 한도 + Redis maxmemory + mem_limit**: 설정 파일 두 개를 고치면 되고, M-1의 `/ai/` 한도도 같이 들어간다.
3. **M-2 하트비트**: 설정 몇 줄이다. "끊긴 쪽이 이기는" 판정 오류를 막는다.
4. M-1(AI 계산 기한), M-3(Redis 왕복 묶기), M-4(EC2 재측정) 순서.
5. M-5·M-6은 카카오와 경쟁 모드를 공개 운영하기 전에 고친다. 낮음 항목은 묶음으로 처리한다.
