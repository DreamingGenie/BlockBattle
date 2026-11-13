package com.blockbattle.engine;

/** 블록 위치: 회전(0=생성 1=R 2 3=L)과 회전 상자 왼쪽 위 칸(열 x, 행 y, 행 0이 맨 위) */
public record Placement(Piece piece, int rotation, int x, int y) {
}
