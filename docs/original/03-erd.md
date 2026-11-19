# 테트리스 대전 게임 — ERD 명세서

Nov 10, 2026 · @ocean

MySQL에는 회원 · 방 이력 · 경기 기록을 남기고, 진행 중인 방 · 게임의 실시간 상태(대기 방해 줄 · 콤보 · 준비 여부)는 Redis에 둔다. matches 행은 경기 시작 시점(싱글 · AI는 POST /matches, 친구 대전은 COUNTDOWN)에 PLAYING으로 만들고, 끝나면 FINISHED 또는 ABORTED로 바꾼다. game_rooms 행은 방 생성 때 만들고 status를 이력으로 갱신한다. 세 모드를 `matches.mode` 하나로 묶고, 참가자별 성적은 `match_players`에 따로 저장한다.

&#91;embedded content: ERD · MySQL 테이블 5개\]

그림에 없는 관계: `matches.winner_user_id` → `users.id` (N : 1, 싱글 · AI 승리 · 무효 시 NULL).

## 1. users — 회원 (게스트 포함)

| 컨럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AUTO\_INCREMENT | 회원 ID |
| nickname | VARCHAR(20) | NOT NULL | 닉네임 (2\~10자) |
| tag | CHAR(4) | NOT NULL, UQ(nickname, tag) | 동명이인 구분 태그 (예: 0421) |
| user\_type | ENUM('GUEST','MEMBER') | NOT NULL, DEFAULT 'GUEST' | 계정 종류 |
| provider | VARCHAR(20) | NULL | 소셜 제공자 (KAKAO), 게스트는 NULL |
| provider\_id | VARCHAR(100) | NULL, UQ(provider, provider\_id) | 제공자 측 사용자 ID |
| created\_at | DATETIME(6) | NOT NULL | 가입 시각 |
| last\_login\_at | DATETIME(6) | NULL | 마지막 로그인 |

## 2. user\_stats — 회원별 누적 전적

경기 종료 시 갱신해 로비 · 마이페이지 조회를 빠르게 한다.

| 컨럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| user\_id | BIGINT | PK, FK → users.id | 회원 ID |
| solo\_games | INT | NOT NULL, DEFAULT 0 | 싱글 플레이 수 |
| solo\_best\_score | INT | NOT NULL, DEFAULT 0 | 싱글 최고 점수 |
| solo\_best\_achieved\_at | DATETIME(6) | NULL | 최고 점수 최초 달성 시각 |
| ai\_wins | INT | NOT NULL, DEFAULT 0 | vs AI 승 |
| ai\_losses | INT | NOT NULL, DEFAULT 0 | vs AI 패 |
| friend\_wins | INT | NOT NULL, DEFAULT 0 | 친구 대전 승 |
| friend\_losses | INT | NOT NULL, DEFAULT 0 | 친구 대전 패 |
| updated\_at | DATETIME(6) | NOT NULL | 마지막 갱신 |

## 3. game\_rooms — 친구 대전 방 이력

진행 중 상태는 Redis가 원본이고, 이 테이블은 방 생성 · 종료 이력을 남긴다. 게임 코드는 재사용될 수 있어 UNIQUE를 걸지 않는다.

| 컨럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AUTO\_INCREMENT | 방 ID |
| room\_code | CHAR(6) | NOT NULL, INDEX | 게임 코드 (대문자 + 숫자, 0 · O · 1 · I 제외) |
| host\_user\_id | BIGINT | FK → users.id, NOT NULL | 방장 |
| status | ENUM('WAITING','PLAYING','FINISHED','CLOSED') | NOT NULL | 방 상태 |
| round\_count | TINYINT | NOT NULL, DEFAULT 1 | 라운드 수 (1 또는 3) |
| created\_at | DATETIME(6) | NOT NULL | 생성 시각 |
| closed\_at | DATETIME(6) | NULL | 종료 시각 |

## 4. matches — 경기 (세 모드 공통)

| 컨럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AUTO\_INCREMENT | 경기 ID |
| mode | ENUM('SOLO','AI','FRIEND') | NOT NULL | 게임 모드 |
| room\_id | BIGINT | FK → game\_rooms.id, NULL | FRIEND일 때만 값 |
| series\_no | INT | NULL | FRIEND일 때 같은 방 안의 시리즈 번호(1부터, 재대결마다 1 증가) |
| round\_no | TINYINT | NULL | FRIEND일 때 시리즈 안의 라운드 번호(1~3) |
| ai\_difficulty | ENUM('EASY','NORMAL','HARD') | NULL | AI일 때만 값 |
| seed | BIGINT | NOT NULL | 7-bag 시드 |
| status | ENUM('PLAYING','FINISHED','ABORTED') | NOT NULL | 경기 상태 |
| winner\_user\_id | BIGINT | FK → users.id, NULL | 승자 (SOLO · AI 승리 · 무효 시 NULL) |
| started\_at | DATETIME(6) | NOT NULL | 시작 시각 |
| ended\_at | DATETIME(6) | NULL | 종료 시각 |
| duration\_ms | INT | NULL | 플레이 시간 |

인덱스: (mode, started\_at), (room\_id), UQ(room\_id, series\_no, round\_no)

3판 2선승의 최종 승자는 같은 (room\_id, series\_no)의 matches.winner\_user\_id 중 2번 나온 사용자로 계산한다.

## 5. match\_players — 경기별 참가자 성적

싱글은 1행, vs AI와 친구 대전은 2행이다. AI 행은 `user_id`가 NULL이고 `player_type`이 AI다.

| 컨럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AUTO\_INCREMENT | 행 ID |
| match\_id | BIGINT | FK → matches.id, NOT NULL | 경기 ID |
| slot | TINYINT | NOT NULL, UQ(match\_id, slot) | 플레이어 자리 (1, 2) |
| user\_id | BIGINT | FK → users.id, NULL | 회원 ID (AI면 NULL) |
| player\_type | ENUM('USER','AI') | NOT NULL | 플레이어 종류 |
| result | ENUM('WIN','LOSE','NONE') | NOT NULL | 승패 (싱글은 NONE) |
| score | INT | NOT NULL, DEFAULT 0 | 점수 |
| lines\_cleared | INT | NOT NULL, DEFAULT 0 | 지운 줄 수 |
| level | INT | NOT NULL, DEFAULT 1 | 도달 레벨 |
| pieces\_placed | INT | NOT NULL, DEFAULT 0 | 놓은 블록 수 |
| attack\_sent | INT | NOT NULL, DEFAULT 0 | 보낸 공격 줄 |
| attack\_received | INT | NOT NULL, DEFAULT 0 | 받은 공격 줄 |
| max\_combo | INT | NOT NULL, DEFAULT 0 | 최대 콤보 |

인덱스: (user\_id, match\_id)

## 6. Redis 키 설계

| 키 | 타입 | TTL | 내용 |
| --- | --- | --- | --- |
| room:{code} | Hash | 30분 (활동 시 갱신) | roomId, hostId, status, roundCount, seed |
| room:{code}:players | Hash | room과 동일 | userId → {nickname, ready, sessionId} |
| match:{matchId}:state | Hash | 2시간 | 플레이어별 대기 중 방해 줄, 콤보, B2B 여부 |
| ranking:solo | Sorted Set | 없음 | member = userId, score = 최고 점수 |
| refresh:{userId} | String | 7일 | Refresh Token |
| ws:session:{sessionId} | String | 연결 중 | userId, roomCode (끊김 처리용) |

