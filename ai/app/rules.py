"""게임 규칙(GR-01·GR-04·GR-05) — 모양·SRS 킥 표는 shared/rules-fixtures/srs.json에서 읽는다(CON-002).

보드는 22행 bitmask 목록이다. 행 0이 맨 위, 비트 c가 열 c(왼쪽 0)다.
블록 위치 (rotation, x, y)는 회전 상자 왼쪽 위 칸이며 FE 엔진·공용 시험 데이터와 같다.
"""
from __future__ import annotations

import json
import os
from pathlib import Path

WIDTH = 10
HEIGHT = 22
FULL_ROW = (1 << WIDTH) - 1
SPAWN_X = 3
SPAWN_Y = 0
PIECES = ("I", "O", "T", "S", "Z", "J", "L")

_RULES_DIR = Path(os.environ.get("RULES_DIR", Path(__file__).resolve().parents[2] / "shared" / "rules-fixtures"))
SRS = json.loads((_RULES_DIR / "srs.json").read_text(encoding="utf-8"))
SHAPES: dict[str, list[list[tuple[int, int]]]] = {
    piece: [[(r, c) for r, c in rotation] for rotation in rotations] for piece, rotations in SRS["shapes"].items()
}

Board = list[int]


def empty_board() -> Board:
    return [0] * HEIGHT


def cells(piece: str, rotation: int, x: int, y: int) -> list[tuple[int, int]]:
    return [(y + r, x + c) for r, c in SHAPES[piece][rotation]]


def is_blocked(board: Board, row: int, col: int) -> bool:
    """벽·바닥·천장 밖이거나 채워진 칸이면 막힘"""
    if row < 0 or row >= HEIGHT or col < 0 or col >= WIDTH:
        return True
    return bool(board[row] >> col & 1)


def fits(board: Board, piece: str, rotation: int, x: int, y: int) -> bool:
    return not any(is_blocked(board, r, c) for r, c in cells(piece, rotation, x, y))


def kicks(piece: str, src: int, dst: int) -> list[tuple[int, int]]:
    if piece == "O":
        return [(0, 0)]
    table = SRS["kicks"]["I" if piece == "I" else "JLSTZ"]
    return [tuple(k) for k in table[f"{src}>{dst}"]]


def try_rotate(board: Board, piece: str, rotation: int, x: int, y: int, clockwise: bool):
    """SRS 회전. 킥의 dy는 위쪽이 + 라서 새 y = y - dy. 모두 막히면 None"""
    dst = (rotation + (1 if clockwise else 3)) % 4
    for dx, dy in kicks(piece, rotation, dst):
        if fits(board, piece, dst, x + dx, y - dy):
            return dst, x + dx, y - dy
    return None


def drop_y(board: Board, piece: str, rotation: int, x: int, y: int) -> int:
    while fits(board, piece, rotation, x, y + 1):
        y += 1
    return y


def place(board: Board, piece: str, rotation: int, x: int, y: int) -> Board:
    result = list(board)
    for r, c in cells(piece, rotation, x, y):
        result[r] |= 1 << c
    return result


def clear_lines(board: Board) -> tuple[Board, int]:
    kept = [row for row in board if row != FULL_ROW]
    cleared = HEIGHT - len(kept)
    return [0] * cleared + kept, cleared


def is_tspin(board: Board, piece: str, rotation: int, x: int, y: int, last_action_rotate: bool) -> bool:
    """GR-05: T, 마지막 성공 동작이 회전, 중심 기준 대각선 네 칸 중 3칸 이상 막힘(벽·바닥 포함). board는 놓기 전"""
    if piece != "T" or not last_action_rotate:
        return False
    corners = [(y, x), (y, x + 2), (y + 2, x), (y + 2, x + 2)]
    return sum(is_blocked(board, r, c) for r, c in corners) >= 3


def board_from_rows(rows: list[str]) -> Board:
    """공용 시험 데이터 형식(아래쪽 행만, 위→아래)의 문자 보드를 occupancy로"""
    padded = ["0" * WIDTH] * (HEIGHT - len(rows)) + rows
    return [sum(1 << c for c, ch in enumerate(row) if ch != "0") for row in padded]


def board_from_grid(grid: list[list[int]]) -> Board:
    """API 요청 board(22 × 10, 0·1)를 bitmask로"""
    return [sum(1 << c for c, v in enumerate(row) if v) for row in grid]
