#!/usr/bin/env bash
# BEEZ 서버(dev.beez.ai.kr)의 /tetris 아래에 블록 배틀을 임시로 함께 올리고 내린다(2026-11-19 ~ 11-21, 11-21에 purge로 내림).
# BEEZ 컨테이너·설정은 건드리지 않는다. 블록 배틀은 별도 compose 프로젝트(block-battle)로 127.0.0.1:18080·18000에만 열고,
# 호스트 nginx의 dev.beez.ai.kr 443 server 블록에 include 한 줄(snippets/tetris-locations.conf)과 conf.d/tetris-limits.conf만 더한다.
#
# 사용(Git Bash):
#   bash deploy/beez-host.sh up [백업 폴더]   빌드·올리기·기동. 백업 폴더(deploy/backup.sh 결과)를 주면 처음 한 번 DB를 되살린다
#   bash deploy/beez-host.sh down             nginx 설정을 되돌리고 컨테이너를 내린다(데이터 볼륨은 남긴다)
#   bash deploy/beez-host.sh purge            down + 데이터 볼륨·정적 파일까지 지운다
# 서버 접속은 ~/.ssh/config의 Host 이름(BEEZ_SSH, 기본 beez)을 쓴다. 공지 띠 문구는 NOTICE 환경 변수로 넣는다.
set -euo pipefail
export MSYS_NO_PATHCONV=1 MSYS2_ENV_CONV_EXCL='*'   # Git Bash가 /tetris/ 같은 값을 Windows 경로로 바꾸지 않게

CMD="${1:?up | down | purge}"
BACKUP="${2:-}"
SSH_HOST="${BEEZ_SSH:-beez}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REMOTE='~/block-battle'
SITE=/etc/nginx/sites-available/dev.beez.ai.kr.conf
COMPOSE='docker compose -f docker-compose.yml -f beez-host/docker-compose.override.yml'
remote() { ssh -o BatchMode=yes "$SSH_HOST" "set -euo pipefail; $1"; }

nginx_off() {
  remote "
    if grep -q 'snippets/tetris-locations.conf' $SITE; then
      sudo cp $SITE /tmp/dev.beez.ai.kr.conf.before-tetris-down
      sudo sed -i '/snippets\/tetris-locations.conf/d' $SITE
    fi
    sudo rm -f /etc/nginx/snippets/tetris-locations.conf /etc/nginx/conf.d/tetris-limits.conf
    sudo nginx -t && sudo systemctl reload nginx"
}

