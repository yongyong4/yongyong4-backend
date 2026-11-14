#!/usr/bin/env bash
# VM(Ubuntu 24.04) 최초 설치. /opt/yongyong/.env 를 먼저 올려둔 뒤 sudo 로 실행:
#   sudo bash /opt/yongyong/install.sh
# 예측 서버(yongyong4-model)는 이 설치 뒤에 모델 레포 deploy.sh 로 올린다
set -euo pipefail

APP_DIR=/opt/yongyong
[ -f "$APP_DIR/.env" ] || { echo "$APP_DIR/.env 가 없습니다. deploy.sh 로 먼저 올리세요."; exit 1; }
if grep -q $'\r' "$APP_DIR/.env"; then
  echo ".env 에 윈도우 줄바꿈(CRLF)이 있습니다. LF 로 바꿔 주세요"; exit 1
fi
set -a; . "$APP_DIR/.env"; set +a
: "${MYSQL_PASSWORD:?.env 에 MYSQL_PASSWORD 필요}"
# SQL 문자열·셸에 그대로 들어가므로 안전한 문자만 허용
if ! [[ "$MYSQL_PASSWORD" =~ ^[A-Za-z0-9._@%+=-]+$ ]]; then
  echo "MYSQL_PASSWORD 는 영문·숫자·._@%+=- 만 써 주세요 (따옴표·공백·\$ 불가)"; exit 1
fi
DB_NAME=yongyong
DB_USER=${MYSQL_USER:-yongyong}

echo "== 패키지 설치"
apt-get update -y
DEBIAN_FRONTEND=noninteractive apt-get upgrade -y
DEBIAN_FRONTEND=noninteractive apt-get install -y \
  openjdk-21-jre-headless mariadb-server python3-venv caddy curl

echo "== 시간대 KST"
timedatectl set-timezone Asia/Seoul

# 우분투 자동 보안 업데이트는 기본 06:00~07:00 에 돌며 서비스를 재시작한다 → 출근 시간대 밖(16시대)으로 옮김
echo "== 자동 업데이트 16:00 으로 이동"
mkdir -p /etc/systemd/system/apt-daily-upgrade.timer.d
printf '[Timer]\nOnCalendar=\nOnCalendar=*-*-* 16:00\nRandomizedDelaySec=60m\n' > /etc/systemd/system/apt-daily-upgrade.timer.d/override.conf
systemctl daemon-reload
systemctl restart apt-daily-upgrade.timer

echo "== MariaDB 설정"
cat > /etc/mysql/mariadb.conf.d/99-yongyong.cnf <<CNF
[mysqld]
default-time-zone = '+09:00'
character-set-server = utf8mb4
collation-server = utf8mb4_unicode_ci
innodb_buffer_pool_size = 256M
bind-address = 127.0.0.1
CNF
systemctl enable --now mariadb
systemctl restart mariadb
mariadb <<SQL
CREATE DATABASE IF NOT EXISTS \`$DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '$DB_USER'@'localhost' IDENTIFIED BY '$MYSQL_PASSWORD';
ALTER USER '$DB_USER'@'localhost' IDENTIFIED BY '$MYSQL_PASSWORD';
GRANT ALL ON \`$DB_NAME\`.* TO '$DB_USER'@'localhost';
FLUSH PRIVILEGES;
SQL

# trip_schema.sql 끝의 ALTER(외래키)는 다시 실행하면 실패하므로 trip 테이블이 없을 때만 적용
if [ -z "$(mariadb -N -e "SHOW TABLES FROM \`$DB_NAME\` LIKE 'trip'")" ]; then
  echo "== 서비스 테이블 생성 (trip_schema.sql)"
  mariadb "$DB_NAME" < "$APP_DIR/trip_schema.sql"
fi

echo "== 앱 사용자·디렉터리"
id yongyong >/dev/null 2>&1 || useradd --system --home "$APP_DIR" --shell /usr/sbin/nologin yongyong
mkdir -p "$APP_DIR/logs"
chown -R yongyong:yongyong "$APP_DIR"
chmod 600 "$APP_DIR/.env"

# 오라클 우분투 이미지는 iptables 로 22 외 포트를 막아 둔다 → Caddy 용 80·443 열기
echo "== 방화벽 80·443"
for port in 80 443; do
  iptables -C INPUT -p tcp -m state --state NEW --dport "$port" -j ACCEPT 2>/dev/null \
    || iptables -I INPUT 5 -p tcp -m state --state NEW --dport "$port" -j ACCEPT
done
netfilter-persistent save

echo "== Caddy (HTTPS → localhost:8080)"
cp "$APP_DIR/Caddyfile" /etc/caddy/Caddyfile
systemctl enable caddy
systemctl reload caddy || systemctl restart caddy

echo "== systemd 등록"
cp "$APP_DIR/yongyong-backend.service" /etc/systemd/system/
systemctl daemon-reload
systemctl enable yongyong-backend
systemctl restart yongyong-backend

echo "완료. 로그: journalctl -u yongyong-backend -f"
