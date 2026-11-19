# 테트리스 대전 게임 — API 명세서

개정 3판 · 2026-11-14 · 작성 오션(팀장) · 3판 변경: 5.2 lock 드롭 칸 수(POL-005), 5.7 RESYNC 시드·내 성적(POL-006), 6장 NOT_FOUND·METHOD_NOT_ALLOWED(POL-026)

## 1. 공통 규칙

| 항목 | 내용 |
| --- | --- |
| REST Base URL | https://{host}/api/v1 |
| WebSocket | wss://{host}/ws (STOMP 1.2) |
| AI Base URL | https://{host}/ai/v1 |
| 경로 표기 | 아래 표와 제목의 경로는 각 Base URL 뒤에 붙는 상대 경로다. 예: AI의 POST /move는 https://{host}/ai/v1/move |
| 인증 | Access JWT(HS256, sub = userId, 만료 1시간)를 REST는 Authorization: Bearer {accessToken} 헤더로, STOMP는 CONNECT 프레임의 같은 헤더로 보낸다. AI 서버도 같은 비밀 키로 검증한다 |
| 인증 예외 | POST /auth/guest, POST /auth/kakao/login, AI GET /health는 인증 없음. POST /auth/refresh는 Refresh 쿠키로 인증 |
| Refresh 쿠키 | 이름 refresh_token, HttpOnly, Secure, SameSite=Strict, Path=/api/v1/auth, 만료 7일 |
| 형식 | JSON, UTF-8. 시각은 REST는 ISO-8601(오프셋 포함), WebSocket은 epoch ms |

### 1.1 응답 봉투

REST 응답은 모두 같은 봉투로 감싼다. 성공이면 success = true, error = null이고, 실패면 success = false, data = null이다. 본문이 없는 성공(204)은 봉투도 없다.

```json
{ "success": true, "data": { "matchId": 381 }, "error": null }
{ "success": false, "data": null, "error": { "code": "ROOM_FULL", "message": "방이 가득 찼습니다." } }
```

아래 3장·4장의 응답 예시는 봉투의 data 안에 들어가는 내용만 적는다. AI 서버 응답도 같은 봉투를 쓴다.

## 2. API 목록

### 2.1 REST

| 메서드 | 경로 | 설명 | 인증 | 요구사항 |
| --- | --- | --- | --- | --- |
| POST | /auth/guest | 게스트 로그인 | 없음 | FR-USR-01 |
| POST | /auth/refresh | 토큰 재발급 | Refresh 쿠키 | FR-USR-01 |
| POST | /auth/logout | 로그아웃 | Access | FR-USR-01 |
| POST | /auth/kakao/link | 게스트 계정에 카카오 연결 (Should) | Access | FR-USR-02 |
| POST | /auth/kakao/login | 카카오 로그인으로 이어 받기 (Should) | 없음 | FR-USR-02 |
| GET | /users/me | 내 정보·전적 요약 | Access | FR-USR-03 |
| GET | /users/me/matches | 내 최근 경기 목록 | Access | FR-REC-01 |
| POST | /matches | 싱글·vs AI 경기 시작 | Access | FR-SOL-01, FR-AI-03 |
| POST | /matches/{matchId}/result | 싱글·vs AI 결과 저장 | Access | FR-SOL-01, FR-AI-03 |
| GET | /matches/{matchId} | 경기 상세 | Access | FR-REC-01 |
| GET | /rankings/solo | 싱글 랭킹 | Access | FR-SOL-02 |
| GET | /rankings/ai | vs AI 난이도별 최단 승리 시간 랭킹 | Access | T-35 |
| POST | /rooms | 방 생성 | Access | FR-ROM-01 |
| GET | /rooms/{roomCode} | 방 조회(입장 전 확인) | Access | FR-ROM-02 |
| POST | /rooms/{roomCode}/join | 방 입장 | Access | FR-ROM-02 |
| POST | /rooms/{roomCode}/leave | 방 퇴장 | Access | FR-ROM-02 |

