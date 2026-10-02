# 家庭综合平台 MySQL 恢复脚本（Windows / PowerShell）
# 用法：$env:MYSQL_ROOT_PASSWORD="..." ; ./restore-mysql.ps1 <备份目录>
# 示例：./restore-mysql.ps1 .\backups\20260928-123456
$ErrorActionPreference = 'Stop'

if ($args.Count -lt 1) { throw '用法: ./restore-mysql.ps1 <备份目录>' }
$backupDir = $args[0]
if (-not (Test-Path -LiteralPath $backupDir -PathType Container)) { throw "目录不存在: $backupDir" }

$mysqlHost = $env:MYSQL_HOST
if (-not $mysqlHost) { $mysqlHost = '127.0.0.1' }
$mysqlPassword = $env:MYSQL_ROOT_PASSWORD
if (-not $mysqlPassword) { throw '请设置 MYSQL_ROOT_PASSWORD 环境变量' }

$files = Get-ChildItem -LiteralPath $backupDir -Filter '*.sql'
if ($files.Count -eq 0) { throw '备份目录中没有 .sql 文件' }

Write-Output "即将恢复以下数据库，目标实例将覆盖这些库："
$files | ForEach-Object { Write-Output "  $($_.Name)" }
Write-Output '确认无误后按 Enter 继续，Ctrl+C 取消...'
Read-Host | Out-Null

foreach ($f in $files) {
    $db = [System.IO.Path]::GetFileNameWithoutExtension($f.Name)
    Write-Output "restoring $db from $($f.Name)"
    & mysql --host=$mysqlHost --user=root --password=$mysqlPassword $db 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "mysql restore failed for $db" }
}
Write-Output "restore complete: $backupDir"
