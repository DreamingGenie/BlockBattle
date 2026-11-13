"""공용 규칙 시험 데이터(shared/rules-fixtures)를 AI 서버 엔진으로 돌린다(CON-002).

깨지면: AI가 FE·BE와 다른 회전·줄 삭제·T-스핀 규칙으로 수를 계산한다는 뜻이다(엉뚱한 자리를 고르거나 놓을 수 없는 수를 낸다).
"""
import json

import pytest

from app import rules

FIXTURES = rules._RULES_DIR
SRS = json.loads((FIXTURES / "srs.json").read_text(encoding="utf-8"))
LOCKS = json.loads((FIXTURES / "lock-cases.json").read_text(encoding="utf-8"))


@pytest.mark.parametrize("case", SRS["rotationCases"], ids=lambda c: c["name"])
def test_rotation_cases(case):
    board = rules.board_from_rows(case["board"]["bottomRows"])
    s = case["start"]
    result = rules.try_rotate(board, s["piece"], s["rotation"], s["x"], s["y"], case["direction"] == "CW")
    expected = case["expected"]
    assert result == (None if expected is None else (expected["rotation"], expected["x"], expected["y"]))


@pytest.mark.parametrize("case", LOCKS["cases"], ids=lambda c: c["name"])
def test_lock_cases_lines_and_tspin(case):
    board = rules.board_from_rows(case["state"]["bottomRows"])
    lock = case["lock"]
    expected = case["expected"]
    assert rules.fits(board, lock["piece"], lock["rotation"], lock["x"], lock["y"])
    tspin = rules.is_tspin(board, lock["piece"], lock["rotation"], lock["x"], lock["y"], lock["lastAction"] == "rotate")
    after, lines = rules.clear_lines(rules.place(board, lock["piece"], lock["rotation"], lock["x"], lock["y"]))
    assert (lines, tspin) == (expected["linesCleared"], expected["tSpin"])
    if expected["garbageRaised"] == 0:  # 방해 줄 상승은 AI 탐색 범위 밖
        assert after == rules.board_from_rows(expected["bottomRows"])
