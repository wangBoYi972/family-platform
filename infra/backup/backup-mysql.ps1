# 家庭综合平台 MySQL 备份脚本（Windows / PowerShell）
# 用法：$env:MYSQL_ROOT_PASSWORD="..." ; ./backup-mysql.ps1
# 备份全部业务库到 backups/<时间戳>/，默认保留最近 7 份。
$ErrorActionPreference = 'Stop'

$mysqlHost = $env:MYSQL_HOST
if (-not $mysqlHost) { $mysqlHost = '127.0.0.1' }
$mysqlUser = 'root'
$mysqlPassword = $env:MYSQL_ROOT_PASSWORD
if (-not $mysqlPassword) { throw '请设置 MYSQL_ROOT_PASSWORD 环境变量' }

$databases = @('family_user','family_content','family_file','family_archive','family_im','family_media')
$backupRoot = Join-Path $PSScriptRoot 'backups'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$targetDir = Join-Path $backupRoot $stamp
New-Item -ItemType Directory -Force -Path $targetDir | Out-Null

foreach ($db in $databases) {
    $out = Join-Path $targetDir "$db.sql"
    Write-Output "backing up $db -> $out"
    & mysqldump `
        --host=$mysqlHost --user=$mysqlUser --password=$mysqlPassword `
        --single-transaction --routines --triggers --hex-blob `
        --databases $db 2>&1 | Out-File -Encoding utf8 -FilePath $out
    if ($LASTEXITCODE -ne 0) { throw "mysqldump failed for $db" }
}

# 轮转：仅保留最近 7 份
Get-ChildItem -LiteralPath $backupRoot -Directory | Sort-Object Name -Descending |
    Select-Object -Skip 7 | ForEach-Object {
        Write-Output "rotate: removing $($_.Name)"
        Remove-Item -LiteralPath $_.FullName -Recurse -Force
    }
Write-Output "backup complete: $targetDir"