case "$CMD" in
up)
  if [ -n "$BACKUP" ]; then
    for f in mysql-block_battle.sql.gz row-counts.txt SHA256SUMS; do
      [ -f "$BACKUP/$f" ] || { echo "백업 폴더에 $f 가 없다: $BACKUP" >&2; exit 1; }
    done
    (cd "$BACKUP" && sha256sum -c --quiet SHA256SUMS)
  fi

  echo "[1/6] 빌드(프론트는 /tetris/ 하위 경로)"
  (cd "$ROOT/backend" && ./gradlew -q bootJar -x test)
  (cd "$ROOT/frontend" && VITE_BASE_PATH=/tetris/ VITE_SERVICE_NOTICE="${NOTICE:-}" npm run -s build)

  echo "[2/6] 묶어서 올리기"
  BUNDLE="$(mktemp -d)/block-battle.tgz"
  tar -czf "$BUNDLE" -C "$ROOT" \
    backend/Dockerfile backend/build/libs/block-battle-backend-0.0.1-SNAPSHOT.jar \
    ai/Dockerfile ai/app shared/rules-fixtures frontend/dist \
    deploy/docker-compose.yml deploy/beez-host
  scp -o BatchMode=yes "$BUNDLE" "$SSH_HOST:/tmp/block-battle.tgz"
  remote "mkdir -p $REMOTE && rm -rf $REMOTE/frontend/dist && tar -xzf /tmp/block-battle.tgz -C $REMOTE && rm /tmp/block-battle.tgz"

  echo "[3/6] 비밀값(처음 한 번 무작위로 만든다)"
  FRESH="$(remote "
    ENV=$REMOTE/deploy/.env
    if [ -f \$ENV ]; then echo no; else
      umask 077
      printf 'PUBLIC_HOST=dev.beez.ai.kr\nJWT_SECRET=%s\nDB_PASSWORD=%s\nDB_ROOT_PASSWORD=%s\n' \
        \"\$(openssl rand -hex 32)\" \"\$(openssl rand -hex 16)\" \"\$(openssl rand -hex 16)\" > \$ENV
      echo yes
    fi")"

  echo "[4/6] 컨테이너 기동(mysql·redis·backend·ai, nginx는 BEEZ 호스트 nginx)"
  remote "cd $REMOTE/deploy && $COMPOSE up -d --build mysql redis backend ai"
  remote "for i in \$(seq 1 60); do c=\$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18080/api/v1/users/me || true);
            [ \"\$c\" != 000 ] && echo \"backend 응답 \$c\" && exit 0; sleep 2; done; echo 'backend가 2분 안에 뜨지 않았다' >&2; exit 1"

  if [ -n "$BACKUP" ] && [ "$FRESH" = yes ]; then
    echo "[4.5/6] DB 되살리기(빈 DB일 때만)"
    scp -o BatchMode=yes "$BACKUP/mysql-block_battle.sql.gz" "$SSH_HOST:/tmp/restore.sql.gz"
    remote "cd $REMOTE/deploy && $COMPOSE stop backend
      gzip -cd /tmp/restore.sql.gz | $COMPOSE exec -T mysql sh -c 'exec mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" 2>/dev/null'
      rm -f /tmp/restore.sql.gz
      $COMPOSE start backend"
    remote "cd $REMOTE/deploy && $COMPOSE exec -T mysql sh -c 'mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N -e \"SELECT COUNT(*) FROM block_battle.users\" 2>/dev/null'" \
      | xargs -I{} echo "되살린 사용자 수: {} (백업: $(grep '^users ' "$BACKUP/row-counts.txt" | tr -d '\r'))"
  elif [ -n "$BACKUP" ]; then
    echo "[4.5/6] 이미 .env가 있어 DB를 덮어쓰지 않는다(되살리기는 처음 한 번만)"
  fi

  echo "[5/6] 정적 파일"
  remote "sudo mkdir -p /var/www/tetris && sudo rm -rf /var/www/tetris/next && sudo cp -r $REMOTE/frontend/dist /var/www/tetris/next
    sudo rm -rf /var/www/tetris/current && sudo mv /var/www/tetris/next /var/www/tetris/current"

  echo "[6/6] nginx(/tetris include 한 줄 + 요청 한도)"
  remote "
    sudo cp $REMOTE/deploy/beez-host/tetris-locations.conf /etc/nginx/snippets/tetris-locations.conf
    sudo cp $REMOTE/deploy/beez-host/tetris-limits.conf /etc/nginx/conf.d/tetris-limits.conf
    if ! grep -q 'snippets/tetris-locations.conf' $SITE; then
      sudo cp $SITE /tmp/dev.beez.ai.kr.conf.before-tetris-up
      sudo sed -i '\#root /var/www/beez/current;#a\\    include snippets/tetris-locations.conf;   # 블록 배틀 /tetris 임시(deploy/beez-host.sh down 으로 지운다)' $SITE
    fi
    if ! sudo nginx -t; then
      echo 'nginx 설정 검사 실패 — 되돌린다' >&2
      [ -f /tmp/dev.beez.ai.kr.conf.before-tetris-up ] && sudo cp /tmp/dev.beez.ai.kr.conf.before-tetris-up $SITE
      sudo rm -f /etc/nginx/snippets/tetris-locations.conf /etc/nginx/conf.d/tetris-limits.conf
      exit 1
    fi
    sudo systemctl reload nginx"
  echo "접속: https://dev.beez.ai.kr/tetris/"
  ;;
down)
  nginx_off
  remote "cd $REMOTE/deploy && $COMPOSE down"
  echo "내렸다. 데이터 볼륨(block-battle_mysql-data 등)은 남아 있다. 지우려면 purge"
  ;;
purge)
  nginx_off
  remote "cd $REMOTE/deploy && $COMPOSE down -v && sudo rm -rf /var/www/tetris"
  echo "컨테이너·볼륨·정적 파일을 지웠다. $REMOTE 폴더와 deploy/.env는 남아 있다"
  ;;
*)
  echo "up | down | purge" >&2
  exit 1
  ;;
esac
