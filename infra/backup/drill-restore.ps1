# 家庭综合平台 MySQL 备份恢复演练（Windows / PowerShell）
# 目的：不触碰生产库，验证备份文件可恢复、数据完整。
# 步骤：建隔离库 <db>_drill<时间戳> -> 导入备份 -> 逐表比对行数 -> 清理隔离库 -> 输出证据报告。
# 用法：$env:MYSQL_ROOT_PASSWORD="..." ; ./drill-restore.ps1 <备份目录>
# 示例：./drill-restore.ps1 .\backups\20260928-123456
$ErrorActionPreference = 'Stop'

if ($args.Count -lt 1) { throw '用法: ./drill-restore.ps1 <备份目录>' }
$backupDir = $args[0]
if (-not (Test-Path -LiteralPath $backupDir -PathType Container)) { throw "备份目录不存在: $backupDir" }

$mysqlHost = $env:MYSQL_HOST
if (-not $mysqlHost) { $mysqlHost = '127.0.0.1' }
$mysqlUser = 'root'
$mysqlPassword = $env:MYSQL_ROOT_PASSWORD
if (-not $mysqlPassword) { throw '请设置 MYSQL_ROOT_PASSWORD 环境变量' }

$suffix = '_drill_' + (Get-Date -Format 'yyyyMMddHHmmss')
$report = Join-Path $backupDir "drill-report-$(Get-Date -Format 'yyyyMMdd-HHmmss').txt"
$createdDbs = [System.Collections.Generic.List[string]]::new()
$failed = 0

function Say($msg) { $msg | Tee-Object -FilePath $report -Append | Out-Host }
function SqlQ([string]$name) { return '`' + $name + '`' }

try {
    Say "=== 备份恢复演练报告 ==="
    Say "时间: $(Get-Date -Format o)"
    Say "备份源: $backupDir"
    Say "目标实例: ${mysqlHost}（隔离库，不触碰生产）"

    $sqlFiles = Get-ChildItem -LiteralPath $backupDir -Filter '*.sql'
    if ($sqlFiles.Count -eq 0) { throw '备份目录中没有 .sql 文件' }

    foreach ($f in $sqlFiles) {
        $srcDb = [System.IO.Path]::GetFileNameWithoutExtension($f.Name)
        $drillDb = "$srcDb$suffix"
        Say ""
        Say "--- $srcDb -> $drillDb ---"

        $createSql = 'CREATE DATABASE IF NOT EXISTS {0} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;' -f (SqlQ $drillDb)
        & mysql --host=$mysqlHost --user=$mysqlUser --password=$mysqlPassword -e $createSql
        if ($LASTEXITCODE -ne 0) { Say "RESULT: FAIL —— 建隔离库失败"; $failed++; continue }
        $createdDbs.Add($drillDb)

        # 备份含 CREATE DATABASE / USE 原库名，导入前把库名整体替换为隔离库名
        $sql = Get-Content -LiteralPath $f.FullName -Raw -Encoding UTF8
        $sql = $sql.Replace((SqlQ $srcDb), (SqlQ $drillDb))
        $tmpSql = Join-Path $env:TEMP "drill-$srcDb-$suffix.sql"
        $sql | Out-File -LiteralPath $tmpSql -Encoding utf8
        Get-Content -LiteralPath $tmpSql -Encoding UTF8 | & mysql --host=$mysqlHost --user=$mysqlUser --password=$mysqlPassword 2>&1 | Out-Null
        Remove-Item -LiteralPath $tmpSql -Force -ErrorAction SilentlyContinue
        if ($LASTEXITCODE -ne 0) { Say "RESULT: FAIL —— $srcDb 导入失败"; $failed++; continue }

        # 逐表比对行数
        $listSql = "SELECT table_name FROM information_schema.tables WHERE table_schema='$srcDb';"
        $tables = & mysql --host=$mysqlHost --user=$mysqlUser --password=$mysqlPassword -N -B -e $listSql
        $tableCount = 0; $mismatches = 0
        foreach ($tbl in $tables) {
            if (-not $tbl) { continue }
            $tableCount++
            $cntSrcSql = 'SELECT COUNT(*) FROM {0}.{1};' -f (SqlQ $srcDb), (SqlQ $tbl)
            $cntDriSql = 'SELECT COUNT(*) FROM {0}.{1};' -f (SqlQ $drillDb), (SqlQ $tbl)
            $srcCount = & mysql --host=$mysqlHost --user=$mysqlUser --password=$mysqlPassword -N -B -e $cntSrcSql
            $driCount = & mysql --host=$mysqlHost --user=$mysqlUser --password=$mysqlPassword -N -B -e $cntDriSql
            if ($srcCount -ne $driCount) {
                Say ('  MISMATCH {0}: 源={1} 恢复={2}' -f $tbl, $srcCount, $driCount)
                $mismatches++
            }
        }
        if ($mismatches -eq 0) {
            Say "  $tableCount 张表行数全部一致 -> OK"
        } else {
            Say "RESULT: FAIL —— $srcDb 有 $mismatches 张表不一致"
            $failed++
        }
    }

    Say ""
    if ($failed -eq 0) {
        Say "=== 演练结论: PASS —— 备份可恢复且数据一致 ==="
    } else {
        Say "=== 演练结论: FAIL —— $failed 个库存在问题 ==="
    }
    Say "证据文件: $report"
    if ($failed -ne 0) { throw "演练失败: $failed 个库存在问题" }
}
finally {
    foreach ($db in $createdDbs) {
        $dropSql = 'DROP DATABASE IF EXISTS {0};' -f (SqlQ $db)
        & mysql --host=$mysqlHost --user=$mysqlUser --password=$mysqlPassword -e $dropSql 2>&1 | Out-Null
    }
    Say "已清理 $($createdDbs.Count) 个隔离库"
}
