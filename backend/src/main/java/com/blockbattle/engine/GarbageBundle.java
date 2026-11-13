package com.blockbattle.engine;

/** 대기 방해 줄 한 묶음: 모든 줄의 구멍이 같은 열(GR-08) */
public record GarbageBundle(int lines, int holeColumn) {
}
