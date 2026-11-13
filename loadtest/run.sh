#!/usr/bin/env bash
# T-17 부하 시험 한 번 실행: 환경을 새로 띄우고, k6 시나리오와 docker stats CPU 샘플링을 함께 돌린 뒤 보고서를 만든다.
# 사용: loadtest/run.sh [ROOMS] [RAMP_S] [HOLD_S]   (기본 100 60 600)
# 필요: Docker, backend/build/libs/*.jar(./gradlew bootJar), 이미지 grafana/k6:1.3.0·eclipse-temurin:21-jre·mysql:8.4·redis:7.4-alpine
set -euo pipefail
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1
ROOMS=${1:-100}
RAMP_S=${2:-60}
HOLD_S=${3:-600}
OUT=results/$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUT"

docker compose down -v >/dev/null 2>&1 || true
docker compose up -d mysql redis backend
until [ "$(docker inspect -f '{{.State.Health.Status}}' lt-backend)" = healthy ]; do sleep 2; done
# 첫 요청은 초기화로 느리다 — 측정 전에 한 번 데운다
docker exec lt-backend bash -c 'exec 3<>/dev/tcp/localhost/8080; printf "POST /api/v1/auth/guest HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nContent-Length: 20\r\nConnection: close\r\n\r\n{\"nickname\":\"warm\"}" >&3; timeout 30 cat <&3 >/dev/null' || true

START=$(date +%s)
echo "$START $ROOMS $RAMP_S $HOLD_S" > "$OUT/run.txt"
(
  echo "epoch,name,cpu_percent,mem_usage"
  while [ ! -f "$OUT/k6.done" ]; do
    now=$(date +%s)
    k6c=$(docker ps --filter name=k6-run -q | head -1)
    docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' lt-backend lt-mysql lt-redis $k6c | sed "s/^/$now,/"
    sleep 5
  done
) > "$OUT/cpu.csv" &
SAMPLER=$!

set +e
docker compose --profile run run --rm -e ROOMS="$ROOMS" -e RAMP_S="$RAMP_S" -e HOLD_S="$HOLD_S" k6 \
  run --summary-export "/loadtest/$OUT/summary.json" battle.js > "$OUT/k6.log" 2>&1
K6_EXIT=$?
set -e
touch "$OUT/k6.done"
wait $SAMPLER
docker logs lt-backend > "$OUT/backend.log" 2>&1 || true
python report.py "$OUT" "$K6_EXIT"
echo "결과: $OUT/report.md (k6 종료 코드 $K6_EXIT)"
