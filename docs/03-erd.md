# 테트리스 대전 게임 — ERD 명세서

개정 2판 · 2026-11-12 · 작성 오션(팀장)

## 저장 원칙

- MySQL에는 회원, 누적 전적, 방 이력, 경기 기록(진행 중 경기 포함)을 둔다.
- Redis에는 진행 중인 방·경기의 실시간 상태(참가자 준비 여부, 보드, 대기 방해 줄, 콤보, B2B), 랭킹 캐시, Refresh 토큰을 둔다. 진행 중 상태의 원본은 Redis다.
- matches 행은 경기가 시작될 때 PLAYING으로 만들고 끝나면 FINISHED 또는 ABORTED로 바꾼다(요구사항 BR-02). game_rooms 행은 방 생성 때 만들고 status를 이력으로 갱신한다(BR-01).
- 세 모드를 matches.mode 하나로 묶고, 참가자별 성적은 match_players에 따로 저장한다.
- 끝난 경기와 닫힌 방은 1년 보존하며, 매일 04:00(KST)에 ended_at·closed_at이 1년 지난 행을 지운다(BR-10).

## 관계

| 관계 | 카디널리티 | 설명 |
| --- | --- | --- |
| users → user_stats | 1 : 1 | 게스트 로그인 때 user_stats 행도 함께 만든다 |
| users → game_rooms | 1 : N | game_rooms.host_user_id |
| game_rooms → matches | 1 : N | FRIEND 경기만 room_id가 있다 |
| matches → match_players | 1 : N | 싱글 1행, vs AI·친구 대전 2행 |
| users → match_players | 1 : N | AI 행은 user_id가 NULL |
| users → matches | 1 : N | matches.winner_user_id, 싱글·AI 승리·무승부·무효면 NULL |

## 1. users — 회원 (게스트 포함)

| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AUTO_INCREMENT | 회원 ID |
| nickname | VARCHAR(20) | NOT NULL | 닉네임(2~10자, 한글·영문·숫자) |
| tag | CHAR(4) | NOT NULL, UQ(nickname, tag) | 같은 닉네임 구분 태그(예: 0421) |
| user_type | ENUM('GUEST','MEMBER') | NOT NULL, DEFAULT 'GUEST' | 카카오 연결 전 GUEST, 연결 뒤 MEMBER |
| provider | VARCHAR(20) | NULL | 소셜 제공자(KAKAO), 게스트는 NULL |
| provider_id | VARCHAR(100) | NULL, UQ(provider, provider_id) | 제공자 쪽 사용자 ID |
| created_at | DATETIME(6) | NOT NULL | 가입 시각 |
| last_login_at | DATETIME(6) | NULL | 마지막 로그인·토큰 재발급 시각 |

## 2. user_stats — 회원별 누적 전적

경기가 FINISHED로 확정될 때 BR-09대로 갱신해 내 정보·랭킹 조회를 빠르게 한다.

| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| user_id | BIGINT | PK, FK → users.id | 회원 ID |
| solo_games | INT | NOT NULL, DEFAULT 0 | 싱글 완료 판 수 |
| solo_best_score | INT | NOT NULL, DEFAULT 0 | 싱글 최고 점수(ranked 결과만) |
| solo_best_achieved_at | DATETIME(6) | NULL | 최고 점수를 처음 달성한 시각, 기록이 없으면 NULL |
| ai_wins | INT | NOT NULL, DEFAULT 0 | vs AI 승 |
| ai_losses | INT | NOT NULL, DEFAULT 0 | vs AI 패 |
| friend_wins | INT | NOT NULL, DEFAULT 0 | 친구 대전 라운드 승 |
| friend_losses | INT | NOT NULL, DEFAULT 0 | 친구 대전 라운드 패 |
| updated_at | DATETIME(6) | NOT NULL | 마지막 갱신 |

인덱스: (solo_best_score DESC, solo_best_achieved_at ASC) — Redis 랭킹이 비었을 때 다시 채우는 용도(BR-08)

## 3. game_rooms — 친구 대전 방 이력

진행 중 상태의 원본은 Redis이고, 이 테이블은 방의 생성·상태·종료 이력을 남긴다. 게임 코드는 닫힌 방에서 다시 쓰일 수 있어 UNIQUE를 걸지 않는다(활성 방끼리 중복은 Redis room:{code} 키로 막는다).

| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AUTO_INCREMENT | 방 ID |
| room_code | CHAR(6) | NOT NULL, INDEX | 게임 코드(대문자·숫자, 0·O·1·I 제외) |
| host_user_id | BIGINT | NOT NULL, FK → users.id | 방장 |
| status | ENUM('WAITING','PLAYING','FINISHED','CLOSED') | NOT NULL | 방 상태(BR-01) |
| round_count | TINYINT | NOT NULL, DEFAULT 1 | 라운드 수(1 또는 3) |
| close_reason | ENUM('HOST_LEFT','EMPTY','EXPIRED','UNRECOVERABLE') | NULL | 닫힌 이유, 열려 있으면 NULL. UNRECOVERABLE은 Redis 활성 상태를 복구할 수 없어 닫은 경우(BR-01) |
| created_at | DATETIME(6) | NOT NULL | 생성 시각 |
| closed_at | DATETIME(6) | NULL | 닫힌 시각 |

## 4. matches — 경기 (세 모드 공통)

| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AUTO_INCREMENT | 경기 ID |
| mode | ENUM('SOLO','AI','FRIEND') | NOT NULL | 게임 모드 |
| room_id | BIGINT | NULL, FK → game_rooms.id | FRIEND일 때만 값 |
| series_no | INT | NULL | FRIEND일 때 같은 방 안의 시리즈 번호(1부터, 재대결마다 + 1) |
| round_no | TINYINT | NULL | FRIEND일 때 시리즈 안의 라운드 번호(1~3) |
| attempt_no | TINYINT | NULL | FRIEND일 때 같은 라운드를 다시 한 횟수(1부터, 무승부·ABORTED 뒤 + 1) |
| ai_difficulty | ENUM('EASY','NORMAL','HARD','IMPOSSIBLE') | NULL | AI일 때만 값 |
| seed | BIGINT | NOT NULL | 7-bag 시드 |
| status | ENUM('PLAYING','FINISHED','ABORTED') | NOT NULL | 경기 상태(BR-02) |
| end_reason | ENUM('TOP_OUT','DRAW','LEFT','DISCONNECT','EXPIRED','COMPLETED') | NULL | 종료 사유. 싱글·AI 결과 저장은 COMPLETED, 2시간 미저장은 EXPIRED |
| winner_user_id | BIGINT | NULL, FK → users.id | 승자. 싱글, AI 승리, 무승부, ABORTED면 NULL |
| series_winner_user_id | BIGINT | NULL, FK → users.id | FRIEND일 때 시리즈를 끝낸 경기에만 시리즈 승자를 적는다. 나머지 경기는 NULL |
| ranked | BOOLEAN | NOT NULL, DEFAULT TRUE | BR-07 상한 검사 통과 여부(싱글·AI). FRIEND는 항상 TRUE |
| started_at | DATETIME(6) | NOT NULL | 실제 게임 시작 시각(친구 대전은 COUNTDOWN의 startsAt) |
| ended_at | DATETIME(6) | NULL | 종료 시각 |
| duration_ms | INT | NULL | 플레이 시간 |

인덱스: (mode, started_at), (room_id), (status, started_at), UQ(room_id, series_no, round_no, attempt_no)

시리즈가 끝나면 그 시리즈의 마지막 경기 행에 series_winner_user_id를 적는다. 보통은 winner_user_id가 2번(라운드 수 1이면 1번) 나온 사용자이고, 시리즈 도중 한쪽이 나가면 남은 쪽이다(BR-03). 라운드 사이에 나가 진행 중 경기가 없으면 그 시리즈의 마지막 FINISHED 경기 행에 적는다.

## 5. match_players — 경기별 참가자 성적

싱글은 1행, vs AI와 친구 대전은 2행이다. AI 행은 user_id가 NULL이고 player_type이 AI다.

| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AUTO_INCREMENT | 행 ID |
| match_id | BIGINT | NOT NULL, FK → matches.id | 경기 ID |
| slot | TINYINT | NOT NULL, UQ(match_id, slot) | 플레이어 자리(1, 2). AI는 2 |
| user_id | BIGINT | NULL, FK → users.id | 회원 ID(AI면 NULL) |
| player_type | ENUM('USER','AI') | NOT NULL | 플레이어 종류 |
| result | ENUM('WIN','LOSE','NONE') | NOT NULL | 승패. 싱글·무승부·ABORTED는 NONE |
| score | INT | NOT NULL, DEFAULT 0 | 점수 |
| lines_cleared | INT | NOT NULL, DEFAULT 0 | 지운 줄 수 |
| level | INT | NOT NULL, DEFAULT 1 | 도달 레벨 |
| pieces_placed | INT | NOT NULL, DEFAULT 0 | 놓은 블록 수 |
| attack_sent | INT | NOT NULL, DEFAULT 0 | 상쇄 뒤 상대에게 보낸 공격 줄 |
| attack_received | INT | NOT NULL, DEFAULT 0 | 받은 공격 줄 |
| max_combo | INT | NOT NULL, DEFAULT 0 | 최대 콤보 |

인덱스: (user_id, match_id)

APM은 저장하지 않고 조회 때 attack_sent ÷ (duration_ms ÷ 60,000)으로 계산해 소수 첫째 자리까지 표시한다.

## 6. Redis 키

| 키 | 타입 | TTL | 내용 |
| --- | --- | --- | --- |
| room:{code} | Hash | 30분, 활동마다 30분으로 갱신 | roomId, hostId, status, roundCount, seriesNo, roundNo, attemptNo, wins(userId별 승수), currentMatchId |
| room:{code}:players | Hash | room:{code}와 같음 | userId → {nickname, tag, ready, connected, rematch} |
| user:{userId}:room | String | room:{code}와 같음 | 사용자가 있는 활성 방 코드(한 사람 한 방) |
| match:{matchId}:state | Hash | 2시간, lock·재접속마다 2시간으로 갱신(친구 대전은 활동이 있는 한 지워지지 않음) | 플레이어별 board(220자), seq, pieceIndex, hold, holdUsed, pendingGarbage(묶음 목록), combo, b2b, score, linesCleared, level, piecesPlaced, attackSent, attackReceived, maxCombo, topoutAt |
| match:{matchId}:disconnect:{userId} | String | 10초 | 재접속 유예 타이머. 만료되면 끊긴 쪽 패배 처리(BR-06) |
| ranking:solo | Sorted Set | 없음 | member = userId, score = BR-08 합성 점수 |
| ranking:solo:seq | String(카운터) | 없음 | 최고 점수 달성 순번 |
| refresh:{userId} | String | 7일 | 현재 Refresh 토큰의 SHA-256 해시(회전마다 교체) |
| ws:session:{sessionId} | String | 연결이 끊긴 뒤 10초 | userId, roomCode(끊김 처리용) |

board 문자열은 22행 × 10열 = 220자이며 맨 위 행부터 왼쪽에서 오른쪽 순서다. 0은 빈칸, I·O·T·S·Z·J·L은 블록, G는 방해 줄이다.
