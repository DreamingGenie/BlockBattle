package com.blockbattle.battle;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 대전 지연 판정(topout 100ms 뒤 동시 여부, 끊김 10초 뒤 재접속 여부, RESYNC 전송 지연).
 * 브로커용 TaskScheduler와 섞이지 않게 전용 스레드를 쓴다(단일 서버 기준, T-12 판단과 같음).
 */
@Component
public class BattleScheduler {

    private static final Logger log = LoggerFactory.getLogger(BattleScheduler.class);
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "battle-timer");
        t.setDaemon(true);
        return t;
    });

    public void after(Duration delay, Runnable task) {
        executor.schedule(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("대전 지연 작업 실패", e);
            }
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