### 2.2 AI 서버

| 메서드 | 경로 | 설명 | 인증 | 요구사항 |
| --- | --- | --- | --- | --- |
| POST | /move | 다음 수 계산 | Access | FR-AI-01 |
| GET | /health | 상태 확인 | 없음 | NFR-05 |

## 3. REST 상세

### 3.1 POST /auth/guest — 게스트 로그인

요청 본문 nickname(2~10자, 한글·영문·숫자). 성공하면 201과 함께 Set-Cookie로 Refresh 토큰을 준다.

```json
// 요청
{ "nickname": "오션" }
// 201 응답 data
{ "userId": 12, "nickname": "오션", "tag": "0421", "userType": "GUEST",
  "accessToken": "eyJ...", "expiresIn": 3600 }
```

오류: INVALID_NICKNAME(400), NICKNAME_TAG_EXHAUSTED(409, 그 닉네임의 태그 10,000개가 모두 쓰임)

### 3.2 POST /auth/refresh — 토큰 재발급

요청 본문 없음. Refresh 쿠키를 확인하고 새 Access 토큰을 주며, Refresh 토큰을 회전해 새 쿠키로 준다. Redis에 저장된 해시와 다르면(이미 쓴 토큰 포함) 거부한다.

```json
// 200 응답 data
{ "accessToken": "eyJ...", "expiresIn": 3600 }
```

오류: TOKEN_EXPIRED(401, 쿠키 없음·만료·폐기·재사용)

### 3.3 POST /auth/logout — 로그아웃

요청 본문 없음. Redis의 Refresh를 지우고 쿠키를 비운다(Max-Age=0). 응답 204.

오류: UNAUTHORIZED(401)

### 3.4 POST /auth/kakao/link — 카카오 계정 연결 (Should)

카카오 인가 코드로 카카오 사용자 ID를 얻어 현재 계정에 연결한다. user_type이 MEMBER가 된다.

```json
// 요청
{ "authorizationCode": "abc...", "redirectUri": "https://{host}/oauth/kakao" }
// 200 응답 data
{ "userId": 12, "userType": "MEMBER", "provider": "KAKAO" }
```

오류: KAKAO_AUTH_FAILED(401), KAKAO_ALREADY_LINKED(409, 그 카카오 계정이 다른 회원에 연결됨), ACCOUNT_ALREADY_LINKED(409, 내 계정이 이미 연결됨)

### 3.5 POST /auth/kakao/login — 카카오 로그인 (Should)

연결된 카카오 계정으로 로그인한다. 3.1과 같은 응답과 Refresh 쿠키를 준다. 연결되지 않은 카카오 계정이면 새 계정을 만들지 않고 거부한다.

```json
// 요청
{ "authorizationCode": "abc...", "redirectUri": "https://{host}/oauth/kakao" }
```

오류: KAKAO_AUTH_FAILED(401), KAKAO_NOT_LINKED(404)

### 3.6 GET /users/me — 내 정보·전적 요약

```json
// 200 응답 data
{ "userId": 12, "nickname": "오션", "tag": "0421", "userType": "GUEST",
  "stats": { "soloGames": 31, "soloBestScore": 45200,
             "aiWins": 7, "aiLosses": 4, "friendWins": 12, "friendLosses": 9 } }
```

### 3.7 GET /users/me/matches — 내 최근 경기 목록

쿼리: mode(SOLO·AI·FRIEND, 생략하면 전체), page(0부터, 기본 0), size(1~50, 기본 20). 내가 참가한 FINISHED·ABORTED 경기를 started_at 내림차순으로 준다.

