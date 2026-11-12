#!/usr/bin/env bash
# 로컬(맥)에서 빌드 후 VM 에 올리고 재시작한다.
#   ./deploy/deploy.sh ubuntu@1.2.3.4 [ssh 키 경로]
# 최초 1회는 deploy/.env 도 올리고 install.sh 를 실행한다. 이후 .env 를 바꿨으면 UPLOAD_ENV=1 ./deploy/deploy.sh ...
set -euo pipefail

HOST=${1:?사용법: deploy.sh user@host [키파일]}
KEY=${2:-}
SSH_OPTS=()
if [ -n "$KEY" ]; then SSH_OPTS=(-i "$KEY"); fi
ROOT=$(cd "$(dirname "$0")/.." && pwd)

echo "== 빌드"
(cd "$ROOT" && sh ./gradlew -q clean bootJar)
JAR=$(ls "$ROOT"/build/libs/*.jar | grep -v -- '-plain.jar' | head -1)

echo "== 업로드"
UP=/tmp/yongyong-upload
ssh ${SSH_OPTS[@]+"${SSH_OPTS[@]}"} "$HOST" "rm -rf $UP && mkdir -p $UP"
FILES=("$ROOT/deploy/yongyong-backend.service" "$ROOT/deploy/install.sh" "$ROOT/deploy/Caddyfile" "$ROOT/sql/trip_schema.sql")
FIRST=false
if ! ssh ${SSH_OPTS[@]+"${SSH_OPTS[@]}"} "$HOST" "test -f /etc/systemd/system/yongyong-backend.service"; then
  FIRST=true
  [ -f "$ROOT/deploy/.env" ] || { echo "deploy/.env 가 없습니다 (deploy/.env.example 참고)"; exit 1; }
  FILES+=("$ROOT/deploy/.env")
elif [ "${UPLOAD_ENV:-0}" = "1" ]; then
  echo "== .env 도 교체 (UPLOAD_ENV=1)"
  FILES+=("$ROOT/deploy/.env")
fi
scp ${SSH_OPTS[@]+"${SSH_OPTS[@]}"} "${FILES[@]}" "$HOST:$UP/"
scp ${SSH_OPTS[@]+"${SSH_OPTS[@]}"} "$JAR" "$HOST:$UP/app.jar"
ssh ${SSH_OPTS[@]+"${SSH_OPTS[@]}"} "$HOST" "sudo mkdir -p /opt/yongyong && sudo cp -a $UP/. /opt/yongyong/ && rm -rf $UP"

if $FIRST; then
  echo "== 최초 설치 (install.sh)"
  ssh ${SSH_OPTS[@]+"${SSH_OPTS[@]}"} "$HOST" "sudo bash /opt/yongyong/install.sh"
else
  echo "== 재시작"
  ssh ${SSH_OPTS[@]+"${SSH_OPTS[@]}"} "$HOST" "sudo chown -R yongyong:yongyong /opt/yongyong && sudo chmod 600 /opt/yongyong/.env && sudo cp /opt/yongyong/Caddyfile /etc/caddy/Caddyfile && sudo systemctl reload caddy && sudo systemctl restart yongyong-backend"
fi

echo "== 상태"
sleep 25
ssh ${SSH_OPTS[@]+"${SSH_OPTS[@]}"} "$HOST" "systemctl is-active yongyong-backend; curl -s localhost:8080/actuator/health; echo"
