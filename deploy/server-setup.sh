#!/usr/bin/env bash
# EC2(Ubuntu 24.04) 처음 한 번 준비(T-29): Docker·certbot 설치, swap 2GB, .env 비밀값 생성, HTTPS 인증서 발급.
# 사용: sudo bash deploy/server-setup.sh <PUBLIC_HOST>   예) 3-34-12-7.sslip.io
set -euo pipefail
HOST="${1:?PUBLIC_HOST 필요(예: 3-34-12-7.sslip.io)}"
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"

if ! command -v docker >/dev/null; then
  apt-get update -y
  apt-get install -y docker.io docker-compose-v2 certbot
  systemctl enable --now docker
  usermod -aG docker "${SUDO_USER:-ubuntu}"
fi

# 4GB 서버에서 MySQL·JVM·Python을 함께 올리므로 여유 swap을 둔다
if ! swapon --show | grep -q /swapfile; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  grep -q '/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

# 비밀값은 서버에서만 만든다(저장소에는 .env.example만 있다)
ENV_FILE="$APP_DIR/deploy/.env"
if [ ! -f "$ENV_FILE" ]; then
  umask 077
  cat > "$ENV_FILE" <<EOF
PUBLIC_HOST=$HOST
JWT_SECRET=$(openssl rand -hex 32)
DB_PASSWORD=$(openssl rand -hex 16)
DB_ROOT_PASSWORD=$(openssl rand -hex 16)
EOF
  chown "${SUDO_USER:-ubuntu}" "$ENV_FILE"
fi

# Let's Encrypt 인증서(standalone: nginx가 뜨기 전에 80 포트로 확인). 갱신 때는 nginx를 잠깐 멈춘다
mkdir -p /var/www/certbot
if [ ! -d "/etc/letsencrypt/live/$HOST" ]; then
  certbot certonly --standalone -d "$HOST" --non-interactive --agree-tos --register-unsafely-without-email
fi
mkdir -p /etc/letsencrypt/renewal-hooks/pre /etc/letsencrypt/renewal-hooks/post
cat > /etc/letsencrypt/renewal-hooks/pre/stop-nginx.sh <<EOF
#!/bin/sh
docker compose -f $APP_DIR/deploy/docker-compose.yml stop nginx
EOF
cat > /etc/letsencrypt/renewal-hooks/post/start-nginx.sh <<EOF
#!/bin/sh
docker compose -f $APP_DIR/deploy/docker-compose.yml start nginx
EOF
chmod +x /etc/letsencrypt/renewal-hooks/pre/stop-nginx.sh /etc/letsencrypt/renewal-hooks/post/start-nginx.sh
echo "준비 완료: $HOST"
