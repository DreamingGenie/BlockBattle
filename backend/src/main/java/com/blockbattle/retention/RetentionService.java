package com.blockbattle.retention;

import com.blockbattle.common.time.ApiTime;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 보존 기간 정리(POL-010·POL-023). 끝난 경기는 ended_at, 닫힌 방은 closed_at이 기준 시각(실행 시각 - 1년, KST 달력)보다 이르면 지운다.
 * FK는 ON DELETE RESTRICT라 match_players → matches → game_rooms 순서로 지우고, 방은 그 방의 경기가 모두 지워진 뒤에만 지운다.
 * users·user_stats는 건드리지 않는다.
 */
@Service
public class RetentionService {

    public record Purged(int players, int matches, int rooms) {
    }

    static final int BATCH = 1_000;
    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public RetentionService(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** 1년 = KST 달력 기준(윤년 2월 29일 등은 java.time 규칙) */
    public static Instant cutoff(Instant now) {
        return now.atZone(ApiTime.SERVICE_ZONE).minusYears(1).toInstant();
    }

    public Purged purge(Instant now) {
        Timestamp cutoff = Timestamp.from(cutoff(now));
        int players = 0;
        int matches = 0;
        while (true) {
            List<Long> ids = jdbc.queryForList("""
                    SELECT id FROM matches
                    WHERE status IN ('FINISHED', 'ABORTED') AND ended_at IS NOT NULL AND ended_at < :cutoff
                    ORDER BY id LIMIT :batch""", Map.of("cutoff", cutoff, "batch", BATCH), Long.class);
            if (ids.isEmpty()) break;
            int[] counts = tx.execute(status -> new int[] {
                    jdbc.update("DELETE FROM match_players WHERE match_id IN (:ids)", Map.of("ids", ids)),
                    jdbc.update("DELETE FROM matches WHERE id IN (:ids)", Map.of("ids", ids))});
            players += counts[0];
            matches += counts[1];
        }
        int rooms = 0;
        while (true) {
            int deleted = jdbc.update("""
                    DELETE FROM game_rooms
                    WHERE status = 'CLOSED' AND closed_at < :cutoff
                      AND NOT EXISTS (SELECT 1 FROM matches m WHERE m.room_id = game_rooms.id)
                    LIMIT :batch""", Map.of("cutoff", cutoff, "batch", BATCH));
            rooms += deleted;
            if (deleted < BATCH) break;
        }
        log.info("보존 기간 정리: 기준 {} 이전 — match_players {}, matches {}, game_rooms {}건 삭제", cutoff, players, matches, rooms);
        return new Purged(players, matches, rooms);
    }
}