```json
// 200 응답 data
{ "items": [
    { "matchId": 402, "mode": "FRIEND", "status": "FINISHED", "endReason": "TOP_OUT",
      "startedAt": "2026-11-10T12:31:05+09:00", "durationMs": 184000,
      "myResult": "WIN", "myScore": 12400,
      "opponent": { "playerType": "USER", "userId": 31, "nickname": "민수", "tag": "1187", "aiDifficulty": null } } ],
  "page": 0, "size": 20, "totalElements": 57 }
```

싱글 경기의 opponent는 null이다. 오류: INVALID_QUERY(400)

### 3.8 POST /matches — 싱글·vs AI 경기 시작

친구 대전 경기는 서버가 COUNTDOWN 때 만들므로 이 API를 쓰지 않는다.

```json
// 요청 (SOLO면 aiDifficulty 생략)
{ "mode": "AI", "aiDifficulty": "HARD" }
// 201 응답 data
{ "matchId": 381, "seed": 918273645, "startedAt": "2026-11-10T12:30:00+09:00" }
```

오류: INVALID_MODE(400, FRIEND이거나 AI인데 난이도가 없음)

### 3.9 POST /matches/{matchId}/result — 싱글·vs AI 결과 저장

본인이 시작한 PLAYING 경기에만 한 번 저장할 수 있다. 서버는 요구사항 BR-07로 상한을 검사하고, BR-09로 전적을 갱신한다.

봇 판정(T-39·T-40): 요청에 선택 필드 `keyPresses`(실제 키 입력 수)와 `inputStats`(`intervalCv`·`holdMeanMs`·`holdCv`·`inputsPerPiece`)를 담을 수 있다.
놓은 블록이 20개 이상이고 초당 블록 수가 7을 넘거나 키 입력이 놓은 블록보다 적으면 봇 의심이다. 응답 `botSuspected`가 true이고 ranked=false이며 전적·최고 점수·랭킹에 넣지 않는다.
점수제(2026-11-19부터 기본으로 켬): 초당 블록 4 초과, 키 누름 평균 50ms 미만 또는 변동계수 0.10 미만, 블록 간격 변동계수 0.15 미만, 블록당 키 입력 1.5 미만, `keyPresses`·`inputStats` 없음 중 2개 이상이면 봇 의심이다. 봇으로 판정되면 그 뒤 24시간 동안 같은 계정의 싱글·vs AI 결과도 기록하지 않는다. 이때 응답은 `botSuspected`·`botProbation`이 true다.
친구 대전도 GAME_OVER 때 같은 바로 차단 조건(서버가 잰 라운드 시간, lock의 선택 필드 `keyPresses`)으로 판정하고, 의심이면 그 라운드를 ranked=false로 두고 양쪽 전적에 넣지 않으며 GAME_OVER에 `botSuspectedUserIds`를 담는다(T-41).
나머지 신호(초당 4 초과, 간격·키 누름이 일정함, 블록당 입력이 적음)는 개수만 `matches.input_stats`에 기록한다. `app.bot.soft-enforce=true`이면 2개 이상일 때 봇 의심으로 본다(기본 false).

```json
// 요청 (SOLO면 result·ai와 player의 attackSent·attackReceived 생략)
{ "result": "WIN", "durationMs": 184000,
  "player": { "score": 45200, "linesCleared": 62, "level": 7, "piecesPlaced": 171,
              "attackSent": 38, "attackReceived": 21, "maxCombo": 5 },
  "ai":     { "score": 30100, "linesCleared": 48, "level": 5, "piecesPlaced": 160,
              "attackSent": 21, "attackReceived": 38, "maxCombo": 3 } }
// 200 응답 data
{ "matchId": 381, "ranked": true, "newBest": false, "soloRank": null }
```

soloRank는 싱글이고 newBest가 true일 때만 값이 있다. 오류: INVALID_RESULT(400, 필드 누락·음수·모드와 맞지 않는 필드), NOT_PARTICIPANT(403), MATCH_NOT_FOUND(404), MATCH_ALREADY_FINISHED(409, FINISHED·ABORTED 경기)

### 3.10 GET /matches/{matchId} — 경기 상세

