package com.blockbattle.room;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 비활성 방 만료 확인(30초 주기, T-10 계획 리비전 200 unlinked) */
@Component
public class RoomExpiryJob {

    private final RoomService rooms;

    public RoomExpiryJob(RoomService rooms) {
        this.rooms = rooms;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void run() {
        rooms.expireRooms();
    }
}
