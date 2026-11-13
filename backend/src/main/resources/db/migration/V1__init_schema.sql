-- ERD 명세 개정 2판(BEEZ 기준 v2, ENT-001~005)
-- FK는 모두 ON DELETE RESTRICT: 보존 기간 삭제 배치(POL-023)가 match_players → matches → game_rooms 순서로 지우며 CASCADE를 쓰지 않는다.

CREATE TABLE users (
    id            BIGINT                   NOT NULL AUTO_INCREMENT,
    nickname      VARCHAR(20)              NOT NULL,
    tag           CHAR(4)                  NOT NULL,
    user_type     ENUM ('GUEST', 'MEMBER') NOT NULL DEFAULT 'GUEST',
    provider      VARCHAR(20)              NULL,
    provider_id   VARCHAR(100)             NULL,
    created_at    DATETIME(6)              NOT NULL,
    last_login_at DATETIME(6)              NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_users_nickname_tag UNIQUE (nickname, tag),
    CONSTRAINT uq_users_provider UNIQUE (provider, provider_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE user_stats (
    user_id               BIGINT      NOT NULL,
    solo_games            INT         NOT NULL DEFAULT 0,
    solo_best_score       INT         NOT NULL DEFAULT 0,
    solo_best_achieved_at DATETIME(6) NULL,
    ai_wins               INT         NOT NULL DEFAULT 0,
    ai_losses             INT         NOT NULL DEFAULT 0,
    friend_wins           INT         NOT NULL DEFAULT 0,
    friend_losses         INT         NOT NULL DEFAULT 0,
    updated_at            DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_stats_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_user_stats_ranking (solo_best_score DESC, solo_best_achieved_at ASC)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE game_rooms (
    id           BIGINT                                                   NOT NULL AUTO_INCREMENT,
    room_code    CHAR(6)                                                  NOT NULL,
    host_user_id BIGINT                                                   NOT NULL,
    status       ENUM ('WAITING', 'PLAYING', 'FINISHED', 'CLOSED')        NOT NULL,
    round_count  TINYINT                                                  NOT NULL DEFAULT 1,
    close_reason ENUM ('HOST_LEFT', 'EMPTY', 'EXPIRED', 'UNRECOVERABLE') NULL,
    created_at   DATETIME(6)                                              NOT NULL,
    closed_at    DATETIME(6)                                              NULL,
    PRIMARY KEY (id),
    INDEX idx_game_rooms_room_code (room_code),
    CONSTRAINT fk_game_rooms_host FOREIGN KEY (host_user_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE matches (
    id                    BIGINT                                                                  NOT NULL AUTO_INCREMENT,
    mode                  ENUM ('SOLO', 'AI', 'FRIEND')                                           NOT NULL,
    room_id               BIGINT                                                                  NULL,
    series_no             INT                                                                     NULL,
    round_no              TINYINT                                                                 NULL,
    attempt_no            TINYINT                                                                 NULL,
    ai_difficulty         ENUM ('EASY', 'NORMAL', 'HARD')                                         NULL,
    seed                  BIGINT                                                                  NOT NULL,
    status                ENUM ('PLAYING', 'FINISHED', 'ABORTED')                                 NOT NULL,
    end_reason            ENUM ('TOP_OUT', 'DRAW', 'LEFT', 'DISCONNECT', 'EXPIRED', 'COMPLETED') NULL,
    winner_user_id        BIGINT                                                                  NULL,
    series_winner_user_id BIGINT                                                                  NULL,
    ranked                BOOLEAN                                                                 NOT NULL DEFAULT TRUE,
    started_at            DATETIME(6)                                                             NOT NULL,
    ended_at              DATETIME(6)                                                             NULL,
    duration_ms           INT                                                                     NULL,
    PRIMARY KEY (id),
    INDEX idx_matches_mode_started (mode, started_at),
    INDEX idx_matches_room (room_id),
    INDEX idx_matches_status_started (status, started_at),
    CONSTRAINT uq_matches_room_round UNIQUE (room_id, series_no, round_no, attempt_no),
    CONSTRAINT fk_matches_room FOREIGN KEY (room_id) REFERENCES game_rooms (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_matches_winner FOREIGN KEY (winner_user_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_matches_series_winner FOREIGN KEY (series_winner_user_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- FRIEND면 방·시리즈·라운드·시도 번호가 모두 있고, SOLO·AI면 모두 없다(ENT-004)
    CONSTRAINT chk_matches_friend_fields CHECK (
        (mode = 'FRIEND' AND room_id IS NOT NULL AND series_no IS NOT NULL AND round_no IS NOT NULL AND attempt_no IS NOT NULL)
        OR (mode <> 'FRIEND' AND room_id IS NULL AND series_no IS NULL AND round_no IS NULL AND attempt_no IS NULL)
    ),
    -- ai_difficulty는 AI 모드에서만 필수다(ENT-004)
    CONSTRAINT chk_matches_ai_difficulty CHECK (
        (mode = 'AI' AND ai_difficulty IS NOT NULL) OR (mode <> 'AI' AND ai_difficulty IS NULL)
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE match_players (
    id              BIGINT                         NOT NULL AUTO_INCREMENT,
    match_id        BIGINT                         NOT NULL,
    slot            TINYINT                        NOT NULL,
    user_id         BIGINT                         NULL,
    player_type     ENUM ('USER', 'AI')            NOT NULL,
    result          ENUM ('WIN', 'LOSE', 'NONE')   NOT NULL,
    score           INT                            NOT NULL DEFAULT 0,
    lines_cleared   INT                            NOT NULL DEFAULT 0,
    level           INT                            NOT NULL DEFAULT 1,
    pieces_placed   INT                            NOT NULL DEFAULT 0,
    attack_sent     INT                            NOT NULL DEFAULT 0,
    attack_received INT                            NOT NULL DEFAULT 0,
    max_combo       INT                            NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_match_players_slot UNIQUE (match_id, slot),
    INDEX idx_match_players_user_match (user_id, match_id),
    CONSTRAINT fk_match_players_match FOREIGN KEY (match_id) REFERENCES matches (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_match_players_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