그 경기의 참가자만 볼 수 있다.

```json
// 200 응답 data
{ "matchId": 402, "mode": "FRIEND", "status": "FINISHED", "endReason": "TOP_OUT",
  "aiDifficulty": null, "ranked": true,
  "room": { "roomCode": "K7Q2MX", "seriesNo": 1, "roundNo": 2, "attemptNo": 1 },
  "startedAt": "2026-11-10T12:31:05+09:00", "endedAt": "2026-11-10T12:34:09+09:00",
  "durationMs": 184000, "winnerUserId": 12,
  "players": [
    { "slot": 1, "playerType": "USER", "userId": 12, "nickname": "오션", "tag": "0421",
      "result": "WIN", "score": 12400, "linesCleared": 41, "level": 5, "piecesPlaced": 120,
      "attackSent": 38, "attackReceived": 21, "maxCombo": 5, "apm": 12.4 } ] }
```

room은 FRIEND가 아니면 null이다. 오류: NOT_PARTICIPANT(403), MATCH_NOT_FOUND(404)

### 3.11 GET /rankings/solo — 싱글 랭킹

쿼리: limit(1~50, 기본 10). 동점 순서는 BR-08을 따른다.

```json
// 200 응답 data
{ "top": [ { "rank": 1, "userId": 3, "nickname": "지아", "tag": "0007", "score": 98000,
             "achievedAt": "2026-11-09T21:10:00+09:00" } ],
  "me": { "rank": 37, "score": 45200 } }
```

싱글 랭킹 기록이 없으면 me는 null이다. 오류: INVALID_QUERY(400)

### 3.11-1 GET /rankings/ai — vs AI 난이도별 최단 승리 시간 랭킹 (T-35)

쿼리: difficulty(VERY_EASY·EASY·NORMAL·HARD·IMPOSSIBLE, 필수), limit(1~50, 기본 10).
사용자마다 그 난이도에서 이긴 경기 중 duration_ms가 가장 짧은 한 판만 남기고, 짧은 시간 → 먼저 달성(ended_at) → userId 순으로 순위를 매긴다.
ranked=false 경기와, 신고한 플레이 시간이 서버 경과 시간(ended_at − started_at)보다 5초 넘게 짧은 경기는 뺀다.

```json
// 200 응답 data
{ "difficulty": "HARD",
  "top": [ { "rank": 1, "userId": 3, "nickname": "지아", "tag": "0007", "durationMs": 74200,
             "achievedAt": "2026-11-16T21:10:00+09:00" } ],
  "me": { "rank": 12, "durationMs": 131000 } }
```

그 난이도 승리 기록이 없으면 me는 null이다. 오류: INVALID_QUERY(400, 난이도 없음·틀림, limit 범위 밖)

### 3.12 POST /rooms — 방 생성

```json
// 요청 (roundCount 생략 시 1)
{ "roundCount": 1 }
// 201 응답 data
{ "roomCode": "K7Q2MX", "inviteUrl": "https://{host}/room/K7Q2MX", "status": "WAITING",
  "roundCount": 1, "hostUserId": 12, "expiresAt": "2026-11-10T16:30:00+09:00" }
```

오류: INVALID_ROUND_COUNT(400, 1·3이 아님), ALREADY_IN_ROOM(409, 다른 활성 방에 있음)

### 3.13 GET /rooms/{roomCode} — 방 조회

입장 전 확인용이며 참가자가 아니어도 볼 수 있다.

```json
// 200 응답 data
{ "roomCode": "K7Q2MX", "status": "WAITING", "roundCount": 1, "hostUserId": 12,
  "players": [ { "userId": 12, "nickname": "오션", "tag": "0421", "ready": false } ],
  "expiresAt": "2026-11-10T16:30:00+09:00" }
```

오류: ROOM_NOT_FOUND(404, 없거나 닫힌 방)

### 3.14 POST /rooms/{roomCode}/join — 방 입장

