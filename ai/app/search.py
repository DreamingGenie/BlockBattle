"""난이도별 다음 수 탐색(FR-AI-01).

- 자리 찾기: 생성 자리에서 LEFT·RIGHT·CW·CCW(·SOFT_DROP)를 넓이 우선으로 탐색해 각 자리까지의 가장 짧은 입력을 얻는다.
  EASY·NORMAL은 위에서 회전·이동 후 HARD_DROP하는 자리만, HARD는 소프트 드롭 뒤 밀어 넣기·회전으로 들어가는 자리까지 본다.
- 깊이: EASY 1수, NORMAL·HARD 2수(다음 블록까지, 상위 BEAM개만 펼침).
- 평가: GR-09 가중치 + T-스핀(HARD)·방해 줄 대기 시 줄 삭제 가산점. 난수 없이 결정론적이다.
"""
from __future__ import annotations

from collections import deque
from dataclasses import dataclass

from . import rules

BEAM = 10
W_HEIGHT, W_LINES, W_HOLES, W_BUMP = -0.51, 0.76, -0.36, -0.18
TSPIN_BONUS_PER_LINE = 2.0
GARBAGE_CLEAR_BONUS_PER_LINE = 0.5


@dataclass(frozen=True)
class Placement:
    piece: str
    rotation: int
    x: int
    y: int
    inputs: tuple[str, ...]
    tspin: bool
    board_after: tuple[int, ...]
    lines: int


@dataclass(frozen=True)
class Move:
    use_hold: bool
    placement: Placement
    score: float


def placements(board: rules.Board, piece: str, allow_soft_drop: bool) -> list[Placement]:
    """도달 가능한 모든 고정 자리와 그 입력 순서(마지막은 HARD_DROP)"""
    start = (0, rules.SPAWN_X, rules.SPAWN_Y)
    if not rules.fits(board, piece, *start):
        return []
    parent: dict[tuple[int, int, int], tuple[tuple[int, int, int] | None, str | None]] = {start: (None, None)}
    queue = deque([start])
    found: dict[tuple[int, int, int, bool], Placement] = {}

    def path_to(state):
        actions = []
        while parent[state][0] is not None:
            prev, action = parent[state]
            actions.append(action)
            state = prev
        return actions[::-1]

    # (rot, x, y) → 떨어진 y. drop_y(y) == drop_y(y + 1) (y + 1에 들어갈 때)라서 같은 열을 다시 내려가지 않는다(T-24)
    landings: dict[tuple[int, int, int], int] = {}

    def land(rot, x, y):
        walked = []
        while (rot, x, y) not in landings and rules.fits(board, piece, rot, x, y + 1):
            walked.append(y)
            y += 1
        result = landings.get((rot, x, y), y)
        for wy in walked:
            landings[(rot, x, wy)] = result
        landings[(rot, x, y)] = result
        return result

    while queue:
        state = queue.popleft()
        rot, x, y = state
        landing = land(rot, x, y)
        # 마지막 동작은 부모 기록에서 바로 읽고, 입력 경로는 새 고정 자리일 때만 복원한다(T-24)
        last_rotate = landing == y and parent[state][1] in ("CW", "CCW")
        key = (rot, x, landing, last_rotate and piece == "T")
        if key not in found:
            tspin = rules.is_tspin(board, piece, rot, x, landing, last_rotate)
            after, lines = rules.clear_lines(rules.place(board, piece, rot, x, landing))
            found[key] = Placement(piece, rot, x, landing, tuple(path_to(state) + ["HARD_DROP"]), tspin, tuple(after), lines)

        nexts = [("LEFT", (rot, x - 1, y)), ("RIGHT", (rot, x + 1, y))]
        for action, clockwise in (("CW", True), ("CCW", False)):
            rotated = rules.try_rotate(board, piece, rot, x, y, clockwise)
            if rotated:
                nexts.append((action, rotated))
        if allow_soft_drop:
            nexts.append(("SOFT_DROP", (rot, x, y + 1)))
        for action, nxt in nexts:
            if nxt in parent:
                continue
            if action in ("LEFT", "RIGHT", "SOFT_DROP") and not rules.fits(board, piece, *nxt):
                continue
            parent[nxt] = (state, action)
            queue.append(nxt)
    return list(found.values())


def evaluate(board: tuple[int, ...] | list[int]) -> float:
    heights = []
    holes = 0
    for c in range(rules.WIDTH):
        top = next((r for r in range(rules.HEIGHT) if board[r] >> c & 1), rules.HEIGHT)
        heights.append(rules.HEIGHT - top)
        holes += sum(1 for r in range(top + 1, rules.HEIGHT) if not board[r] >> c & 1)
    bump = sum(abs(heights[i] - heights[i + 1]) for i in range(rules.WIDTH - 1))
    return W_HEIGHT * sum(heights) + W_HOLES * holes + W_BUMP * bump


def step_bonus(p: Placement, pending_garbage: int, tspin_search: bool) -> float:
    bonus = W_LINES * p.lines
    if tspin_search and p.tspin and p.lines > 0:
        bonus += TSPIN_BONUS_PER_LINE * p.lines
    if pending_garbage > 0:
        bonus += GARBAGE_CLEAR_BONUS_PER_LINE * p.lines
    return bonus


def choose(board: rules.Board, difficulty: str, current: str, hold: str | None, can_hold: bool,
           next_pieces: list[str], pending_garbage: int) -> Move | None:
    hard = difficulty == "HARD"
    depth = 1 if difficulty == "EASY" else 2

    # (홀드 사용 여부, 이번에 놓을 블록, 그다음 블록)
    options: list[tuple[bool, str, str | None]] = [(False, current, next_pieces[0] if next_pieces else None)]
    if can_hold:
        if hold is not None:
            options.append((True, hold, next_pieces[0] if next_pieces else None))
        elif next_pieces:
            options.append((True, next_pieces[0], next_pieces[1] if len(next_pieces) > 1 else None))

    first_moves: list[tuple[float, int, bool, Placement, str | None]] = []
    order = 0
    for use_hold, piece, following in options:
        for p in placements(board, piece, allow_soft_drop=hard):
            score = step_bonus(p, pending_garbage, hard) + evaluate(p.board_after)
            first_moves.append((score, order, use_hold, p, following))
            order += 1
    if not first_moves:
        return None

    if depth == 2:
        beam = sorted(first_moves, key=lambda m: (-m[0], m[1]))[:BEAM]
        rescored = []
        for score, idx, use_hold, p, following in beam:
            total = score
            if following is not None:
                seconds = placements(list(p.board_after), following, allow_soft_drop=hard)
                if seconds:
                    total = step_bonus(p, pending_garbage, hard) + max(
                        step_bonus(q, pending_garbage, hard) + evaluate(q.board_after) for q in seconds)
            rescored.append((total, idx, use_hold, p, following))
        first_moves = rescored

    best = min(first_moves, key=lambda m: (-m[0], m[1]))
    return Move(best[2], best[3], best[0])
