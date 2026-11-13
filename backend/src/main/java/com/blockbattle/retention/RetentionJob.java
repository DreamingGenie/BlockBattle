package com.blockbattle.retention;

import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** POL-010·POL-023: 매일 04:00(KST)에 보존 기간이 지난 경기·방을 지운다 */
@Component
public class RetentionJob {

    static final String CRON = "0 0 4 * * *";
    static final String ZONE = "Asia/Seoul";

    private final RetentionService retention;
    private final Clock clock;

    public RetentionJob(RetentionService retention, Clock clock) {
        this.retention = retention;
        this.clock = clock;
    }

    @Scheduled(cron = CRON, zone = ZONE)
    public void run() {
        retention.purge(clock.instant());
    }
}