```json
// 200 응답 data (3.13과 같은 모양, 입장한 뒤 상태)
{ "roomCode": "K7Q2MX", "status": "WAITING", "roundCount": 1, "hostUserId": 12,
  "players": [
    { "userId": 12, "nickname": "오션", "tag": "0421", "ready": false },
    { "userId": 31, "nickname": "민수", "tag": "1187", "ready": false } ],
  "expiresAt": "2026-11-10T16:30:00+09:00" }
```

이미 그 방 참가자면 입장 없이 같은 응답을 준다. 오류: ROOM_NOT_FOUND(404), ROOM_FULL(409), ROOM_ALREADY_PLAYING(409, WAITING이 아닌 방), ALREADY_IN_ROOM(409)

### 3.15 POST /rooms/{roomCode}/leave — 방 퇴장

요청 본문 없음. 응답 204. 방장이 나가 한 사람만 남으면 방을 닫고 ROOM_CLOSED(HOST_LEFT)를 보낸다. 두 사람 이상 남으면 userId가 가장 작은 사람이 방장을 넘겨받고 HOST_CHANGED를 보낸다(FR-BAT-04). 경기 중이면 나간 쪽을 탈락시킨다(2인이면 나간 쪽 패배, BR-04·BR-03).

오류: NOT_PARTICIPANT(403), ROOM_NOT_FOUND(404)

## 4. AI 서버 상세

### 4.1 POST /move — 다음 수 계산

difficulty는 VERY_EASY·EASY·NORMAL·HARD·IMPOSSIBLE이다(T-35, T-41). board는 맨 위 행부터 22행 × 10열이며(위 2행은 숨김 영역), 0은 빈칸, 1은 채워진 칸이다. FE는 inputs를 난이도별 간격(FR-AI-01)으로 재생한다. FE가 요청을 보낸 뒤 150ms 안에 응답을 못 받거나 오류가 나면 그 수는 FE 규칙 AI로 둔다(FR-AI-02).

```json
// 요청
{ "difficulty": "IMPOSSIBLE", "board": [[0,0,0,0,0,0,0,0,0,0], "... 22행"],
  "current": "T", "hold": null, "canHold": true,
  "next": ["I","O","S","Z","L"], "pendingGarbage": 2 }
// 200 응답 data
{ "useHold": false, "piece": "T", "rotation": 2, "x": 4,
  "inputs": ["CW","CW","RIGHT","HARD_DROP"], "tSpin": false, "elapsedMs": 12 }
```

rotation은 SRS 상태 0·R·2·L을 0~3으로, x는 블록 기준점의 열(왼쪽 0)이다. inputs 값은 LEFT, RIGHT, CW, CCW, SOFT_DROP, HARD_DROP, HOLD다.

오류: INVALID_BOARD(400, 22 × 10이 아니거나 0·1 밖의 값), UNAUTHORIZED(401), AI_BUSY(503, 동시 계산 상한을 넘음 — FE는 규칙 AI로 대신 둔다)

### 4.2 GET /health — 상태 확인

```json
// 200 응답 data
{ "status": "UP" }
```

## 5. WebSocket (STOMP) 상세

클라이언트 → 서버는 /app, 방 전체 방송은 /topic, 개인 메시지는 /user/queue를 쓴다. 방에 참가하지 않은 사용자의 구독·발행은 서버가 ERROR 프레임으로 거부한다. CONNECT의 Access 토큰이 없거나 만료되면 연결을 거부한다.

서버가 보내는 메시지는 모두 같은 봉투를 쓴다.

```json
{ "type": "ATTACK", "roomCode": "K7Q2MX", "sentAt": 1794293000000, "payload": { } }
```

### 5.1 SEND /app/rooms/{code}/ready — 준비·취소

payload: { "ready": true }. WAITING 방이나 라운드 사이 대기 중에만 받는다. 상태가 바뀌면 READY_CHANGED를 방송하고, 두 사람이 모두 준비되면 COUNTDOWN을 보낸다(FR-ROM-03).

