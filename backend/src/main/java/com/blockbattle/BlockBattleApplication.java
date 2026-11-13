package com.blockbattle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BlockBattleApplication {

    public static void main(String[] args) {
        SpringApplication.run(BlockBattleApplication.class, args);
    }
}
