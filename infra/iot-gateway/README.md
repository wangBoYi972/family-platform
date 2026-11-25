# 家庭网关（给家里人看的说明）

手机 App **扫不到**你家局域网。要遥控真空调/电视，需要在**家里常开的电脑**上跑一个小程序，当作「小助手」。

你不需要懂命令行：双击启动，按中文提示输入配对码即可。

## 家里人怎么配对（推荐）

1. 手机 App 打开 **家居** → 点 **控制真空调/电视** → 会得到 **8 位配对码**  
2. 点 **「复制给家人的说明」**，把文字发到家庭群  
3. 家里电脑打开本文件夹 `infra/iot-gateway`  
4. **双击**：
   - Windows：`启动网关.bat`
   - Mac：`启动网关.command`（若提示无法打开：右键 → 打开）
   - 树莓派 / Linux：终端执行 `./启动网关.sh`
5. 若提示没有 Node.js：按提示打开 [nodejs.org](https://nodejs.org) 安装 **LTS**，再双击一次  
6. 程序问配对码时，输入手机上那 8 位，回车  
7. 看到 **「配对成功」** 后，手机回到家居页等几秒就会出现设备  

窗口要一直开着；关掉就断线。配对成功后下次再开，一般不用重新输码。

## 第一次问云端地址？

正式环境填家里人给你的地址，一般是：

- API：`https://你的域名/api`
- 程序通常能自动推算出 WebSocket

本地开发才用 `http://127.0.0.1:8080/api`。填过一次会记住。

## 开发者联调（可选）

```bash
cd infra/iot-gateway
npm install
npm start          # 交互输入配对码
# 或
PAIRING_CODE=ABCD1234 CLOUD_API=http://127.0.0.1:8080/api CLOUD_WS=ws://127.0.0.1:8080 npm start
```

也可把地址写进 `config.json`（参考 `config.example.json`）。令牌在 `gateway-state.json`，不要提交到 Git。

## Broadlink 红外（可选）

默认是 `mock` 执行器（先验证「手机 → 云 → 家里助手」链路）。接真红外：

1. Broadlink RM 与网关同网  
2. `config.json` 设 `"executor": "broadlink"`，并填 `broadlink.host`  
3. 红外码放到 `ir-codes/`（`实体ID.命令.b64`）  
4. 缺码会明确失败，不会假装发送成功  

## 安全

- 配对码约 15 分钟有效、一次性  
- 网关令牌只留在家里电脑  
- 只需出站上网，不用给家里电脑做端口映射  
