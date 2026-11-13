package com.blockbattle.common.time;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** REST 응답 시각: ISO-8601 오프셋 포함(API 명세 1장). 서비스 지역 기준 +09:00으로 낸다. */
public final class ApiTime {

    public static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private ApiTime() {
    }

    public static OffsetDateTime of(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
