package com.blockbattle.room;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/** 6자리 게임 코드: 대문자·숫자, 헷갈리는 0·O·1·I 제외(FR-ROM-01) */
@Component
public class RoomCodeGenerator {

    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final SecureRandom random = new SecureRandom();

    public String next() {
        StringBuilder code = new StringBuilder(6);
        for (int i = 0; i < 6; i++) code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return code.toString();
    }
}
