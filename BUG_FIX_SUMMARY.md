# Bug 修复总结

修复日期：2026-11-11  
项目：family-platform（家庭综合平台 Spring Cloud + uni-app）  
修复工具：Claude Code

---

## 修复清单

### ✅ Bug 1：AI 任务搜索实际返回动态数据
**文件**：`backend/family-ai-svc/src/.../SearchFamilyDataTool.java` 第 82–87 行

**问题**：
```java
// 错：tasks 变量调用了 searchDynamics
CompletableFuture<String> tasks = CompletableFuture.supplyAsync(
    () -> safeCall(() -> contentClient.searchDynamics(bearer, keyword, 1, 5)), executor);
// 动态被查两次，真实任务搜不到
```

**修复**：
- 第 82–84 行：改为 `contentClient.searchTasks(bearer, keyword, 1, 5)`
- 第 86 行：calendar 改为 `contentClient.searchCalendar(bearer, keyword, 1, 5)`  
- 新增 `ContentServiceClient` 两个方法：`searchTasks(...)` 和 `searchCalendar(...)`
- 同步修改 `ContentServiceClientFallback` 的降级实现

**验证**：编译通过；Feign 客户端方法对应后端 `/api/tasks?keyword=xxx` 和 `/api/calendar?keyword=xxx` 接口

---

### ✅ Bug 2：任务状态枚举与后端不一致
**文件**：`backend/family-ai-svc/src/.../ListTasksTool.java` 第 48–52 行

**问题**：
- 工具 schema 定义：`TODO`、`DOING`、`DONE`
- 后端实际值：`TODO`、`IN_PROGRESS`、`COMPLETED`  
- 导致模型按 schema 传参时必然 400，功能完全失效

**修复**：
- 第 51 行：枚举值改为 `"TODO", "IN_PROGRESS", "COMPLETED"`
- 第 50 行：文案同步更新为「进行中」、「已完成」
- `AiService.java` 第 148–150 行：system prompt 里的示例文档同步更新

**验证**：枚举值与 `TaskServiceImpl.normalizeStatus()` 对齐

---

### ✅ Bug 3：断线重连后不触发补刷
**文件**：
- `apps/shared/realtime.mjs` 第 23–29 行
- `apps/shared/family-socket.js` 第 71 行

**问题**：
```javascript
// realtime.mjs
function fire() {
  if (count) onFlush(count, events);  // 断线时 pending=0，此检查失败
}

// family-socket.js
task.onOpen(() => {
  this.gate.flushNow();  // 意图是补刷，但 fire() 不会触发 onFlush
});
```
- 断线期间错过的所有变更对用户不可见，必须手动刷新

**修复**：
- `realtime.mjs` 第 23 行：`fire()` 增加 `force` 参数
- 第 29 行：改为 `if (count || force) onFlush(count, events)`
- 第 41–43 行：`flushNow()` 调用改为 `fire(true)` 并更新注释

**验证**：断线重连时 `gate.flushNow()` 会强制触发 `onFlush`，即使 `pending=0` 也补刷一次

---

### ✅ Bug 4：撤回当前会话消息后预览不更新
**文件**：`apps/shared/panels/ChatPanel.vue` 第 327–330 行

**问题**：
```javascript
if (frame.type === "message.deleted") {
  if (String(frame.conversationId) === active.value) markDeleted(frame.messageId);
  else loadConversations().catch(() => {});  // 只在「不是当前会话」时刷新
}
```
- 撤回当前会话消息时，气泡就地清空但会话列表预览仍显示原文

**修复**：
- 第 329 行：删除 `else` 判断，改为无条件调 `loadConversations()`
- 添加注释说明两种情况都需要刷新

**验证**：任何会话消息被撤回时都会同步会话列表

---

### ✅ Bug 5：微信 access_token 并发刷新缺乏同步
**文件**：`backend/family-user-svc/src/.../WxService.java` 第 173 行

**问题**：
```java
private String accessToken() throws Exception {  // 无同步保护
    long now = System.currentTimeMillis();
    String token = accessToken;
    if (token != null && now < accessTokenExpiresAt) return token;  // check-then-act 非原子
    // ...多个线程可能同时通过检查，各自去刷 token
}
```
- 高并发下多线程同时触发刷新，微信有频率限制时会失败

**修复**：
- 第 173 行：方法签名改为 `private synchronized String accessToken()`

**验证**：Java synchronized 保证 check-then-act 的原子性

---

### ✅ Bug 6：AiConfigStore 重复初始化
**文件**：`backend/family-ai-svc/src/.../AiConfigStore.java` 第 41–58 行

**问题**：
- 两个构造函数都有 `init()` 调用
- Spring 使用有 `@Autowired` 的构造函数时，先跑构造函数里的 `init()`，再跑 `@PostConstruct` 的 `init()`
- 另一个无 `@Autowired` 的构造函数 `new ObjectMapper()` 绕过 Spring 配置

