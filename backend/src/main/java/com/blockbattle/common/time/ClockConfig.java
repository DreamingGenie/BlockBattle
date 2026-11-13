package com.blockbattle.common.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** 시각 저장은 UTC. 시험에서 바꿔 끼울 수 있도록 Clock을 주입받는다. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
