#!/usr/bin/env bash
# 로컬(Git Bash)에서 빌드해 EC2로 올리고 기동한다(T-29). 서버에서는 Gradle·npm을 돌리지 않는다.
# 사용: bash deploy/deploy.sh <서버 공인 IP> <키 파일 .pem>
set -euo pipefail
IP="${1:?서버 공인 IP 필요}"
KEY="${2:?.pem 키 파일 경로 필요}"
HOST="$(echo "$IP" | tr . -).sslip.io"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SSH=(ssh -i "$KEY" -o StrictHostKeyChecking=accept-new "ubuntu@$IP")

echo "[1/4] 빌드"
(cd "$ROOT/backend" && ./gradlew -q bootJar -x test)
(cd "$ROOT/frontend" && npm run -s build)

echo "[2/4] 묶기"
BUNDLE="$(mktemp -d)/block-battle.tgz"
tar -czf "$BUNDLE" -C "$ROOT" \
  backend/Dockerfile backend/build/libs/block-battle-backend-0.0.1-SNAPSHOT.jar \
  ai/Dockerfile ai/app shared/rules-fixtures frontend/dist \
  deploy/docker-compose.yml deploy/nginx.conf.template deploy/server-setup.sh deploy/.env.example

echo "[3/4] 올리기"
scp -i "$KEY" -o StrictHostKeyChecking=accept-new "$BUNDLE" "ubuntu@$IP:/tmp/block-battle.tgz"
"${SSH[@]}" "mkdir -p ~/block-battle && tar -xzf /tmp/block-battle.tgz -C ~/block-battle && rm /tmp/block-battle.tgz"

echo "[4/4] 서버 준비(처음 한 번)·기동"
"${SSH[@]}" "sudo bash ~/block-battle/deploy/server-setup.sh $HOST && cd ~/block-battle/deploy && sudo docker compose up -d --build && sudo docker compose ps"
echo "접속: https://$HOST"
