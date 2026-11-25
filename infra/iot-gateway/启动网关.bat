@echo off
chcp 65001 >nul
cd /d "%~dp0"
set FAMILY_GATEWAY_PAUSE=1

where node >nul 2>nul
if errorlevel 1 (
  echo.
  echo 还没有安装 Node.js。
  echo 请用浏览器打开 https://nodejs.org 下载 LTS 版本并安装，
  echo 装好后重新双击本文件。
  echo.
  pause
  start https://nodejs.org
  exit /b 1
)

echo 正在准备家庭网关（第一次可能要等一会儿）…
call npm install
if errorlevel 1 (
  echo 安装依赖失败，请检查网络后重试。
  pause
  exit /b 1
)

node src\index.js
echo.
pause
