"""AI 서버(API 명세 4장): POST /ai/v1/move, GET /ai/v1/health. 응답은 REST와 같은 봉투를 쓴다."""
from __future__ import annotations

import os
import threading
import time
from typing import Literal

import jwt
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, field_validator

from . import rules, search

JWT_SECRET = os.environ.get("JWT_SECRET", "local-dev-only-secret-change-me-0123456789abcdef")
PREFIX = "/ai/v1"

# 보안 점검 M-1: 계산이 몰려도 CPU를 붙잡지 않게 한다. 클라이언트는 150ms를 넘기면 버리고 규칙 AI로 대신 두므로(FR-AI-02)
# 계산은 SEARCH_BUDGET_S 안에서 끊고, 동시에 MAX_CONCURRENT개를 넘는 요청은 줄 세우지 않고 바로 503으로 돌려보낸다.
SEARCH_BUDGET_S = 0.12
MAX_CONCURRENT = int(os.environ.get("AI_MAX_CONCURRENT", "2"))
_slots = threading.BoundedSemaphore(MAX_CONCURRENT)

app = FastAPI(title="block-battle-ai")

Piece = Literal["I", "O", "T", "S", "Z", "J", "L"]


class MoveRequest(BaseModel):
    difficulty: Literal["VERY_EASY", "EASY", "NORMAL", "HARD", "IMPOSSIBLE"]
    board: list[list[int]]
    current: Piece
    hold: Piece | None = None
    canHold: bool
    next: list[Piece] = Field(max_length=5)
    pendingGarbage: int = Field(ge=0)

    @field_validator("board")
    @classmethod
    def board_shape(cls, board: list[list[int]]) -> list[list[int]]:
        if len(board) != rules.HEIGHT or any(len(row) != rules.WIDTH for row in board):
            raise ValueError("board는 22 × 10이어야 한다")
        if any(v not in (0, 1) for row in board for v in row):
            raise ValueError("board 값은 0·1이어야 한다")
        return board


def envelope(data=None, code: str | None = None, message: str | None = None, status: int = 200) -> JSONResponse:
    error = None if code is None else {"code": code, "message": message}
    return JSONResponse(status_code=status, content={"success": code is None, "data": data, "error": error})


@app.exception_handler(RequestValidationError)
async def invalid_request(_: Request, __: RequestValidationError) -> JSONResponse:
    # 요청 형식 오류는 명세의 유일한 400 코드인 INVALID_BOARD로 본다(T-7 계획 unlinked)
    return envelope(code="INVALID_BOARD", message="보드나 요청 형식이 올바르지 않습니다.", status=400)


@app.middleware("http")
async def require_access_token(request: Request, call_next):
    """GET /health를 뺀 요청은 Access JWT(HS256, BE와 같은 비밀 키)가 필요하다"""
    if request.url.path != f"{PREFIX}/health":
        header = request.headers.get("authorization", "")
        if not header.startswith("Bearer "):
            return envelope(code="UNAUTHORIZED", message="인증이 필요합니다.", status=401)
        try:
            jwt.decode(header[len("Bearer "):], JWT_SECRET, algorithms=["HS256"], options={"require": ["sub", "exp"]})
        except jwt.PyJWTError:
            return envelope(code="UNAUTHORIZED", message="인증이 필요합니다.", status=401)
    return await call_next(request)


@app.get(f"{PREFIX}/health")
def health() -> JSONResponse:
    return envelope({"status": "UP"})


@app.post(f"{PREFIX}/move")
def move(req: MoveRequest) -> JSONResponse:
    if not _slots.acquire(blocking=False):
        return envelope(code="AI_BUSY", message="AI 서버가 바쁩니다.", status=503)
    try:
        return _move(req)
    finally:
        _slots.release()


def _move(req: MoveRequest) -> JSONResponse:
    started = time.perf_counter()
    board = rules.board_from_grid(req.board)
    chosen = search.choose(board, req.difficulty, req.current, req.hold, req.canHold, list(req.next), req.pendingGarbage,
                           deadline=started + SEARCH_BUDGET_S)
    if chosen is None:
        # 생성 자리부터 막혀 놓을 곳이 없다(topout 직전). 그대로 떨어뜨린다.
        data = {"useHold": False, "piece": req.current, "rotation": 0, "x": rules.SPAWN_X,
                "inputs": ["HARD_DROP"], "tSpin": False}
    else:
        p = chosen.placement
        inputs = (["HOLD"] if chosen.use_hold else []) + list(p.inputs)
        data = {"useHold": chosen.use_hold, "piece": p.piece, "rotation": p.rotation, "x": p.x,
                "inputs": inputs, "tSpin": p.tspin}
    data["elapsedMs"] = round((time.perf_counter() - started) * 1000)
    return envelope(data)
