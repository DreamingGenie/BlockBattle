# 테트리스 대전 게임 — API 명세서

Nov 10, 2026 · @ocean

REST는 `/api/v1`, 실시간 대전은 STOMP over WebSocket(`/ws`), AI는 `/ai/v1` 아래에 둔다. 모든 요청은 게스트 로그인으로 받은 JWT로 인증한다.

## 1. 공통 규칙

| 항목 | 내용 |
| --- | --- |
| REST Base URL | `https://{host}/api/v1` |
| WebSocket | `wss://{host}/ws` (STOMP 1.2) |
| AI Base URL | `https://{host}/ai/v1` |
| 인증 | REST: `Authorization: Bearer {accessToken}` 헤더, STOMP: CONNECT 프레임의 같은 헤더 |
| 형식 | JSON, UTF-8, 시각은 ISO-8601(REST) 또는 epoch ms(WebSocket) |

공통 응답 형식:

```json
{ "success": true, "data": { }, "error": null }
{ "success": false, "data": null, "error": { "code": "ROOM_FULL", "message": "방이 가득 찼습니다." } }
```

## 2. REST API 목록

| 메서드 | URI | 설명 | 인증 | 요구사항 |
| --- | --- | --- | --- | --- |
| POST | /auth/guest | 게스트 로그인 | X | FR-USR-01 |
| POST | /auth/refresh | 토큰 재발급 | Refresh | FR-USR-04 |
| POST | /auth/logout | 로그아웃 (Refresh 삭제) | O | FR-USR-04 |
| GET | /users/me | 내 정보 · 전적 요약 | O | FR-USR-03 |
| GET | /users/me/matches?mode=&page=&size= | 내 최근 경기 | O | FR-REC-01 |
| POST | /matches | 싱글 · vs AI 경기 시작 (matchId, 시드 발급) | O | FR-SOL-01, FR-AI-04 |
| POST | /matches/{matchId}/result | 싱글 · vs AI 결과 저장 | O | FR-SOL-02, FR-AI-05 |
| GET | /matches/{matchId} | 경기 상세 (결과 화면) | O | FR-REC-02 |
| GET | /rankings/solo?limit=10 | 싱글 랭킹 TOP N + 내 순위 | O | FR-SOL-03 |
| POST | /rooms | 방 생성 (게임 코드 · 초대 URL) | O | FR-ROM-01 |
| GET | /rooms/{roomCode} | 방 조회 (입장 전 확인) | O | FR-ROM-02 |
| POST | /rooms/{roomCode}/join | 방 입장 | O | FR-ROM-02 |
| POST | /rooms/{roomCode}/leave | 방 퇴장 (방장이면 방 삭제) | O | FR-ROM-03 |

AI 서버

| 메서드 | URI | 설명 | 인증 | 요구사항 |
| --- | --- | --- | --- | --- |
| POST | /ai/v1/move | 다음 수 계산 | O | FR-AI-01, FR-AI-02 |
| GET | /ai/v1/health | 상태 확인 | X | - |

## 3. REST 상세

### POST /auth/guest — 게스트 로그인

```json
// Request
{ "nickname": "오션" }
// Response 201
{ "userId": 12, "nickname": "오션", "tag": "0421",
  "accessToken": "eyJ...", "refreshToken": "eyJ...", "expiresIn": 3600 }
```

오류: `INVALID_NICKNAME`(400) — 2\~10자, 한글 · 영문 · 숫자가 아닐 때

### POST /rooms — 방 생성

```json
// Request
{ "roundCount": 1 }
// Response 201
{ "roomCode": "K7Q2MX", "inviteUrl": "https://{host}/room/K7Q2MX",
  "status": "WAITING", "expiresAt": "2026-11-10T16:30:00+09:00" }
```

### POST /rooms/{roomCode}/join — 방 입장

```json
// Response 200
{ "roomCode": "K7Q2MX", "status": "WAITING", "hostUserId": 12,
  "players": [
    { "userId": 12, "nickname": "오션", "tag": "0421", "ready": false },
    { "userId": 31, "nickname": "민수", "tag": "1187", "ready": false } ] }
```

오류: `ROOM_NOT_FOUND`(404), `ROOM_FULL`(409), `ROOM_ALREADY_PLAYING`(409)

### POST /matches — 싱글 · vs AI 경기 시작

친구 대전 경기는 서버가 COUNTDOWN 시점에 만들므로 이 API를 쓰지 않는다.

```json
// Request
{ "mode": "AI", "aiDifficulty": "HARD" }   // SOLO면 aiDifficulty 생략
// Response 201
{ "matchId": 381, "seed": 918273645 }
```

### POST /matches/{matchId}/result — 결과 저장

```json
// Request
{ "result": "WIN", "durationMs": 184000,
  "player": { "score": 45200, "linesCleared": 62, "level": 7, "piecesPlaced": 171,
              "attackSent": 38, "attackReceived": 21, "maxCombo": 5 },
  "ai":     { "score": 30100, "linesCleared": 48, "level": 5, "piecesPlaced": 160,
              "attackSent": 21, "attackReceived": 38, "maxCombo": 3 } }   // SOLO면 result·ai 생략
// Response 200
{ "matchId": 381, "newBest": false, "soloRank": null }
```

오류: `MATCH_NOT_FOUND`(404), `MATCH_ALREADY_FINISHED`(409)

### GET /rankings/solo — 랭킹

```json
// Response 200
{ "top": [ { "rank": 1, "nickname": "오션", "tag": "0421", "score": 98000 } ],
  "me": { "rank": 37, "score": 45200 } }
```

### POST /ai/v1/move — 다음 수 계산

보드는 맨 윗줄부터 20행 × 10열, 0은 빈칸 1은 채워진 칸이다. FE는 `inputs`를 난이도별 속도로 재생해 AI가 사람처럼 움직이게 한다.

