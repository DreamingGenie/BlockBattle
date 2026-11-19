# 블록 배틀 (BlockBattle)

브라우저에서 하는 블록 낙하 퍼즐 대전 게임. 싱글(랭킹), vs AI(VERY EASY~IMPOSSIBLE), 친구 대전(방 코드·초대 링크, 최대 4명, 2명이면 1판·3판 2선승, 재대결)을 플레이한다. 친구 대전은 서버가 lock마다 블록·위치·줄 수를 다시 계산해 검증하고, vs AI는 AI 서버가 다음 수를 두며 응답이 늦거나 실패하면 화면의 규칙 AI가 대신 둔다.

지금은 운영 서버 없이 로컬 실행과 시험으로 이어 간다. 서버를 다시 올리는 절차는 아래 "배포"와 "서버 이전·종료"에 있다.

## 배경

2026-11-10 ~ 11-17에 BEEZ(AI 에이전트의 작업 계획을 승인 설계와 대조해 검증하는 협업 서비스)를 실증하려고 만든 저장소다. 코드는 AI 에이전트 세션이 BEEZ MCP 절차(설계 확인 → 계획 → 검증 → 시작 → 완료 보고)를 따라 썼고, AWS EC2 한 대에 배포해 운영했다.

2026-11-19 팀 org 저장소 `S15A108/BlockBattle`에서 이력을 그대로 이 개인 저장소로 옮겼고, 이후로는 BEEZ 없이 독립 프로젝트로 운용한다. 같은 날 AWS 운영 서버는 서버 이용료 정산을 위해 내렸다. 로컬 폴더 이름 `beez-tetris`는 그때의 이름이다.

실증 기간의 기록(실행 지시서·지표·일지, 설계 문서 원본, 부하·보안 점검)은 `docs/`에 그대로 보관한다. 안내는 `docs/README.md` 맨 위.

| 폴더 | 내용 | 스택 |
| --- | --- | --- |
| `backend` | REST·STOMP 서버(인증, 경기 저장, 방·대전 서버 검증, 보존 배치) | Java 21 · Spring Boot 3.5 · MySQL 8.4 · Redis 7.4 |
| `ai` | AI 다음 수 서버(`/ai/v1`) | Python 3.12 · FastAPI |
| `frontend` | 브라우저 화면과 게임 엔진·규칙 AI | React 19 · TypeScript · Vite · Canvas |
| `shared/rules-fixtures` | FE·BE·AI가 함께 쓰는 규칙 시험 데이터 | JSON |
| `infra` | 개발용 MySQL·Redis | Docker Compose |
| `loadtest` | 동시 100방 부하 시험 | k6 |

## 로컬에서 실행

필요한 것: Docker, JDK 21, Python 3.12, Node.js 22 이상.

**1. MySQL·Redis**

```bash
docker compose -f infra/docker-compose.yml up -d
```

이미 로컬 MySQL이 3306을 쓰고 있으면 다른 포트로 띄우고, 3단계의 `DB_URL`도 같은 포트로 바꾼다.

```bash
MYSQL_PORT=3307 docker compose -f infra/docker-compose.yml up -d
```

**2. AI 서버 (포트 8000)**

```bash
cd ai && python -m venv .venv && .venv/Scripts/pip install -r requirements.txt
```

```bash
cd ai && .venv/Scripts/uvicorn app.main:app --port 8000
```

macOS·Linux는 `.venv/Scripts/` 대신 `.venv/bin/`이다.

**3. 백엔드 (포트 8080)** — 시작할 때 Flyway가 스키마를 만든다.

```bash
cd backend && ./gradlew bootRun
```

MySQL을 3307로 띄웠다면:

```bash
cd backend && DB_URL="jdbc:mysql://localhost:3307/block_battle?serverTimezone=UTC&characterEncoding=UTF-8" ./gradlew bootRun
```

