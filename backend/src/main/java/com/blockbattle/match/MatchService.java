package com.blockbattle.match;

import com.blockbattle.common.error.ApiException;
import com.blockbattle.common.error.ErrorCode;
import com.blockbattle.match.MatchRepository.PlayerStats;
import com.blockbattle.user.UserRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 싱글·vs AI 경기 시작과 결과 저장(FR-SOL-01, FR-AI-03, BR-02·07·09) */
@Service
public class MatchService {

    public record Started(long matchId, long seed, Instant startedAt) {
    }

    /** SOLO면 result·ai·player.attackSent·attackReceived는 null이어야 한다 */
    public record ResultInput(String result, Integer durationMs, Stats player, Stats ai) {
    }

    public record Stats(Integer score, Integer linesCleared, Integer level, Integer piecesPlaced, Integer attackSent,
                        Integer attackReceived, Integer maxCombo) {
    }

    public record Saved(long matchId, long userId, String mode, boolean ranked, boolean newBest, int score) {
    }

    static final Duration UNSAVED_LIMIT = Duration.ofHours(2);
    private static final Set<String> DIFFICULTIES = Set.of("EASY", "NORMAL", "HARD");

    private final MatchRepository matches;
    private final UserRepository users;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public MatchService(MatchRepository matches, UserRepository users, Clock clock) {
        this.matches = matches;
        this.users = users;
        this.clock = clock;
    }

    @Transactional
    public Started start(long userId, String mode, String aiDifficulty) {
        boolean solo = "SOLO".equals(mode) && aiDifficulty == null;
        // Set.of(...).contains(null)은 NPE를 던지므로 null을 먼저 거른다
        boolean ai = "AI".equals(mode) && aiDifficulty != null && DIFFICULTIES.contains(aiDifficulty);
        if (!solo && !ai) throw new ApiException(ErrorCode.INVALID_MODE);
        Instant now = clock.instant();
        long seed = random.nextLong() >>> 32; // 0 ~ 2^32 − 1 (GR-01은 하위 32비트만 쓴다)
        long matchId = matches.insertMatch(mode, aiDifficulty, seed, now);
        matches.insertPlayer(matchId, 1, userId, "USER");
        if (ai) matches.insertPlayer(matchId, 2, null, "AI");
        return new Started(matchId, seed, now);
    }

    /** 경기·참가자·전적을 한 트랜잭션으로 갱신한다(BR-09). 랭킹 캐시는 호출자가 커밋 뒤에 반영한다. */
    @Transactional
    public Saved saveResult(long userId, long matchId, ResultInput input) {
        var match = matches.findForUpdate(matchId).orElseThrow(() -> new ApiException(ErrorCode.MATCH_NOT_FOUND));
        if ("FRIEND".equals(match.mode())) throw new ApiException(ErrorCode.INVALID_RESULT);
        if (match.ownerUserId() != userId) throw new ApiException(ErrorCode.NOT_PARTICIPANT);
        if (!"PLAYING".equals(match.status())) throw new ApiException(ErrorCode.MATCH_ALREADY_FINISHED);

        boolean aiMode = "AI".equals(match.mode());
        validate(input, aiMode);
        Stats p = input.player();
        int received = aiMode ? p.attackReceived() : 0;
        Instant now = clock.instant();
        long serverElapsedMs = Duration.between(match.startedAt(), now).toMillis();
        boolean ranked = ResultLimits.withinLimits(p.score(), p.linesCleared(), p.level(), p.piecesPlaced(), received,
                p.maxCombo(), input.durationMs(), serverElapsedMs);

        boolean win = "WIN".equals(input.result());
        matches.finish(matchId, now, input.durationMs(), ranked, aiMode && win ? userId : null);
        matches.updatePlayer(matchId, 1, aiMode ? input.result() : "NONE", toRow(p));
        boolean newBest = false;
        if (aiMode) {
            matches.updatePlayer(matchId, 2, win ? "LOSE" : "WIN", toRow(input.ai()));
            users.recordAiResult(userId, win, now);
        } else {
            users.incrementSoloGames(userId, now);
            newBest = ranked && users.raiseSoloBest(userId, p.score(), now);
        }
        return new Saved(matchId, userId, match.mode(), ranked, newBest, p.score());
    }

    /** BR-02: 시작 2시간이 지나도 결과가 없는 SOLO·AI 경기를 ABORTED(EXPIRED)로 닫는다. 전적은 바꾸지 않는다. */
    @Transactional
    public int expireUnsaved() {
        Instant now = clock.instant();
        return matches.expireUnsaved(now.minus(UNSAVED_LIMIT), now);
    }

    private static void validate(ResultInput in, boolean aiMode) {
        if (in == null || !nonNegative(in.durationMs()) || !requiredStats(in.player())) {
            throw new ApiException(ErrorCode.INVALID_RESULT);
        }
        Stats p = in.player();
        boolean ok = aiMode
                ? ("WIN".equals(in.result()) || "LOSE".equals(in.result()))
                    && nonNegative(p.attackSent()) && nonNegative(p.attackReceived())
                    && requiredStats(in.ai()) && nonNegative(in.ai().attackSent()) && nonNegative(in.ai().attackReceived())
                : in.result() == null && in.ai() == null && p.attackSent() == null && p.attackReceived() == null;
        if (!ok) throw new ApiException(ErrorCode.INVALID_RESULT);
    }

    private static boolean requiredStats(Stats s) {
        return s != null && nonNegative(s.score()) && nonNegative(s.linesCleared()) && nonNegative(s.level())
                && nonNegative(s.piecesPlaced()) && nonNegative(s.maxCombo());
    }

    private static boolean nonNegative(Integer value) {
        return value != null && value >= 0;
    }

    private static PlayerStats toRow(Stats s) {
        return new PlayerStats(s.score(), s.linesCleared(), s.level(), s.piecesPlaced(),
                s.attackSent() == null ? 0 : s.attackSent(), s.attackReceived() == null ? 0 : s.attackReceived(),
                s.maxCombo());
    }
}
