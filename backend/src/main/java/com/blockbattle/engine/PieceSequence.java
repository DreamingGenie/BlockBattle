package com.blockbattle.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 7-bag(GR-01, POL-014): 시드(BIGINT)의 하위 32비트로 mulberry32를 초기화하고, 묶음마다 [I,O,T,S,Z,J,L]을
 * Fisher-Yates(뒤에서부터 j = floor(rand × (i + 1)))로 섞는다. FE(TypeScript)·AI(Python)와 같은 수열을 낸다.
 */
public final class PieceSequence {

    private int state;
    private final List<Piece> pieces = new ArrayList<>();

    public PieceSequence(long seed) {
        this.state = (int) seed; // 하위 32비트
    }

    public Piece at(int index) {
        while (pieces.size() <= index) pieces.addAll(nextBag());
        return pieces.get(index);
    }

    /** mulberry32 — JS의 Math.imul·>>> 연산과 같은 32비트 정수 연산 */
    double nextRandom() {
        state = state + 0x6D2B79F5;
        int t = (state ^ (state >>> 15)) * (1 | state);
        t = (t + ((t ^ (t >>> 7)) * (61 | t))) ^ t;
        return ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
    }

    private List<Piece> nextBag() {
        Piece[] bag = Arrays.copyOf(Piece.BAG_ORDER, Piece.BAG_ORDER.length);
        for (int i = bag.length - 1; i > 0; i--) {
            int j = (int) Math.floor(nextRandom() * (i + 1));
            Piece tmp = bag[i];
            bag[i] = bag[j];
            bag[j] = tmp;
        }
        return Arrays.asList(bag);
    }
}