```json
// Request
{ "difficulty": "HARD", "board": [[0,0,0,0,0,0,0,0,0,0], "... 20행"],
  "current": "T", "hold": null, "canHold": true,
  "next": ["I","O","S","Z","L"], "pendingGarbage": 2 }
// Response 200
{ "useHold": false, "piece": "T", "rotation": 2, "x": 4,
  "inputs": ["CW","CW","RIGHT","HARD_DROP"], "tSpin": false, "elapsedMs": 12 }
```

`rotation`은 SRS 상태 0 · R · 2 · L을 0\~3으로, `x`는 블록 기준점의 열(왼쪽 0)이다. 오류: `INVALID_BOARD`(400), 응답이 150ms를 넘으면 FE가 폴백한다.

## 4. WebSocket (STOMP) 명세

클라이언트 → 서버는 `/app`, 방 전체 방송은 `/topic`, 개인 메시지는 `/user/queue`를 쓴다. 방에 참가하지 않은 사용자의 구독 · 발행은 서버가 막는다.

모든 서버 메시지는 같은 봉투를 쓴다:

```json
{ "type": "ATTACK", "roomCode": "K7Q2MX", "sentAt": 1794293000000, "payload": { } }
```

### 클라이언트 → 서버 (SEND)

| Destination | 설명 | payload |
| --- | --- | --- |
| /app/rooms/{code}/ready | 준비 · 취소 | `{ "ready": true }` |
| /app/rooms/{code}/lock | 블록 고정 | `{ "seq": 57, "piece": "T", "rotation": 2, "x": 4, "y": 18, "tSpin": "FULL", "linesCleared": 2, "combo": 1, "b2b": true, "board": "200자 문자열", "score": 12400 }` |
| /app/rooms/{code}/topout | 게임오버 | `{ "seq": 120 }` |
| /app/rooms/{code}/rematch | 재대결 수락 (Should) | `{ "accept": true }` |

`board` 문자열은 10 × 20 = 200자, 윗줄부터 왼쪽에서 오른쪽 순서다. `0`은 빈칸, `I O T S Z J L`은 블록, `G`는 방해 줄이다. `seq`는 플레이어별로 1씩 늘어 중복 · 순서 오류를 걸러낸다.

### 서버 → 클라이언트 (`/topic/rooms/{code}`)

| type | 언제 | payload |
| --- | --- | --- |
| PLAYER\_JOINED | 입장 | `{ "userId": 31, "nickname": "민수", "tag": "1187" }` |
| PLAYER\_LEFT | 퇴장 · 연결 끊김 | `{ "userId": 31, "reason": "LEFT" }` |
| READY\_CHANGED | 준비 상태 변경 | `{ "userId": 31, "ready": true }` |
| COUNTDOWN | 둘 다 준비 | `{ "matchId": 402, "seed": 551203, "startsAt": 1794293003000 }` |
| BOARD | 상대 블록 고정 | `{ "userId": 31, "seq": 57, "board": "...", "score": 12400, "pendingGarbage": 0 }` |
| ATTACK | 상쇄 후 남은 공격 | `{ "fromUserId": 12, "toUserId": 31, "lines": 3, "holeColumns": [4, 4, 7] }` |
| GAME\_OVER | 승패 확정 | `{ "matchId": 402, "winnerUserId": 12, "reason": "TOP_OUT", "stats": [ { "userId": 12, "attackSent": 38, "apm": 12.4 } ] }` |
| ROOM\_CLOSED | 방 삭제 | `{ "reason": "HOST_LEFT" }` |

`/user/queue/errors`로는 `{ "code": "INVALID_LOCK", "message": "..." }` 형태의 개인 오류가 온다. GAME\_OVER의 `reason`은 TOP\_OUT, DISCONNECT, LEFT 중 하나다.

### 친구 대전 한 판의 흐름

1. 두 클라이언트가 REST로 입장한 뒤 STOMP CONNECT, `/topic/rooms/{code}`를 구독한다.
2. 각자 `ready`를 보내고, 둘 다 준비되면 서버가 matches 행을 만들고 COUNTDOWN(시드 포함)을 보낸다.
3. `startsAt`에 두 클라이언트가 같은 시드로 시작하고, 블록이 고정될 때마다 `lock`을 보낸다.
4. 서버는 줄 삭제 · 공격량을 다시 계산해 BOARD를 방송하고, 상쇄 후 남은 공격이 있으면 ATTACK을 보낸다.
5. `topout`이 오면 서버가 승패를 확정해 GAME\_OVER를 보내고 MySQL에 결과를 저장한다.

## 5. 에러 코드

| 코드 | HTTP | 설명 |
| --- | --- | --- |
| INVALID\_NICKNAME | 400 | 닉네임 형식 오류 |
| INVALID\_BOARD | 400 | AI 요청의 보드 형식 오류 |
| UNAUTHORIZED | 401 | 토큰 없음 · 위조 |
| TOKEN\_EXPIRED | 401 | Access Token 만료 |
| NOT\_ROOM\_MEMBER | 403 | 참가하지 않은 방에 접근 |
| ROOM\_NOT\_FOUND | 404 | 없거나 만료된 게임 코드 |
| MATCH\_NOT\_FOUND | 404 | 없는 경기 |
| ROOM\_FULL | 409 | 정원(2명) 초과 |
| ROOM\_ALREADY\_PLAYING | 409 | 이미 게임 중인 방 |
| MATCH\_ALREADY\_FINISHED | 409 | 이미 저장된 경기 |
| INVALID\_LOCK | - (WS) | 서버 재계산과 맞지 않는 lock |
| INTERNAL\_ERROR | 500 | 서버 오류 |

