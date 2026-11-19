"""API 명세 4.1·4.2(API-015·017)와 FR-AI-01을 대조한다.

깨지면: FE가 받는 수의 형식·인증·오류 계약이 바뀌었거나, 반환한 입력을 재생했을 때 말한 자리에 놓이지 않거나,
난이도별 탐색 범위(EASY·NORMAL 1수, HARD 2수, IMPOSSIBLE 2수 + T-스핀 자리)가 어긋났다는 뜻이다.
"""
import time

import jwt
import pytest
from fastapi.testclient import TestClient

from app import main, rules, search
from app.main import JWT_SECRET, app

client = TestClient(app)


def token(secret=JWT_SECRET, exp_offset=600):
    return jwt.encode({"sub": "12", "exp": int(time.time()) + exp_offset}, secret, algorithm="HS256")


AUTH = {"Authorization": f"Bearer {token()}"}


def grid(bottom_rows):
    board = rules.board_from_rows(bottom_rows)
    return [[board[r] >> c & 1 for c in range(rules.WIDTH)] for r in range(rules.HEIGHT)]


def request(difficulty="IMPOSSIBLE", rows=(), current="T", hold=None, can_hold=False, nxt=("I", "O", "S", "Z", "L"), pending=0):
    return {"difficulty": difficulty, "board": grid(list(rows)), "current": current, "hold": hold,
            "canHold": can_hold, "next": list(nxt), "pendingGarbage": pending}


def replay(rows, piece, inputs):
    """FE처럼 생성 자리에서 입력을 재생해 최종 (회전, x, y)를 돌려준다"""
    board = rules.board_from_rows(list(rows))
    rot, x, y = 0, rules.SPAWN_X, rules.SPAWN_Y
    for action in inputs:
        if action == "LEFT" and rules.fits(board, piece, rot, x - 1, y):
            x -= 1
        elif action == "RIGHT" and rules.fits(board, piece, rot, x + 1, y):
            x += 1
        elif action == "SOFT_DROP" and rules.fits(board, piece, rot, x, y + 1):
            y += 1
        elif action in ("CW", "CCW"):
            rotated = rules.try_rotate(board, piece, rot, x, y, action == "CW")
            assert rotated is not None, f"{inputs}: 회전 실패"
            rot, x, y = rotated
        elif action == "HARD_DROP":
            return rot, x, rules.drop_y(board, piece, rot, x, y)
    raise AssertionError("HARD_DROP으로 끝나지 않음")


TSD_ROWS = ["GGGG000000", "GGG000GGGG", "GGGG0GGGGG"]  # 공용 lock-cases의 T-스핀 더블 자리


def test_health_needs_no_auth():
    res = client.get("/ai/v1/health")
    assert res.status_code == 200
    assert res.json() == {"success": True, "data": {"status": "UP"}, "error": None}


def test_move_returns_envelope_and_inputs_land_where_it_says():
    res = client.post("/ai/v1/move", json=request("HARD", rows=["GGGGGGGG00"], current="O"), headers=AUTH)
    assert res.status_code == 200
    body = res.json()
    assert body["success"] is True and body["error"] is None
    data = body["data"]
    assert set(data) == {"useHold", "piece", "rotation", "x", "inputs", "tSpin", "elapsedMs"}
    assert data["inputs"][-1] == "HARD_DROP"
    assert set(data["inputs"]) <= {"LEFT", "RIGHT", "CW", "CCW", "SOFT_DROP", "HARD_DROP", "HOLD"}
    rot, x, _ = replay(["GGGGGGGG00"], data["piece"], data["inputs"])
    assert (rot, x) == (data["rotation"], data["x"])
    # O를 빈 두 칸(열 8·9)에 넣어 줄을 지운다
    assert data["x"] == 7