### 5.2 SEND /app/rooms/{code}/lock — 블록 고정

```json
{ "seq": 57, "piece": "T", "rotation": 2, "x": 4, "y": 18,
  "holdUsed": false, "lastMove": "ROTATE", "linesCleared": 2,
  "softDropCells": 3, "hardDropCells": 12 }
```

seq는 플레이어별로 1부터 1씩 는다. 직전 seq와 같으면 무시하고, 건너뛰면 INVALID_LOCK을 보낸다. y는 맨 위 행을 0으로 센 블록 기준점의 행이다. lastMove는 ROTATE·MOVE·DROP 중 하나이며 T-스핀 판정에 쓴다. 서버는 요구사항 BR-05로 검증·재계산한 뒤 BOARD를 방송하고, 상쇄 뒤 남은 공격이 있으면 ATTACK을 보낸다. 맞지 않으면 보낸 사람에게만 /user/queue/errors로 INVALID_LOCK을 보낸다.

softDropCells·hardDropCells는 이 블록이 소프트 드롭·하드 드롭으로 내려온 칸 수이며 서버는 GR-07 ④ 드롭 점수(소프트 칸당 1, 하드 칸당 2)에만 쓴다(POL-005). 두 값의 합은 y − 생성 y(0)를 넘을 수 없고, 넘으면 하드 칸부터 상한까지 잘라낸다. 음수·정수 아님·필드 없음은 0으로 본다. 이 두 값 때문에 INVALID_LOCK을 보내지는 않는다.

### 5.3 SEND /app/rooms/{code}/topout — 게임오버 알림

payload: { "seq": 120, "holdUsed": false }. holdUsed(선택)는 홀드로 꺼낸 블록이 나올 자리에서 막혔을 때 true다. 서버는 자기 보드로 topout을 확인한 뒤 BR-04로 승패를 정하고 GAME_OVER를 방송한다. 서버 보드에서는 아직 블록이 나올 수 있으면 INVALID_LOCK(5.6)과 서버 보드를 보내고, 클라이언트는 RESYNC로 맞춰 이어 한다. 또 서버는 lock(5.2)을 받아들인 뒤 다음 블록이 생성 자리에 들어가지 않으면 이 메시지 없이도 topout으로 판정한다(2026-11-19).

### 5.4 SEND /app/rooms/{code}/rematch — 재대결 수락·거절 (Should)

payload: { "accept": true }. 방이 FINISHED일 때만 받는다. REMATCH_CHANGED를 방송하고, 두 사람이 모두 수락하면 새 시리즈의 COUNTDOWN을 보낸다. 거절하면 재대결은 끝나고 방은 FINISHED로 남는다.

### 5.5 SUBSCRIBE /topic/rooms/{code} — 방 방송

