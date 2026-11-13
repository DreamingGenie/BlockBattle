package com.blockbattle.engine;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** 22행 × 10열 보드(GR-01, CON-001). 문자열은 맨 위 행부터 왼쪽→오른쪽 220자, '0' 빈칸·블록 문자·'G' 방해 줄. 불변 객체. */
public final class Board {

    public static final int WIDTH = 10;
    public static final int HEIGHT = 22;
    private static final Pattern FORMAT = Pattern.compile("[0IOTSZJLG]{220}");

    private final char[] cells;

    private Board(char[] cells) {
        this.cells = cells;
    }

    public static Board empty() {
        char[] cells = new char[WIDTH * HEIGHT];
        Arrays.fill(cells, '0');
        return new Board(cells);
    }

    public static Board parse(String value) {
        if (value == null || !FORMAT.matcher(value).matches()) throw new IllegalArgumentException("board는 [0IOTSZJLG] 220자");
        return new Board(value.toCharArray());
    }

    /** 아래쪽 행만 위→아래 순서로 적은 보드(공용 시험 데이터 형식) */
    public static Board fromBottomRows(List<String> rows) {
        return parse("0".repeat(WIDTH * (HEIGHT - rows.size())) + String.join("", rows));
    }

    @Override
    public String toString() {
        return new String(cells);
    }

    /** 벽·바닥·천장 밖이거나 채워진 칸이면 막힘 */
    public boolean isBlocked(int row, int col) {
        if (row < 0 || row >= HEIGHT || col < 0 || col >= WIDTH) return true;
        return cells[row * WIDTH + col] != '0';
    }

    public boolean fits(Placement p) {
        for (int[] rc : p.piece().cells(p.rotation())) {
            if (isBlocked(p.y() + rc[0], p.x() + rc[1])) return false;
        }
        return true;
    }

    /** 바로 아래가 막혀 있는지(BR-05 ③) */
    public boolean isGrounded(Placement p) {
        return !fits(new Placement(p.piece(), p.rotation(), p.x(), p.y() + 1));
    }

    public Board place(Placement p) {
        char[] next = cells.clone();
        for (int[] rc : p.piece().cells(p.rotation())) next[(p.y() + rc[0]) * WIDTH + p.x() + rc[1]] = p.piece().letter();
        return new Board(next);
    }

    /** 가득 찬 행을 지우고 위를 내린다. [새 보드, 지운 줄 수] */
    public Cleared clearLines() {
        char[] next = new char[WIDTH * HEIGHT];
        Arrays.fill(next, '0');
        int write = HEIGHT - 1;
        int cleared = 0;
        for (int r = HEIGHT - 1; r >= 0; r--) {
            boolean full = true;
            for (int c = 0; c < WIDTH; c++) if (cells[r * WIDTH + c] == '0') { full = false; break; }
            if (full) { cleared++; continue; }
            System.arraycopy(cells, r * WIDTH, next, write * WIDTH, WIDTH);
            write--;
        }
        return new Cleared(new Board(next), cleared);
    }

    public record Cleared(Board board, int lines) {
    }

    public boolean isEmpty() {
        for (char c : cells) if (c != '0') return false;
        return true;
    }

    /** 방해 줄을 아래에서 올린다(GR-08). 묶음 순서대로 올리므로 먼저 받은 묶음이 위에 놓인다 */
    public Board raiseGarbage(List<GarbageBundle> bundles) {
        char[] current = cells.clone();
        for (GarbageBundle bundle : bundles) {
            char[] next = new char[WIDTH * HEIGHT];
            int shift = bundle.lines() * WIDTH;
            System.arraycopy(current, shift, next, 0, WIDTH * HEIGHT - shift);
            for (int r = HEIGHT - bundle.lines(); r < HEIGHT; r++) {
                for (int c = 0; c < WIDTH; c++) next[r * WIDTH + c] = c == bundle.holeColumn() ? '0' : 'G';
            }
            current = next;
        }
        return new Board(current);
    }
}
