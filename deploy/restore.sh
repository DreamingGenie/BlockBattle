#!/usr/bin/env bash
# deploy/backup.sh로 받은 백업을 새 서버에 되살린다. 새 서버는 README "배포"의 1번(서버 만들기)까지 해 둔 상태여야 한다.
# 하는 일: 백업의 비밀값(.env)을 새 주소로 바꿔 먼저 올림 → deploy.sh로 빌드·기동(빈 DB에 Flyway 적용)
#        → backend를 잠깐 멈추고 MySQL 덤프를 덮어씀 → backend 다시 켜고 행 수를 백업 때와 대조
# Redis(방·Refresh 토큰)는 되살리지 않는다. 주소가 바뀌면 브라우저 쿠키가 따라오지 않아 쓸모가 없고, 모두 TTL이 있는 값이다.
# 사용: bash deploy/restore.sh <새 서버 공인 IP> <키 파일 .pem> <백업 폴더>
set -euo pipefail
IP="${1:?새 서버 공인 IP 필요}"
KEY="${2:?.pem 키 파일 경로 필요}"
BACKUP="${3:?백업 폴더 필요(backup.sh의 받을 폴더)}"
HOST="$(echo "$IP" | tr . -).sslip.io"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SSH=(ssh -i "$KEY" -o StrictHostKeyChecking=accept-new "ubuntu@$IP")

for f in mysql-block_battle.sql.gz env row-counts.txt SHA256SUMS; do
  [ -f "$BACKUP/$f" ] || { echo "백업 폴더에 $f 가 없다: $BACKUP" >&2; exit 1; }
done
(cd "$BACKUP" && sha256sum -c --quiet SHA256SUMS)

echo "[1/4] 비밀값 올리기(PUBLIC_HOST만 새 주소로)"
ENV_TMP="$(mktemp)"
sed "s/^PUBLIC_HOST=.*/PUBLIC_HOST=$HOST/" "$BACKUP/env" > "$ENV_TMP"
"${SSH[@]}" 'mkdir -p ~/block-battle/deploy && test ! -f ~/block-battle/deploy/.env' \
  || { echo "새 서버에 이미 deploy/.env가 있다. 빈 서버에서만 복원한다(기존 DB를 덮어쓰지 않게)" >&2; rm -f "$ENV_TMP"; exit 1; }
scp -i "$KEY" -o StrictHostKeyChecking=accept-new "$ENV_TMP" "ubuntu@$IP:block-battle/deploy/.env"
rm -f "$ENV_TMP"
"${SSH[@]}" 'chmod 600 ~/block-battle/deploy/.env'

echo "[2/4] 빌드·기동(deploy.sh)"
bash "$ROOT/deploy/deploy.sh" "$IP" "$KEY"

echo "[3/4] DB 덮어쓰기"
scp -i "$KEY" -o StrictHostKeyChecking=accept-new "$BACKUP/mysql-block_battle.sql.gz" "ubuntu@$IP:/tmp/restore.sql.gz"
"${SSH[@]}" 'set -euo pipefail; cd ~/block-battle/deploy
  sudo docker compose stop backend
  gzip -cd /tmp/restore.sql.gz | sudo docker compose exec -T mysql sh -c '"'"'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" 2>/dev/null'"'"'
  rm -f /tmp/restore.sql.gz
  sudo docker compose start backend'

echo "[4/4] 행 수 대조"
ACTUAL="$("${SSH[@]}" 'cd ~/block-battle/deploy && sudo docker compose exec -T mysql sh -c '"'"'
  P="$MYSQL_ROOT_PASSWORD"
  for t in $(mysql -uroot -p"$P" -N -e "SHOW TABLES FROM block_battle" 2>/dev/null); do
    echo "$t $(mysql -uroot -p"$P" -N -e "SELECT COUNT(*) FROM block_battle.\`$t\`" 2>/dev/null)"
  done'"'"'')"
if diff <(sort "$BACKUP/row-counts.txt" | tr -d '\r') <(echo "$ACTUAL" | sort | tr -d '\r'); then
  echo "행 수 일치. 접속: https://$HOST"
else
  echo "행 수가 백업과 다르다(위 diff). 확인이 필요하다" >&2
  exit 1
fi
