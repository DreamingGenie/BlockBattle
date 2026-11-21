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

## 배포와 운영

- [docs/deploy.md](docs/deploy.md) — AWS EC2 1대에 올리는 절차, 공지 띠, 요청 한도·상한
- [docs/operations.md](docs/operations.md) — 백업·복원, 서버 이전·종료, 다른 서버의 하위 경로에 임시로 올리기

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