| type | 언제 | payload |
| --- | --- | --- |
| PLAYER_JOINED | 입장 | { "userId": 31, "nickname": "민수", "tag": "1187" } |
| PLAYER_LEFT | 퇴장·연결 끊김 | { "userId": 31, "reason": "LEFT" } — reason은 LEFT 또는 DISCONNECTED |
| PLAYER_RECONNECTED | 10초 안에 재접속 | { "userId": 31 } |
| READY_CHANGED | 준비 상태 변경 | { "userId": 31, "ready": true } |
| COUNTDOWN | 경기 시작 예고 | { "matchId": 402, "seriesNo": 1, "roundNo": 1, "attemptNo": 1, "seed": 551203, "startsAt": 1794293003000 } |
| BOARD | lock 처리 뒤 | { "userId": 31, "seq": 57, "board": "220자", "score": 12400, "linesCleared": 41, "level": 5, "combo": 1, "b2b": true, "pendingGarbage": 0 } |
| ATTACK | 상쇄 뒤 남은 공격 | { "fromUserId": 12, "toUserId": 31, "lines": 3, "holeColumn": 4 } |
| GAME_OVER | 라운드 승패 확정 | { "matchId": 402, "winnerUserId": 12, "reason": "TOP_OUT", "seriesWins": { "12": 1, "31": 0 }, "seriesWinnerUserId": null, "nextRoundStartsAt": 1794293190000, "stats": [ { "userId": 12, "score": 12400, "attackSent": 38, "attackReceived": 21, "maxCombo": 5, "apm": 12.4 } ] } |
| REMATCH_CHANGED | 재대결 수락·거절 (Should) | { "userId": 31, "accept": true } |
| PLAYER_OUT | 다인 대전에서 탈락했지만 두 명 이상 남아 경기가 이어짐 (FR-BAT-04) | { "userId": 31, "placement": 3, "reason": "TOP_OUT" } — reason은 TOP_OUT, LEFT, DISCONNECT |
| HOST_CHANGED | 방장이 나가고 두 명 이상 남음 (FR-BAT-04) | { "userId": 12 } |
| ROOM_CLOSED | 방이 닫힘 | { "reason": "HOST_LEFT" } — reason은 HOST_LEFT, EMPTY, EXPIRED, UNRECOVERABLE |

GAME_OVER의 reason은 TOP_OUT, DRAW, LEFT, DISCONNECT 중 하나다. 무승부면 winnerUserId가 null이다. placements는 참가자별 등수({ "12": 1, "31": 2 })이고, 3명 이상 경기는 1판이라 seriesWinnerUserId가 1위(무승부면 null)이고 nextRoundStartsAt은 null이다(FR-BAT-04). 시리즈가 끝났으면 seriesWinnerUserId에 값이 있고 nextRoundStartsAt은 null이다. board 문자열은 ERD 명세서 6장 형식이다.

### 5.6 SUBSCRIBE /user/queue/errors — 개인 오류

```json
{ "code": "INVALID_LOCK", "message": "서버 보드와 맞지 않습니다.",
  "payload": { "seq": 57, "board": "220자", "nextSeq": 57 } }
```

INVALID_LOCK이면 클라이언트는 payload의 서버 보드로 바꾸고 nextSeq부터 다시 보낸다.

### 5.7 SUBSCRIBE /user/queue/state — 재접속 상태 복원

재접속해 이 주소를 구독하면 서버가 RESYNC를 한 번 보낸다.

```json
{ "type": "RESYNC", "payload": { "matchId": 402, "seq": 57, "pieceIndex": 61, "hold": "I",
  "holdUsed": false, "board": "220자", "pendingGarbage": [ { "lines": 2, "holeColumn": 3 } ],
  "opponent": { "userId": 31, "board": "220자", "score": 9800 },
  "seed": 551203, "me": { "score": 12400, "linesCleared": 41, "level": 5, "combo": 1, "b2b": true } } }
```

seed는 경기 시드(COUNTDOWN과 같은 값), me는 서버 진행 상태 원본의 내 성적이다(POL-006). opponents는 상대 전부의 { userId, board, score, out }이고(out은 이미 탈락), opponent는 그 첫 항목이다(2인 화면 호환, FR-BAT-04). 페이지를 새로 연 클라이언트는 seed와 pieceIndex로 블록 순서를 다시 만들고 me로 성적 표시를 복원해 경기를 이어 한다.

### 5.8 친구 대전 한 판의 흐름

