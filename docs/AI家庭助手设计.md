# AI 家庭助手（智能客服）设计与配置

> 范围：家庭端 H5 与移动端 APP「聊天」页里置顶的「家庭小助手」会话。
> 服务：`backend/family-ai-svc`（端口 8091，网关前缀 `/api/ai/**`）。

## 1. 它是什么

一个**能查本家庭数据**的对话助手，不是通用问答机器人。用户问「这周有什么任务」「体检报告放哪了」，
它调用 `family-content-svc` / `family-archive-svc` / `family-file-svc` 的现有接口取数，再用人话回答。

```
家庭端 / APP               网关               AI 服务                下游
┌────────────┐  POST /api/ai/chat  ┌──────────────┐  带用户 token  ┌──────────────┐
│ ChatPanel  │ ──────────────────▶ │ family-ai-svc│ ────────────▶ │content / archive│
│  AI 会话   │ ◀── SSE 流 ─────── │  AiService   │               │file-svc         │
└────────────┘                     │  AiTools     │ ◀──────────── └──────────────┘
                                   └──────┬───────┘
                                          │ OpenAI 兼容协议（流式）
                                   ┌──────▼───────┐
                                   │ DashScope     │
                                   │ qwen-plus     │
                                   └──────────────┘
```

## 2. 关键设计

### 权限不归 AI 服务管

工具查询一律**用当前用户自己的 token** 转发给下游，下游按原有规则校验（档案私有性、家庭隔离）。
AI 服务自身不做任何权限判断，也就没有「越权口子」可开——模型能看到的永远不超过这个用户在页面上能看到的。

### 流式输出

响应是 SSE，每个事件一行 JSON：

| type | 含义 | 前端行为 |
| --- | --- | --- |
| `delta` | 回答增量 `text` | 拼进气泡（打字机效果 + 闪烁光标） |
| `tool` | 模型开始查数据 `name` | 气泡内显示「正在查询任务…」 |
| `done` | 本轮结束 | 收起 loading，解除发送按钮禁用 |
| `error` | 出错 `message` | 把错误显示成一条提示 |

服务端用同步写流（`PrintWriter` + `flush`），不用 `SseEmitter`：一次回答 30 秒内，家庭场景并发量级下
占用一个容器线程完全可以接受，而 `SseEmitter` 要自己管线程和超时，出错路径反而更多。

### 工具调用循环

`max-tool-rounds`（默认 3）限制模型最多连续查几轮，防止它陷入「查了还不满意再查」的循环。
轮数用尽还没给出答案时，再发一次**不带 tools** 的请求让模型基于已有信息收尾。

工具失败（下游不可达、参数缺失）返回 `{"error": "..."}` 而不是抛异常——模型能向用户解释
「暂时查不到」，而不是整个对话 500。

### 历史按人存在 AI 服务自己的库

AI 对话**不入 IM 消息库**。`ai_message` 按「当前用户 + 当前家庭」存最近 40 条，
换手机登录同一账号还能接着看。打开会话先 `GET /api/ai/history`；
库是空的、本机还有升级前的旧记录时，再 `POST /api/ai/history/import` 补传一次。
本机 `family_ai_history` 只做断网缓存。发送时仍把最近若干条带给模型当上下文，
服务端只把这一轮的新提问和回答追加进库。会话列表里它是固定置顶的特殊项，
不参与轮询、不参与已读回执、不支持长按撤回。未配置模型时历史接口仍然可读。

## 3. 可用工具

| 工具 | 用途 | 下游 |
| --- | --- | --- |
| `list_tasks` | 查任务清单，可按 `TODO`/`IN_PROGRESS`/`COMPLETED` 筛选 | content-svc `/api/tasks` |
| `list_calendar` | 查日程与纪念日 | content-svc `/api/calendar` |
| `search_family_data` | 跨任务/日程/动态/相册/公告/档案/文件关键字搜索 | content、archive、file 三服务 |

单个工具结果截断在 6000 字符：工具输出是给模型「读」的，不是「背」的，超长只会稀释注意力还费 token。

## 4. 配置

配置项全部走环境变量，key 绝不写进仓库。

| 变量 | 缺省 | 说明 |
| --- | --- | --- |
| `FAMILY_AI_ENABLED` | `false` | 总开关。关着时 `/api/ai/chat` 返回 503，前端提示「AI 助手未配置」 |
| `FAMILY_AI_API_KEY` | 空 | 模型服务 key。为空且已启用时同样 503 |
| `FAMILY_AI_BASE_URL` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | OpenAI 兼容端点 |
| `FAMILY_AI_MODEL` | `qwen-plus` | 模型名 |
| `FAMILY_AI_MAX_TOOL_ROUNDS` | `3` | 工具调用轮数上限 |
| `FAMILY_AI_CONTENT_URL` | `http://127.0.0.1:8082` | content-svc 地址 |
| `FAMILY_AI_FILE_URL` | `http://127.0.0.1:8087` | file-svc 地址 |
| `FAMILY_AI_ARCHIVE_URL` | `http://127.0.0.1:8088` | archive-svc 地址 |
| `FAMILY_AI_USER_URL` | `http://127.0.0.1:8081` | user-svc 地址（Feign 装配用） |
| `FAMILY_AI_DB_URL` | `jdbc:mysql://127.0.0.1:3306/family_ai?...` | 助手历史库。本地需先建库 `family_ai` |
| `FAMILY_AI_DB_USERNAME` | `family_app` | 业务账号，只授权 `family_ai` |
| `FAMILY_AI_DB_PASSWORD` | 空 | 数据库密码，不进仓库 |

本地启用：

```bash
export FAMILY_AI_ENABLED=true
export FAMILY_AI_API_KEY=sk-xxxxxxxx
cd backend
SPRING_PROFILES_ACTIVE=local java -jar family-ai-svc/target/family-ai-svc-0.1.0-SNAPSHOT.jar
```

网关的 `/api/ai/**` 路由在 `application.yml`（`lb://family-ai-svc`）与 `application-local.yml`
（`http://127.0.0.1:8091`）都已配好。

## 5. 测试覆盖

| 测试 | 覆盖 |
| --- | --- |
| `AiEndpointTest` | 未登录 401（对话和历史都不开）、鉴权先于参数校验 |
| `AiHistoryServiceTest` | 按人隔离、只留最近 40 条、空库才导入一次、delta 拼接 |
| `AiToolsTest` | 工具定义契约、未知工具兜底、缺参数快速失败、下游不可达不中断对话 |
| `AiServiceTest` | 流式增量 + done、工具轮（token 透传、分片 arguments 合并）、轮数用尽收尾、历史裁剪、未配置 503、模型 500 转 error 事件 |
| `apps/shared/ai-client.test.mjs` | SSE 缓冲解析；服务端历史优先、空库待导入、断网不导入 |

`AiServiceTest` 用 JDK 自带 `HttpServer` 起假模型和假下游，不引 WireMock 之类的额外依赖，
断言全部基于服务端实际发出的请求体和推给前端的事件序列。

## 6. 已知边界

- 回答质量取决于所选模型；`qwen-plus` 是缺省值，可换 `qwen-max` 等。
- 工具结果直接喂 JSON，字段过多时模型可能转述不全——这是截断阈值存在的另一层原因。
- 助手历史存在 `family_ai.ai_message`，同一账号跨设备可见；断网时退回本机缓存，联网后再对齐。
- 未做按用户限流：单用户连续刷问会持续消耗 token 费用，上生产前建议在网关按用户加一道配额。