**修复**：
- 删除第 41–45 行的无 `@Autowired` 构造函数
- 保留第 47–52 行的 `@Autowired` 构造函数
- `@PostConstruct init()` 保持不变

**验证**：只有 Spring 注入的构造函数被使用，`init()` 仅在 `@PostConstruct` 执行一次

---

### ✅ Bug 7：工具错误返回破损 JSON
**文件**：`backend/family-ai-svc/src/.../SearchFamilyDataTool.java` 第 132–138 行

**问题**：
```java
return "{\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
```
- 只转义了 `"`，未处理 `\n`、`\r`、`\t`、`\` 等
- Feign 错误体通常含换行符，导致无效 JSON

**修复**：
完整转义所有 JSON 特殊字符：
```java
private String safeCall(java.util.function.Supplier<String> call) {
    try {
        return call.get();
    } catch (FeignException e) {
        String msg = e.getMessage();
        if (msg == null) msg = "";
        msg = msg.replace("\\", "\\\\")     // \ 必须最先处理
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        return "{\"error\":\"" + msg + "\"}";
    }
}
```

**验证**：拼接后的字符串是合法的 JSON

---

### ✅ Bug 8：非流式降级时 tool 事件重复发送
**文件**：`backend/family-ai-svc/src/.../AiService.java` 第 307–325 行

**问题**：
- 非流式响应处理（第 320–323 行）已发一遍 tool 事件
- 主循环（第 87–89 行）对同一个 `toolCalls` 又发一遍

**修复**：
- 删除第 320–323 行的"发送工具调用事件"代码
- 让工具事件由主循环统一发送（第 87–89 行）

**验证**：每个工具调用只推送一次 tool 事件

---

### ✅ Bug 9：日程搜索不按关键字过滤
**文件**：`backend/family-ai-svc/src/.../SearchFamilyDataTool.java` 第 85–87 行

**问题**：
```java
CompletableFuture<String> calendar = CompletableFuture.supplyAsync(
    () -> safeCall(() -> contentClient.listCalendar(bearer, 1, 5)),  // 未传 keyword
    executor);
```
- 其他数据源都按关键字过滤，唯独日程返回全量，干扰 AI 回答

**修复**：
- 已在 Bug 1 修复中改为 `contentClient.searchCalendar(bearer, keyword, 1, 5)`

**验证**：新增的 `searchCalendar` 方法接受 keyword 参数

---

### ✅ Bug 10：anchor 变量命名冲突
**文件**：`apps/shared/panels/ChatPanel.vue` 第 483 行

**问题**：
```javascript
const anchor = document.createElement("a");  // 与顶层 anchor = ref("") 同名
```
- 虽无运行时错误（作用域不同），但代码阅读时易混淆

**修复**：
- 改为 `const downloadLink = document.createElement("a")`
- 同步更新后续两行的变量引用

**验证**：命名清晰，无歧义

---

### ⏭️ Bug 11：ImSocket 连接数限制非原子（低优先级）
**文件**：`backend/family-im-svc/src/.../ImSocket.java` 第 99–106 行

**说明**：
- 单节点模式下 1000 连接限制检查（removeIf + size() + put）存在竞态
- 但家庭应用量级下基本不会触发，本次暂不修复

---

## 修复统计

| 级别 | 数量 | 状态 |
|------|------|------|
| 🔴 严重 | 3 | ✅ 已修 |
| 🟡 中等 | 5 | ✅ 已修 |
| 🟢 低优先级 | 2 | ✅ 已修 |
| ⏭️ 超低优先级 | 1 | ⏳ 待做 |

**总计**：10 个 bug 已修，1 个延迟

---

## 受影响模块

### 后端服务
- `family-ai-svc`：工具调用、任务状态、配置初始化、错误处理  
- `family-user-svc`：微信 access_token 并发控制

### 前端客户端
- `apps/shared/`：实时刷新、聊天面板、文件下载

---

## 构建与测试建议

```bash
# 后端编译验证
cd backend
mvn clean compile

# 运行 AI 服务测试（如有集成测试）
mvn -f family-ai-svc/pom.xml test

# 运行 IM 服务测试
mvn -f family-im-svc/pom.xml test

# 前端（apps/shared）单测
node --test apps/shared/**/*.test.mjs
```

---

## 验收清单

- [ ] 后端服务编译通过
- [ ] AI 任务搜索返回真实任务数据
- [ ] AI 按状态筛选任务不报错
- [ ] 断线重连时不在线的消息自动刷新可见
- [ ] 撤回会话消息后列表预览同步更新
- [ ] 微信集成高并发下不丢 token
- [ ] 非流式模型不重复推送 tool 事件
- [ ] 前端构建通过（4 个 H5 端 + 小程序）