def test_same_input_gives_same_move():
    req = request("IMPOSSIBLE", rows=TSD_ROWS)
    a = client.post("/ai/v1/move", json=req, headers=AUTH).json()["data"]
    b = client.post("/ai/v1/move", json=req, headers=AUTH).json()["data"]
    a.pop("elapsedMs"), b.pop("elapsedMs")
    assert a == b


def test_only_hard_searches_rotation_into_tspin_slot():
    hard = client.post("/ai/v1/move", json=request("IMPOSSIBLE", rows=TSD_ROWS), headers=AUTH).json()["data"]
    assert hard["tSpin"] is True
    assert (hard["rotation"], hard["x"]) == (2, 3)
    assert replay(TSD_ROWS, "T", hard["inputs"]) == (2, 3, 19)
    assert hard["inputs"][-2] in ("CW", "CCW")

    for difficulty in ("NORMAL", "HARD"):
        data = client.post("/ai/v1/move", json=request(difficulty, rows=TSD_ROWS), headers=AUTH).json()["data"]
        assert data["tSpin"] is False
        assert "SOFT_DROP" not in data["inputs"]


@pytest.mark.parametrize("difficulty, pieces_searched", [("NORMAL", {"S"}), ("HARD", {"S", "I"}), ("IMPOSSIBLE", {"S", "I"})])
def test_search_depth_by_difficulty(monkeypatch, difficulty, pieces_searched):
    """FR-AI-01 탐색 깊이: NORMAL은 현재 블록 1수, HARD·IMPOSSIBLE은 다음 블록까지 2수"""
    seen = []
    original = search.placements

    def spy(board, piece, allow_soft_drop):
        seen.append((piece, allow_soft_drop))
        return original(board, piece, allow_soft_drop)

    monkeypatch.setattr(search, "placements", spy)
    data = client.post("/ai/v1/move", json=request(difficulty, current="S", nxt=("I", "O")), headers=AUTH).json()["data"]
    assert {piece for piece, _ in seen} == pieces_searched
    assert {soft for _, soft in seen} == {difficulty == "IMPOSSIBLE"}
    assert replay([], "S", data["inputs"])[:2] == (data["rotation"], data["x"])


def test_hold_is_considered_when_allowed():
    rows = ["GGGGGGGGG0"] * 4
    data = client.post("/ai/v1/move", json=request("NORMAL", rows=rows, current="S", hold="I", can_hold=True), headers=AUTH).json()["data"]
    assert data["useHold"] is True
    assert data["piece"] == "I"
    assert data["inputs"][0] == "HOLD"
    assert replay(rows, "I", data["inputs"][1:])[:2] == (1, 7)


@pytest.mark.parametrize("difficulty", ["VERY_EASY", "EASY", "NORMAL", "HARD", "IMPOSSIBLE"])
def test_answers_fast_enough_for_150ms_budget(difficulty):
    req = request(difficulty, rows=["G000000000", "GG0G000GGG", "GGGG0GGGGG"], can_hold=True, hold="L")
    data = client.post("/ai/v1/move", json=req, headers=AUTH).json()["data"]
    assert data["elapsedMs"] < 150


@pytest.mark.parametrize("headers", [{}, {"Authorization": "Bearer nope"},
                                     {"Authorization": f"Bearer {token(secret='x' * 40)}"},
                                     {"Authorization": f"Bearer {token(exp_offset=-10)}"}])
def test_move_requires_valid_access_token(headers):
    res = client.post("/ai/v1/move", json=request(), headers=headers)
    assert res.status_code == 401
    assert res.json()["error"]["code"] == "UNAUTHORIZED"


@pytest.mark.parametrize("mutate", [
    lambda r: r.update(board=r["board"][:21]),
    lambda r: r["board"][0].append(0),
    lambda r: r["board"][5].__setitem__(3, 2),
    lambda r: r.update(current="X"),
    lambda r: r.update(next=["I", "O", "S", "Z", "L", "T"]),
    lambda r: r.update(pendingGarbage=-1),
    lambda r: r.update(difficulty="GOD"),
])
def test_bad_request_is_invalid_board(mutate):
    req = request()
    mutate(req)
    res = client.post("/ai/v1/move", json=req, headers=AUTH)
    assert res.status_code == 400
    assert res.json() == {"success": False, "data": None,
                          "error": {"code": "INVALID_BOARD", "message": res.json()["error"]["message"]}}