**4. 프론트 (포트 5173)** — 개발 서버가 `/api`·`/ws`를 백엔드로, `/ai`를 AI 서버로 프록시한다.

```bash
cd frontend && npm install && npm run dev
```

브라우저에서 http://localhost:5173 을 연다. 친구 대전은 방장이 받은 초대 링크(`http://localhost:5173/room/{코드}`)나 코드로 다른 사용자가 들어온다. 같은 브라우저로 두 사람을 시험하려면 시크릿 창처럼 쿠키가 따로인 창을 쓴다.

환경 변수(선택): `JWT_SECRET`(백엔드·AI가 같은 값), `PUBLIC_BASE_URL`(초대 링크 주소, 기본 `http://localhost:5173`), 프론트 프록시 대상 `BACKEND_URL`·`AI_URL`, AI 동시 계산 상한 `AI_MAX_CONCURRENT`(기본 2, 넘으면 503 `AI_BUSY`). `JWT_SECRET`이 없으면 저장소에 공개된 개발용 키를 쓰는데, 백엔드는 `PUBLIC_BASE_URL`이 localhost가 아니면 이 키로 기동하지 않는다.

## 배포 (AWS EC2 1대)

기획서의 배포 구성대로 EC2 한 대에 Docker Compose로 MySQL·Redis·백엔드·AI·nginx를 올린다. 외부에는 nginx의 80·443만 열고, nginx가 프론트 정적 파일과 `/api`·`/ws`(WebSocket)·`/ai`를 같은 출처로 받는다. Refresh 쿠키가 `Secure`라 HTTPS가 필요해서, 도메인 없이 `{공인IP를 -로 이은 것}.sslip.io` 주소에 Let's Encrypt 인증서를 붙인다.

| 파일 | 내용 |
| --- | --- |
| `deploy/docker-compose.yml` | 운영 서비스 5개. 비밀값은 서버의 `deploy/.env`에서 읽는다 |
| `deploy/nginx.conf.template` | HTTPS·프록시·초대 링크(`/room/{code}`) 대체 |
| `deploy/server-setup.sh` | 서버 처음 한 번: Docker·certbot 설치, swap 2GB, `.env` 무작위 비밀값 생성, 인증서 발급·갱신 훅 |
| `deploy/deploy.sh` | 로컬에서 jar·프론트를 빌드해 서버로 올리고 기동 |
| `backend/Dockerfile`, `ai/Dockerfile` | 운영 이미지 |

**1. EC2 만들기** — Ubuntu 24.04, t3.medium(2 vCPU·4GB, NFR-02 기준 사양), 디스크 20GB. 보안 그룹은 22(내 IP)·80·443만 연다. 탄력적 IP를 붙여 두면 껐다 켜도 주소가 같다.

**2. 배포** — Git Bash에서 실행한다. 처음이면 서버 준비까지 한 번에 한다.

```bash
bash deploy/deploy.sh <공인 IP> <키 파일.pem>
```

끝나면 `https://<공인 IP의 .을 -로>.sslip.io`로 접속한다. 다시 배포할 때도 같은 명령을 쓴다(비밀값·인증서·DB 데이터는 그대로 남는다).

운영 비밀값(`JWT_SECRET`, DB 비밀번호)은 서버의 `deploy/.env`(권한 600)에만 있고 저장소에는 `deploy/.env.example`만 있다.

**공지 띠** — 서버 종료·점검을 알릴 때는 빌드할 때 `VITE_SERVICE_NOTICE`에 문구를 넣는다. 모든 화면 맨 위에 검은 띠로 빨간 글자가 오른쪽에서 왼쪽으로 흐른다. 값이 없으면(평소 `deploy.sh`) 띠가 나오지 않는다. 백엔드를 다시 띄우지 않고 프론트만 바꾸려면 빌드한 `frontend/dist`의 내용을 서버의 `~/block-battle/frontend/dist/`에 덮어쓴다(nginx가 이 폴더를 바로 읽는다). 이미 열려 있는 탭은 새로고침해야 보인다.

