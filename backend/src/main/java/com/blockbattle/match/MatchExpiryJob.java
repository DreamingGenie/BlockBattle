package com.blockbattle.match;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** BR-02 결과 미저장 SOLO·AI 경기 만료를 1분마다 확인한다(주기는 T-5 계획에서 정함) */
@Component
public class MatchExpiryJob {

    private final MatchService matches;

    public MatchExpiryJob(MatchService matches) {
        this.matches = matches;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void run() {
        matches.expireUnsaved();
    }
}
