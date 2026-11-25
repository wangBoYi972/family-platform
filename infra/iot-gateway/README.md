# 家庭 IoT 网关（家侧进程）

跑在**家里电脑 / 树莓派 / NAS**上，出站连云端。云端不扫描你家局域网。

## 能干什么

1. 用 App 生成的**配对码**注册到某个家庭  
2. 上报本地可控设备（默认：模拟空调/电视/音响/灯，用于验证控制链路）  
3. 接收云端下发的开关/调温/音量命令并执行  
4. 可选：配置 Broadlink RM 后走红外（需自备硬件与码库）

## 快速开始（本机联调）

```bash
# 1. App「家居」→「配对家庭网关」→ 拿到 8 位配对码
# 2. 在家里机器：
cd infra/iot-gateway
npm install
CLOUD_API=http://127.0.0.1:8080/api \
CLOUD_WS=ws://127.0.0.1:8080 \
PAIRING_CODE=你的配对码 \
npm start
```

成功后 App 设备列表会出现网关上报的设备；点开关即经网关执行。

环境变量也可写入 `config.json`（见 `config.example.json`）。首次用配对码注册后，令牌会写入本地 `gateway-state.json`，之后重启不用再配对。

## Broadlink 红外（可选）

默认执行器是 `mock`（本地改状态，适合先打通链路）。真机红外：

1. 准备 Broadlink RM 系列红外盒子，与网关同网  
2. `config.json` 设 `"executor": "broadlink"`，并填 `broadlink.host`  
3. 在 `ir-codes/` 下按 `entityId.command.json` 存放学习到的红外码（Base64）  
4. 未配置码时命令会失败并提示缺码——**不会假装发送成功**

## 安全说明

- 配对码 15 分钟有效、一次性  
- 网关令牌只保存在家里机器，勿提交到 Git  
- 网关只出站连你的云端 API/WS，不需要公网端口映射