```bash
cd frontend && VITE_SERVICE_NOTICE="오늘 15:30에 서버 운영을 종료합니다" npm run build
```

**서버 이전·종료(백업과 복원)** — 서버를 내리거나 다른 서버로 옮길 때 쓴다.

1. 백업: `bash deploy/backup.sh <공인 IP> <키 파일.pem> <받을 폴더>`. nginx·backend·ai를 먼저 멈춰 받는 동안 새 기록이 생기지 않게 하고, MySQL 덤프·테이블별 행 수·Redis 스냅숏·`.env`를 받아 체크섬을 확인한다. **받은 폴더에는 회원 데이터와 운영 비밀값이 있으니 저장소 밖에 두고 커밋하지 않는다.**
2. 내리기: 서버에서 `cd ~/block-battle/deploy && sudo docker compose down`. 다시 열려면 `sudo docker compose start`(백업 직후)나 `up -d`.
3. 복원: 새 서버를 위 1번처럼 만든 뒤 `bash deploy/restore.sh <새 공인 IP> <키 파일.pem> <백업 폴더>`. 백업의 `.env`를 새 주소로 바꿔 올리고 → `deploy.sh`로 기동 → DB를 덤프로 덮어쓴 뒤 → 행 수를 백업 때와 대조한다. 빈 서버(`deploy/.env` 없음)에서만 돈다.

주소(sslip.io)가 바뀌면 브라우저 쿠키와 '이 기기에서 이어 하기'가 따라오지 않아 사용자는 다시 로그인한다. 회원·전적·랭킹은 DB에 있어 그대로다. Redis(방·Refresh 토큰)는 TTL이 있는 값이라 되살리지 않는다.

**하위 경로에 함께 올리기(BEEZ 서버 `/tetris`, 2026-11-19 ~ 11-20 임시)** — 다른 서비스가 쓰는 서버의 한 경로 아래에 올릴 때 쓴다. 프론트는 `VITE_BASE_PATH=/tetris/`로 빌드하면 `/api`·`/ws`·`/ai`·초대 링크가 모두 그 아래로 간다. 블록 배틀 컨테이너는 별도 compose 프로젝트로 `127.0.0.1:18080`(backend)·`18000`(ai)에만 열고, 호스트 nginx의 `dev.beez.ai.kr` server 블록에 include 한 줄(`deploy/beez-host/tetris-locations.conf`)과 요청 한도(`tetris-limits.conf`)만 더한다. Refresh 쿠키 경로는 nginx `proxy_cookie_path`로 `/tetris/api/`로 바꾼다.

```bash
bash deploy/beez-host.sh up ../_local-data/block-battle-backup-20261119
```

```bash
bash deploy/beez-host.sh down
```

`up`은 처음 한 번만 백업 DB를 되살리고(서버에 `deploy/.env`가 없을 때), 다시 실행하면 코드만 바꿔 올린다. `down`은 nginx 설정을 원래대로 되돌리고 컨테이너를 내리며 데이터 볼륨은 남긴다(`purge`는 볼륨까지 지움). 서버 접속은 `~/.ssh/config`의 `Host beez`를 쓴다(`BEEZ_SSH`로 바꿀 수 있음).

**요청 한도·상한** — 남용과 메모리 고갈에 대비해 아래 값을 둔다(보안 점검 H-3). IP 기준이라 같은 와이파이 뒤의 여러 사람이 함께 쓰므로, 시연처럼 한곳에서 많이 접속하면 `nginx.conf.template`의 값을 늘린다. 한도를 넘으면 nginx가 429로 답한다.

