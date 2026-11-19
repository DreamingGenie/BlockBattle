#!/usr/bin/env bash
# 운영 서버의 DB·Redis·비밀값을 이 PC로 받아 둔다(서버 이전·종료 전). 다른 서버에서 되살리는 쪽은 deploy/restore.sh.
# 받는 동안 새 기록이 생기지 않게 nginx·backend·ai를 먼저 멈추고, 끝나도 다시 켜지 않는다(mysql·redis만 켜져 있다).
#   다시 열기: 서버에서 cd ~/block-battle/deploy && sudo docker compose start
#   완전히 내리기: 서버에서 cd ~/block-battle/deploy && sudo docker compose down
# 사용: bash deploy/backup.sh <서버 공인 IP> <키 파일 .pem> <받을 폴더>
# 주의: 받은 폴더에는 회원 데이터와 운영 비밀값(.env)이 들어 있다. 저장소 안에 두거나 커밋하지 않는다.
set -euo pipefail
IP="${1:?서버 공인 IP 필요}"
KEY="${2:?.pem 키 파일 경로 필요}"
OUT="${3:?받을 폴더 필요}"
SSH=(ssh -i "$KEY" -o StrictHostKeyChecking=accept-new "ubuntu@$IP")
mkdir -p "$OUT"

echo "[1/3] 서비스 멈추고 서버에서 백업 만들기"
"${SSH[@]}" 'bash -s' <<'REMOTE'
set -euo pipefail
cd ~/block-battle/deploy
B=/tmp/block-battle-backup
rm -rf "$B" && mkdir -p "$B"
# heredoc으로 넘긴 스크립트라 docker 명령마다 </dev/null을 붙인다. 안 붙이면 exec가 남은 스크립트를 표준입력으로 먹어 버린다
sudo docker compose stop nginx backend ai </dev/null

# MySQL: 테이블별 정확한 행 수를 먼저 적고(복원 뒤 대조용) 덤프한다
sudo docker compose exec -T mysql sh -c '
  P="$MYSQL_ROOT_PASSWORD"
  for t in $(mysql -uroot -p"$P" -N -e "SHOW TABLES FROM block_battle" 2>/dev/null); do
    echo "$t $(mysql -uroot -p"$P" -N -e "SELECT COUNT(*) FROM block_battle.\`$t\`" 2>/dev/null)"
  done' </dev/null > "$B/row-counts.txt"
sudo docker compose exec -T mysql sh -c \
  'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers --set-gtid-purged=OFF --databases block_battle 2>/dev/null' </dev/null \
  | gzip > "$B/mysql-block_battle.sql.gz"
gzip -cd "$B/mysql-block_battle.sql.gz" | tail -1 | grep -q 'Dump completed' || { echo "MySQL 덤프가 끝까지 쓰이지 않았다" >&2; exit 1; }

# Redis: 지금 상태를 스냅숏으로 떨군 뒤 복사한다(방·Refresh 토큰은 TTL이 있어 복원은 선택)
sudo docker compose exec -T redis redis-cli SAVE </dev/null >/dev/null
sudo docker compose cp redis:/data/dump.rdb "$B/redis-dump.rdb" </dev/null

cp .env "$B/env"
# docker compose cp로 꺼낸 파일은 root 소유라 체크섬·tar보다 먼저 소유자를 바꾼다
sudo chown -R ubuntu "$B"
chmod -R u+rw "$B"
(cd "$B" && sha256sum mysql-block_battle.sql.gz redis-dump.rdb env row-counts.txt > SHA256SUMS)
tar -czf /tmp/block-battle-backup.tgz -C /tmp block-battle-backup
REMOTE

echo "[2/3] 받기"
scp -i "$KEY" -o StrictHostKeyChecking=accept-new "ubuntu@$IP:/tmp/block-battle-backup.tgz" "$OUT/block-battle-backup.tgz"
tar -xzf "$OUT/block-battle-backup.tgz" -C "$OUT" --strip-components=1
(cd "$OUT" && sha256sum -c SHA256SUMS)

echo "[3/3] 서버의 임시 파일 지우기"
"${SSH[@]}" 'rm -rf /tmp/block-battle-backup /tmp/block-battle-backup.tgz'
echo "완료: $OUT"
cat "$OUT/row-counts.txt"