1. 두 클라이언트가 REST로 입장한 뒤 STOMP CONNECT, /topic/rooms/{code}·/user/queue/errors·/user/queue/state를 구독한다.
2. 각자 ready를 보내고, 둘 다 준비되면 서버가 matches 행을 만들고 COUNTDOWN(시드, startsAt = 지금 + 3초)을 보낸다.
3. startsAt에 두 클라이언트가 같은 시드로 시작하고, 블록이 고정될 때마다 lock을 보낸다.
4. 서버는 BR-05로 검증·재계산해 BOARD를 방송하고, 상쇄 뒤 남은 공격이 있으면 ATTACK을 보낸다.
5. 연결이 끊기면 PLAYER_LEFT(DISCONNECTED) 뒤 10초를 기다린다. 다시 붙으면 PLAYER_RECONNECTED와 RESYNC로 이어 간다(BR-06). 두 사람이 모두 끊긴 채 10초가 지나면 경기를 ABORTED로 처리한다.
6. topout이나 퇴장·끊김 확정이 오면 서버가 승패를 정해 GAME_OVER를 보내고 MySQL에 결과를 저장한다. 시리즈가 남았으면 5초 뒤 다음 라운드 COUNTDOWN을 보낸다(BR-03).

## 6. 오류 코드

| 코드 | HTTP | 설명 |
| --- | --- | --- |
| INVALID_NICKNAME | 400 | 닉네임 형식 오류 |
| INVALID_QUERY | 400 | 쿼리 값 오류(page·size·limit·mode) |
| INVALID_MODE | 400 | 경기 시작 모드·난이도 오류 |
| INVALID_RESULT | 400 | 결과 저장 본문 오류 |
| INVALID_ROUND_COUNT | 400 | 라운드 수가 1·3이 아님 |
| INVALID_BOARD | 400 | AI 요청의 보드 형식 오류 |
| UNAUTHORIZED | 401 | Access 토큰 없음·위조 |
| TOKEN_EXPIRED | 401 | Access 토큰 만료, 또는 Refresh 토큰 없음·만료·폐기·재사용 |
| KAKAO_AUTH_FAILED | 401 | 카카오 인가 코드 확인 실패 |
| NOT_PARTICIPANT | 403 | 참가하지 않은 방·경기에 접근 |
| ROOM_NOT_FOUND | 404 | 없거나 닫힌 게임 코드 |
| MATCH_NOT_FOUND | 404 | 없는 경기 |
| KAKAO_NOT_LINKED | 404 | 연결된 회원이 없는 카카오 계정 |
| NOT_FOUND | 404 | 2.1 API 목록의 어떤 경로에도 매핑되지 않는 요청 |
| METHOD_NOT_ALLOWED | 405 | 경로는 있으나 지원하지 않는 HTTP 메서드(Allow 헤더로 지원 메서드를 알린다) |
| NICKNAME_TAG_EXHAUSTED | 409 | 그 닉네임의 태그가 모두 쓰임 |
| ROOM_FULL | 409 | 정원(2명) 초과 |
| ROOM_ALREADY_PLAYING | 409 | WAITING이 아닌 방 |
| ALREADY_IN_ROOM | 409 | 다른 활성 방에 있음 |
| MATCH_ALREADY_FINISHED | 409 | 이미 끝난 경기 |
| KAKAO_ALREADY_LINKED | 409 | 다른 회원에 연결된 카카오 계정 |
| ACCOUNT_ALREADY_LINKED | 409 | 이미 카카오가 연결된 계정 |
| INVALID_LOCK | - (WS) | 서버 검증·재계산과 맞지 않는 lock |
| INTERNAL_ERROR | 500 | 서버 오류 |
| AI_BUSY | 503 | AI 서버의 동시 계산 상한을 넘음(보안 점검 M-1) |

NOT_FOUND·METHOD_NOT_ALLOWED는 요청이 정의된 API에 닿지 못한 경우에만 쓰고 서버 오류로 기록하지 않는다. 정의된 API 안에서 자원을 찾지 못한 경우는 ROOM_NOT_FOUND·MATCH_NOT_FOUND·KAKAO_NOT_LINKED를 그대로 쓴다. Access 토큰이 필요한 /api/v1 아래 경로는 인증 검사가 먼저라, 토큰이 없거나 위조면 경로와 상관없이 UNAUTHORIZED(401)가 나간다(POL-026).