| 대상 | 값 |
| --- | --- |
| `POST /api/v1/auth/guest` | IP당 분당 30회, 순간 50회 |
| 방·경기 생성(`POST /api/v1/rooms`·`/matches`) | IP당 분당 60회, 순간 20회 |
| `/ai/` | IP당 초당 20회, 순간 40회(넘으면 화면이 규칙 AI로 대신 둔다) |
| `/ws` | IP당 동시 연결 60개 |
| 컨테이너 메모리 | mysql 768m · redis 384m · backend 1200m · ai 384m(CPU 1개) · nginx 128m, 로그는 서비스마다 10MB × 3개 |
| Redis | `maxmemory 256mb`, `volatile-lru`(오래 안 쓴 TTL 키부터 지움) |

Redis가 상한에 닿았는지는 서버에서 `docker compose -p block-battle exec redis redis-cli INFO stats | grep evicted_keys`로 본다. 0보다 크면 비정상적으로 키가 늘었다는 뜻이니 원인을 확인한다.

## 시험

```bash
cd backend && ./gradlew test
```

```bash
cd frontend && npm test && npm run typecheck
```

```bash
cd ai && .venv/Scripts/python -m pytest
```

백엔드 시험은 Testcontainers로 MySQL·Redis를 띄우므로 Docker가 켜져 있어야 한다. 부하 시험은 `loadtest/README.md`.

GitHub Actions(`.github/workflows/ci.yml`)가 `main`으로 가는 push·PR마다 위 세 가지와 프론트 빌드를 돌린다.

## 문서

| 파일 | 내용 |
| --- | --- |
| `docs/01-plan.md` ~ `04-api.md` | 기획서·요구사항(GR·BR·FR·NFR 번호)·ERD·API 명세. 코드 주석의 번호는 여기를 가리킨다 |
| `docs/security-load-review-1116.md` | 11-16 부하·보안 점검(H·M·L 번호) |
| `docs/run/`, `docs/original/`, `docs/README.md` | BEEZ 실증 기간 기록(고치지 않는다) |
| `shared/rules-fixtures/README.md` | 공용 규칙 시험 데이터와 설계에 없어 정한 값 |

Flyway 마이그레이션(`backend/src/main/resources/db/migration/`)은 이미 적용된 파일을 고치지 않는다. 체크섬이 바뀌면 기존 DB에서 기동이 실패하니, 스키마를 바꿀 때는 새 버전 파일을 더한다.

## 알려진 한계

- 카카오 계정 연결(Should)은 화면이 없다(카카오 앱 키 필요). 켤 때는 보안 점검 M-6(state 대조)·L-9(타임아웃)를 같이 고친다.
- 11-16 부하·보안 점검(`docs/security-load-review-1116.md`)에서 아직 열린 항목: M-3~M-6, L-1·L-2·L-4·L-5·L-7·L-9~L-16, L-3 중 AI와 HS256 키 공유(aud·비대칭키), L-17 중 의존성·이미지 고정. 서버를 다시 올리기 전에는 배포 쪽 L-1(nginx 보안 헤더)·L-2(비root 컨테이너)·L-15(인증서 갱신)를 먼저 본다.
- 봇 판정(`BotCheck`)은 클라이언트가 보낸 값으로 하는 1차 방어다. 바로 차단(초당 블록 7 초과, 키 입력 수 < 블록 수)과 점수제(초당 4 초과, 키 누름 평균 50ms 미만, 입력 값 없음 등 2개 이상)로 판정하고, 판정된 계정은 24시간 동안 싱글·vs AI 결과를 기록하지 않는다. 기준은 11-19 운영 데이터로 정했다(`app.bot.soft-enforce=false`로 점수제를 끌 수 있다). 새 게스트 계정으로 바꾸는 것은 막지 못하고, 판정 결과가 응답에 바로 보여 기준을 찾아보는 것은 여전히 가능하다.
- Refresh 토큰 재사용 탐지(L-4)는 넣지 않았다. 사용자당 Refresh 토큰이 하나뿐이고 HttpOnly 쿠키라 훔치기 어렵고, 탐지를 넣으면 두 탭이 동시에 재발급할 때 정상 사용자도 로그아웃되므로 유예 시간 설계가 함께 필요하다.
