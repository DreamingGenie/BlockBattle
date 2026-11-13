"""bag.json 기대 순서를 만든다. FE(TypeScript) 구현과 독립된 Python 참조 구현이다(GR-01, POL-014).

python shared/rules-fixtures/gen_bag.py   # 같은 폴더의 bag.json을 다시 쓴다
"""
import json
from pathlib import Path

MASK = 0xFFFFFFFF


def imul(a: int, b: int) -> int:
    return (a * b) & MASK


def mulberry32(seed: int):
    state = seed & MASK  # BIGINT 하위 32비트를 부호 없는 정수로

    def rand() -> float:
        nonlocal state
        state = (state + 0x6D2B79F5) & MASK
        t = imul(state ^ (state >> 15), 1 | state)
        t = ((t + imul(t ^ (t >> 7), 61 | t)) & MASK) ^ t
        return ((t ^ (t >> 14)) & MASK) / 4294967296

    return rand


def sequence(seed: int, count: int) -> list[str]:
    rand = mulberry32(seed)
    out: list[str] = []
    while len(out) < count:
        bag = list("IOTSZJL")
        for i in range(len(bag) - 1, 0, -1):
            j = int(rand() * (i + 1))
            bag[i], bag[j] = bag[j], bag[i]
        out.extend(bag)
    return out[:count]


SEEDS = ["0", "1", "42", "123456789", "4294967295", "4294967296", "-1", "9007199254740993", "-9223372036854775808"]

if __name__ == "__main__":
    cases = [{"seed": s, "seedUint32": int(s) & MASK, "first21": sequence(int(s), 21)} for s in SEEDS]
    text = json.dumps({
        "rule": "GR-01: seed(BIGINT) 하위 32비트 → mulberry32, 묶음마다 [I,O,T,S,Z,J,L] Fisher-Yates(뒤에서부터 j=floor(rand*(i+1)))",
        "cases": cases,
    }, ensure_ascii=False, indent=2)
    Path(__file__).with_name("bag.json").write_bytes((text + "\n").encode("utf-8"))
