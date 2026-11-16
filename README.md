# beez-tetris

BEEZ MCP로 작업 절차(설계 확인 → 계획 → 검증 → 시작 → 완료 보고)를 직접 따라가며 만드는 블록 낙하 퍼즐 게임 실습 저장소.

싱글(랭킹), vs AI(EASY·NORMAL·HARD), 친구 대전(방 코드·초대 링크, 1판·3판 2선승, 재대결)을 브라우저에서 플레이한다.

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

환경 변수(선택): `JWT_SECRET`(백엔드·AI가 같은 값), `PUBLIC_BASE_URL`(초대 링크 주소, 기본 `http://localhost:5173`), 프론트 프록시 대상 `BACKEND_URL`·`AI_URL`.

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

## 알려진 한계

- 카카오 계정 연결(Should)은 화면이 없다(카카오 앱 키 필요).
