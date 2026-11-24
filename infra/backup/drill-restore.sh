#!/usr/bin/env bash
# 家庭综合平台 MySQL 备份恢复演练（Linux / 服务器）
# 目的：在不触碰生产库的前提下，验证备份文件可恢复、数据完整。
# 步骤：建隔离库 <db>_drill -> 导入备份 -> 逐表比对行数 -> 清理隔离库 -> 输出证据报告。
# 用法：MYSQL_ROOT_PASSWORD=xxx ./drill-restore.sh <备份目录> [mysql命令路径]
# 示例：MYSQL_ROOT_PASSWORD=xxx ./drill-restore.sh ./backups/20260928-123456
set -euo pipefail

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:?请设置 MYSQL_ROOT_PASSWORD 环境变量}"
BACKUP_DIR="${1:?用法: ./drill-restore.sh <备份目录> [mysql命令路径]}"
MYSQL_BIN="${2:-mysql}"
DUMP_BIN="${DUMP_MYSQL:-mysqldump}"

if [[ ! -d "$BACKUP_DIR" ]]; then echo "备份目录不存在: $BACKUP_DIR" >&2; exit 1; fi

DRILL_SUFFIX="_drill_$(date +%s)"
REPORT="$BACKUP_DIR/drill-report-$(date +%Y%m%d-%H%M%S).txt"
CREATED_DBS=()
FAILED=0

say() { echo "$*" | tee -a "$REPORT"; }

cleanup() {
  for db in "${CREATED_DBS[@]}"; do
    "$MYSQL_BIN" --host="$MYSQL_HOST" --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" \
      -e "DROP DATABASE IF EXISTS \`$db\`;" >/dev/null 2>&1 || true
  done
  say "已清理 ${#CREATED_DBS[@]} 个隔离库"
}
trap cleanup EXIT

say "=== 备份恢复演练报告 ==="
say "时间: $(date -Iseconds)"
say "备份源: $BACKUP_DIR"
say "目标实例: $MYSQL_HOST（隔离库，不触碰生产）"

shopt -s nullglob
for sqlfile in "$BACKUP_DIR"/*.sql; do
  srcdb="$(basename "$sqlfile" .sql)"
  drildb="${srcdb}${DRILL_SUFFIX}"
  say ""
  say "--- $srcdb -> $drildb ---"

  "$MYSQL_BIN" --host="$MYSQL_HOST" --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" \
    -e "CREATE DATABASE IF NOT EXISTS \`$drildb\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
  CREATED_DBS+=("$drildb")

  # 备份用 --databases 导出，含 CREATE DATABASE/USE 原库名；导入前改写为隔离库名
  sed -e "s/^CREATE DATABASE.*\`${srcdb}\`.*/CREATE DATABASE IF NOT EXISTS \`${drildb}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;/" \
      -e "s/^USE \`${srcdb}\`;/USE \`${drildb}\`;/" \
      "$sqlfile" | "$MYSQL_BIN" --host="$MYSQL_HOST" --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" >/dev/null \
    || { say "RESULT: FAIL —— $srcdb 导入失败"; FAILED=$((FAILED+1)); continue; }

  # 逐表比对行数（源库 vs 隔离库）
  tables="$("$MYSQL_BIN" --host="$MYSQL_HOST" --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" -N -B \
    -e "SELECT table_name FROM information_schema.tables WHERE table_schema='$srcdb';")"
  table_count=0; mismatches=0
  while IFS= read -r tbl; do
    [[ -z "$tbl" ]] && continue
    table_count=$((table_count+1))
    src_count="$("$MYSQL_BIN" --host="$MYSQL_HOST" --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" -N -B \
      -e "SELECT COUNT(*) FROM \`$srcdb\`.\`$tbl\`;")"
    dri_count="$("$MYSQL_BIN" --host="$MYSQL_HOST" --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" -N -B \
      -e "SELECT COUNT(*) FROM \`$drildb\`.\`$tbl\`;")"
    if [[ "$src_count" != "$dri_count" ]]; then
      say "  MISMATCH $tbl: 源=$src_count 恢复=$dri_count"
      mismatches=$((mismatches+1))
    fi
  done <<< "$tables"

  if [[ $mismatches -eq 0 ]]; then
    say "  $table_count 张表行数全部一致 -> OK"
  else
    say "RESULT: FAIL —— $srcdb 有 $mismatches 张表不一致"
    FAILED=$((FAILED+1))
  fi
done

say ""
if [[ $FAILED -eq 0 ]]; then
  say "=== 演练结论: PASS —— 备份可恢复且数据一致 ==="
else
  say "=== 演练结论: FAIL —— $FAILED 个库存在问题 ==="
fi
say "证据文件: $REPORT"
[[ $FAILED -eq 0 ]]
