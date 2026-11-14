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