def test_easy_sometimes_plays_second_best():
    """T-35: EASY는 30% 확률로 두 번째로 좋은 수를 둔다. 깨지면 새 EASY가 NORMAL과 같아졌다는 뜻이다"""
    import random
    from app import rules, search
    board = rules.board_from_grid(grid(["G000000000", "GG0G000GGG"]))
    best = search.choose(board, "NORMAL", "T", None, False, ["I"], 0).placement
    picks = [search.choose(board, "EASY", "T", None, False, ["I"], 0, rng=random.Random(i)).placement for i in range(200)]
    mistakes = sum(p != best for p in picks)
    assert 30 <= mistakes <= 90


def test_very_easy_never_holds_and_errs_only_among_top_moves():
    """T-43: VERY_EASY는 홀드를 쓰지 않고, 실수해도 평가 상위 6개 안에서만 고른다. 깨지면 막 던지거나(너무 멍청함) 너무 강해진다"""
    import random
    from app import rules, search
    board = rules.board_from_grid(grid(["G000000000", "GG0G000GGG"]))
    ranked = sorted(search.placements(board, "T", False), key=lambda p: -(search.step_bonus(p, 0, False) + search.evaluate(p.board_after)))
    top = set(ranked[:search.VERY_EASY_TOP_K])
    moves = [search.choose(board, "VERY_EASY", "T", "I", True, ["I", "O"], 0, rng=random.Random(i)) for i in range(200)]
    assert not any(m.use_hold for m in moves)
    assert all(m.placement in top for m in moves)
    off = sum(m.placement != ranked[0] for m in moves)
    assert 60 <= off <= 140


def test_search_stops_at_deadline_and_picks_among_expanded():
    """보안 점검 M-1 기한. 깨지면: 계산이 기한을 넘겨도 끝까지 돌아 버려질 응답에 CPU를 쓰거나, 기한에 걸렸을 때 엉뚱한 수를 낸다.
    기한이 이미 지났으면 2수 탐색은 1수 평가 1위만 펼치므로 NORMAL(1수)과 같은 수를 낸다."""
    board = rules.board_from_rows(["GGGG0GGGGG", "GGGGGGG0GG"])  # 2수 탐색과 1수 탐색이 다른 자리를 고르는 보드
    full = search.choose(board, "HARD", "L", None, False, ["S", "S"], 0)
    normal = search.choose(board, "NORMAL", "L", None, False, ["S", "S"], 0)
    assert full.placement != normal.placement
    late = search.choose(board, "HARD", "L", None, False, ["S", "S"], 0, deadline=time.perf_counter() - 1)
    assert late.placement == normal.placement
    on_time = search.choose(board, "HARD", "L", None, False, ["S", "S"], 0, deadline=time.perf_counter() + 60)
    assert on_time == full


def test_busy_server_answers_503_without_queueing():
    """보안 점검 M-1 동시성 상한. 깨지면: 요청이 몰릴 때 줄을 세워 모든 응답이 150ms를 넘기고, 버려질 계산이 CPU를 계속 잡는다.
    FE는 5xx를 받으면 바로 규칙 AI로 대신 둔다."""
    held = 0
    while main._slots.acquire(blocking=False):
        held += 1
    try:
        res = client.post("/ai/v1/move", json=request("NORMAL"), headers=AUTH)
        assert res.status_code == 503
        assert res.json()["error"]["code"] == "AI_BUSY"
    finally:
        for _ in range(held):
            main._slots.release()
    assert client.post("/ai/v1/move", json=request("NORMAL"), headers=AUTH).status_code == 200
