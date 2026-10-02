#!/usr/bin/env bash
# 家庭综合平台 MySQL 备份脚本（Linux / 服务器）
# 用法：MYSQL_ROOT_PASSWORD=xxx ./backup-mysql.sh [保留份数=7]
set -euo pipefail

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:?请设置 MYSQL_ROOT_PASSWORD 环境变量}"
KEEP="${1:-7}"

DATABASES=(family_user family_content family_file family_archive family_im family_media)
BACKUP_ROOT="$(cd "$(dirname "$0")" && pwd)/backups"
STAMP="$(date +%Y%m%d-%H%M%S)"
TARGET_DIR="$BACKUP_ROOT/$STAMP"
mkdir -p "$TARGET_DIR"

for db in "${DATABASES[@]}"; do
  echo "backing up $db"
  mysqldump \
    --host="$MYSQL_HOST" --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" \
    --single-transaction --routines --triggers --hex-blob \
    --databases "$db" > "$TARGET_DIR/$db.sql"
done

# 轮转：仅保留最近 KEEP 份
ls -1dt "$BACKUP_ROOT"/[0-9]* 2>/dev/null | tail -n +$((KEEP + 1)) | while read -r old; do
  echo "rotate: removing $(basename "$old")"
  rm -rf "$old"
done

echo "backup complete: $TARGET_DIR"
