#!/bin/bash
cd "$(dirname "$0")"
export FAMILY_GATEWAY_PAUSE=1

if ! command -v node >/dev/null 2>&1; then
  echo ""
  echo "还没有安装 Node.js。"
  echo "请用浏览器打开 https://nodejs.org 下载 LTS 版本并安装，"
  echo "装好后重新双击本文件。"
  echo ""
  read -r -p "按回车关闭…"
  open "https://nodejs.org" 2>/dev/null || true
  exit 1
fi

echo "正在准备家庭网关（第一次可能要等一会儿）…"
npm install || {
  echo "安装依赖失败，请检查网络后重试。"
  read -r -p "按回车关闭…"
  exit 1
}

node src/index.js
echo ""
read -r -p "按回车关闭…"
