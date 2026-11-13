package com.blockbattle.auth;

import com.blockbattle.common.error.ApiException;
import com.blockbattle.common.error.ErrorCode;
import com.blockbattle.user.UserRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 게스트 계정 생성(FR-USR-01, REQ-001). 같은 닉네임으로 다시 로그인하면 새 게스트 계정이 생긴다.
 * 태그는 그 닉네임에서 아직 안 쓰인 0000~9999 중 무작위다.
 */
@Service
public class GuestAccountService {

    public record Guest(long userId, String nickname, String tag) {
    }

    static final Pattern NICKNAME = Pattern.compile("^[가-힣A-Za-z0-9]{2,10}$");
    static final int TAG_SPACE = 10_000;
    private static final int RANDOM_ATTEMPTS = 5;

    private final UserRepository users;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public GuestAccountService(UserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    /** users·user_stats 행을 한 트랜잭션으로 만든다 */
    @Transactional
    public Guest create(String nickname) {
        if (nickname == null || !NICKNAME.matcher(nickname).matches()) {
            throw new ApiException(ErrorCode.INVALID_NICKNAME);
        }
        Instant now = clock.instant();
        for (int i = 0; i < RANDOM_ATTEMPTS; i++) {
            Guest guest = tryInsert(nickname, format(random.nextInt(TAG_SPACE)), now);
            if (guest != null) return guest;
        }
        // 무작위가 계속 겹치면 빈 태그를 조회해 고른다. 동시에 같은 태그를 잡았으면 다시 고른다.
        while (true) {
            List<String> free = freeTags(users.findUsedTags(nickname));
            if (free.isEmpty()) throw new ApiException(ErrorCode.NICKNAME_TAG_EXHAUSTED);
            Guest guest = tryInsert(nickname, free.get(random.nextInt(free.size())), now);
            if (guest != null) return guest;
        }
    }

    /** UQ(nickname, tag)에 걸리면 null. InnoDB는 중복 키 오류를 문장 단위로 되돌려 트랜잭션은 이어 쓸 수 있다. */
    private Guest tryInsert(String nickname, String tag, Instant now) {
        try {
            long userId = users.insertGuest(nickname, tag, now);
            users.insertStats(userId, now);
            return new Guest(userId, nickname, tag);
        } catch (DuplicateKeyException e) {
            return null;
        }
    }

    private static List<String> freeTags(Set<String> used) {
        List<String> free = new ArrayList<>();
        for (int i = 0; i < TAG_SPACE; i++) {
            String tag = format(i);
            if (!used.contains(tag)) free.add(tag);
        }
        return free;
    }

    private static String format(int tag) {
        return String.format("%04d", tag);
    }
}
