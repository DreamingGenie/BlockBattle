"""T-17 부하 시험 결과 보고서 생성: k6 summary와 docker stats 샘플로 기준별 합·불을 판정한다.

사용: python report.py <결과 폴더> <k6 종료 코드>
CPU 평균은 유지 구간(시작 + RAMP_S ~ + RAMP_S + HOLD_S)의 샘플만 쓰고, 2 vCPU 기준 사용률 = docker CPU% ÷ 2.
"""
import csv
import json
import sys
from pathlib import Path


def metric(summary, name, key):
    m = summary["metrics"].get(name)
    if not m:
        return 0
    return m.get(key, m.get("count", 0))


def main(out_dir: str, k6_exit: str) -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    out = Path(out_dir)
    start, rooms, ramp, hold = (int(v) for v in (out / "run.txt").read_text().split())
    summary = json.loads((out / "summary.json").read_text(encoding="utf-8"))
    hold_from, hold_to = start + ramp, start + ramp + hold

    samples: dict[str, list[float]] = {"lt-backend": [], "lt-mysql": [], "lt-redis": [], "k6": []}
    per_epoch: dict[int, float] = {}
    with (out / "cpu.csv").open(encoding="utf-8") as f:
        for row in csv.DictReader(f):
            epoch = int(row["epoch"])
            if not hold_from <= epoch < hold_to:
                continue
            cpu = float(row["cpu_percent"].rstrip("%"))
            if row["name"].startswith("lt-"):
                samples[row["name"]].append(cpu)
                per_epoch[epoch] = per_epoch.get(epoch, 0.0) + cpu
            else:  # 부하 발생기(k6) — 서버 CPU에 넣지 않고 참고로만 적는다
                samples["k6"].append(cpu)

    def avg(values):
        return sum(values) / len(values) if values else float("nan")

    backend_cpu = avg(samples["lt-backend"]) / 2
    total_cpu = avg(list(per_epoch.values())) / 2
    p95 = metric(summary, "lock_to_opponent_board", "p(95)")
    p95_count = metric(summary, "lock_to_opponent_board", "count")
    failure_rate = summary["metrics"].get("ws_failure", {}).get("value", 0)
    invalid = metric(summary, "invalid_lock", "count")
    locks = metric(summary, "locks_sent", "count")
    windows = metric(summary, "attack_profile_windows", "count")
    windows_ok = metric(summary, "attack_profile_windows_ok", "count")
    topouts = metric(summary, "topouts", "count")
    skipped = metric(summary, "lock_ticks_skipped", "count")
    attacks = metric(summary, "attack_events", "count")
    attack_lines = metric(summary, "attack_lines", "count")

    def verdict(ok):
        return "합격" if ok else "불합격"

    lines = [
        "# T-17 동시 100방 부하 시험 결과 (로컬 근사 측정)",
        "",
        "> **이 결과는 로컬 PC의 Docker에서 잰 근사값이다.** 백엔드 컨테이너를 CPU 2개·메모리 4GB로 제한했고,",
        "> k6·MySQL·Redis가 같은 호스트에서 돌았다(네트워크 지연은 실제보다 작고, 호스트 CPU 경합은 크다).",
        "> CPU는 node exporter가 아니라 docker stats로 쟀다. **같은 리전 2 vCPU·4GB 서버에서의 공식 합격 판정(REQ-016·REQ-017·REQ-022)을 대신하지 않는다.**",
        "",
        f"- 실행: 방 {rooms}개(가상 사용자 {rooms * 2}명), {ramp}초 동안 늘린 뒤 {hold}초 유지. 지표는 유지 구간만 기록",
        f"- k6 종료 코드: {k6_exit} (0 = thresholds 모두 통과)",
        "",
        "## 합격 기준별 판정 (REQ-022)",
        "",
        "| 기준 | 목표 | 측정값 | 판정 |",
        "| --- | --- | --- | --- |",
        f"| 평균 CPU(백엔드 단독, 2 vCPU 기준) | ≤ 70% | {backend_cpu:.1f}% (샘플 {len(samples['lt-backend'])}개) | {verdict(backend_cpu <= 70)} |",
        f"| 평균 CPU(백엔드+MySQL+Redis 합계, 2 vCPU 기준, 참고) | ≤ 70% | {total_cpu:.1f}% | {verdict(total_cpu <= 70)} |",
        f"| lock → 상대 BOARD 수신 p95 | ≤ 200ms | {p95:.1f}ms (표본 {p95_count}개) | {verdict(p95 <= 200)} |",
        f"| WebSocket 오류·비정상 끊김 비율 | ≤ 0.1% | {failure_rate * 100:.2f}% | {verdict(failure_rate <= 0.001)} |",
        f"| 정상 시나리오 INVALID_LOCK | 0건 | {invalid}건 | {verdict(invalid == 0)} |",
        "",
        "## 부하 프로필 재현 정도 (REQ-017)",
        "",
        f"- lock 전송: {locks}회 → 플레이어당 초당 {locks / (rooms * 2 * hold):.2f}회 (목표 2회). 직전 BOARD를 못 받아 건너뛴 틱 {skipped}회",
        f"- 부하 발생기 k6 컨테이너 CPU 평균: {avg(samples['k6']):.0f}%(1코어 = 100%) — 같은 호스트(22코어)에서 서버와 CPU를 나눠 쓴다",
        f"- 지연 분해(같은 호스트 시계, BOARD 봉투 sentAt 기준): lock → 서버 발송 p95 {metric(summary, 'diag_lock_to_server_publish', 'p(95)'):.1f}ms, "
        f"서버 발송 → 봇 수신 p95 {metric(summary, 'diag_server_publish_to_receive', 'p(95)'):.1f}ms",
        f"- topout(재대결로 이어 감): {topouts}회 — 재대결 카운트다운 3초 동안은 lock이 멈춘다",
        f"- ATTACK {attacks}회·{attack_lines}줄 → 방당 10초에 {attack_lines / max(rooms, 1) / (hold / 10):.1f}줄",
        f"- 10초 구간 중 양방향 모두 2줄 이상 ATTACK이 있었던 비율: {windows_ok}/{windows} = {windows_ok / max(windows, 1) * 100:.0f}%",
        "",
        "## 원자료",
        "",
        "- `summary.json`(k6), `k6.log`, `cpu.csv`(docker stats 5초 간격), `backend.log`",
    ]
    (out / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
