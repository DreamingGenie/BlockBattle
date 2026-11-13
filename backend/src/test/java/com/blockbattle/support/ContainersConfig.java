package com.blockbattle.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 통합 시험용 MySQL 8·Redis 7 컨테이너(실 저장소 계약 확인용, 느린 층). */
@TestConfiguration(proxyBeanMethods = false)
public class ContainersConfig {

    @Bean
    @ServiceConnection
    MySQLContainer<?> mysql() {
        return new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    }
}
