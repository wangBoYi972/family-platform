# 家庭综合平台

这是面向家庭内部使用的 Spring Cloud Alibaba 微服务项目。项目采用独立服务、独立数据归属与 Gateway 统一入口；Android APP 与两个微信小程序共享业务接口，但按客户端范围进行授权。

## 首期服务

- `family-gateway`：统一 API 入口、路由、跨域与后续 JWT 鉴权。
- `family-user-svc`：成员白名单、登录、家庭关系、角色权限。
- `family-content-svc`：相册、动态、日历、任务、公告。
- `family-file-svc`：私有文件上传、下载授权和资源元数据。
- `family-archive-svc`：家庭档案、健康记录、访问审计。
- `family-im-svc`：会话、消息、已读和 WebSocket 信令。
- `family-media-svc`：实时通话服务，负责 WebSocket 信令、TURN 时效凭据与通话记录（媒体走 WebRTC P2P）。

## 当前状态

当前代码库已完成首期服务的基础业务闭环：

- Gateway 已提供统一路由与 JWT 前置校验；用户服务已提供白名单登录、会话校验、注销和家庭成员管理。
- 内容、文件、档案、IM 与媒体会话服务均维护独立数据表和 Flyway 迁移；家庭归属从已校验会话中取得，不信任客户端传入的家庭标识。
- 本地开发使用独立 H2 数据库；默认生产配置使用 MySQL、Nacos 与环境变量注入密钥和数据库密码。
- 管理端已接入成员、任务、公告、日历、档案等服务接口。

- 实时通话已打通：1 对 1 与最多 8 人家庭通话、一次性票据鉴权、信令跨实例广播、coturn 中继与断线宽限，详见 `docs/实时通话设计.md`。

以下事项仍属于正式上线前的生产验收，而不是“已完成”的替代说法：MySQL 与 Nacos 实例联调、真实短信或微信认证、对象存储、公网 TURN 实网穿透与压测、8 人以上所需的 SFU、双小程序真机回归、监控告警及备份恢复演练。

## 工程化能力（企业级）

- **链路追踪与结构化日志**：`family-security` 内置请求过滤器生成/透传 `X-Request-Id`（MDC traceId），Feign 自动透传；各服务 `logback-spring.xml` 在 `prod` profile 输出结构化 JSON，敏感头不落日志。
- **API 文档**：各服务接入 OpenAPI（springdoc），`local` profile 启用 `/swagger-ui`；`prod` 默认关闭，需 `FAMILY_DOCS_ENABLED=true` 显式开启。
- **可观测端点收敛**：`prod` 仅暴露 `health,info,prometheus`；Prometheus + Grafana 一键接入（见 `infra/`）。
- **依赖治理**：根 pom 启用 maven-enforcer（强制 Java 21、封禁高危依赖）；`mvn -f backend/pom.xml verify -Pscan` 运行 OWASP dependency-check 漏洞扫描（CVSS≥7 失败）。
- **CI/CD**：`.github/workflows/ci.yml` 覆盖后端 strict verify、管理端构建与依赖扫描。
- **容器化**：`infra/docker-compose.yml` 一键拉起 MySQL、Nacos、Redis 与全部服务；各服务提供多阶段、非 root 运行 Dockerfile。

## 2026-09-29 产品流程补齐

- APP 的 H5 已修复空白页，接入任务创建、负责人选择、开始与完成、搜索和逾期筛选。
- 管理端已修正任务状态按钮，补齐日历日期展示、纪念日标记、编辑与删除确认。
- 后端 30 个 Spring 类显式使用 @Autowired；38 项后端测试和 4 项前端规则测试通过，主要任务与日历流程已通过真实接口浏览器验证。
- 范围、证据和剩余事项见 docs/产品完善与验收记录-20260929.md。

## 2026-10-30 实时通话

- 新增 `family-media-svc` 通话闭环：`call_session` / `call_participant` 两表、REST 状态机接口、`/ws/call` 信令端点。
- 鉴权改用一次性票据（`POST /api/media/ws-ticket`，30 秒失效），JWT 不进 URL；网关 `/ws/call` 走 `lb:ws://`。
- TURN 使用 coturn 时效凭据（HMAC-SHA1），`infra/docker-compose.yml` 内置 coturn（host 网络，3478 + 49160-49200）。
- 客户端：`call-client.js` 负责重连/心跳，`webrtc-mesh.js` 负责网状 PeerConnection，`CallPanel.vue` 提供拨号、来电与多人宫格界面，家庭端与移动端均已接入。
- 测试：`CallIntegrationTest`（业务与权限）、`CallSignalingTest`（真实 WebSocket 链路）通过；H5 两端构建通过。
- 设计、协议与部署细节见 `docs/实时通话设计.md`。
- 冒烟脚本：`scripts/call-smoke.mjs`（信令链路）、`scripts/call-browser-smoke.mjs`（真实浏览器双端出图）、`scripts/call-browser-group.mjs`（3 人网状），用法见设计文档第 10 节。

## 2026-10-31 前端去重与档案端修复

- 通话链路（`CallPanel` / `CallVideo` / `CallAudio` / `call-client` / `webrtc-mesh`，约 1300 行）从两个客户端各一份收敛到 `apps/shared/call/`，
  改一处两端同时生效；此前任何通话 bug 都要改两遍。
- 四端请求错误文案统一到 `apps/shared/request-message.js`：网关 502/503/504 现在四端都提示「对应后端服务未启动」，
  断网不再抛 `errMsg` / `Failed to fetch`。此前这条修复只落在管理端。
- 修复档案端 H5 白屏：`package.json` 缺 `@dcloudio/uni-h5` 且 uni-app 为 alpha 版，构建「成功」但产物只有空壳，
  页面 chunk（`pages-index-index.*.js`）根本没生成。
- 端口规划：管理端 5173 / 家庭端 5174 / 移动端 5175 / 档案端 5176，网关 CORS 同步放行。

## 2026-10-31 后端补齐（映射表 / 分页 / 事件清理）

- `content_file_mapping` 从「建了没人用的死表」变成唯一真相：写入先删后插并回写旧列，读取批量查映射表、缺失时回退旧列，
  启动时幂等回填历史数据，删除内容同步清理关联。此前重构只做了一半，库里存在两套真相。
- 列表接口补齐分页：`page`/`size` 可选，不传时行为与改造前一致（`PageQuery` 统一归一化，上限 500）。
  接入 `/api/tasks`、`/api/{kind}`、`/api/files`、`/api/im/conversations`、`/api/archives`、
  `/api/archives/{id}/audit`、`/api/media/calls/history`，消除「超过 500 条静默丢数据」。
- `OutboxPublisher` 增加已发布事件清理（默认保留 7 天，分批删除）与 PENDING 积压告警，Outbox 表不再只增不减。
- 前后端共 59 项后端测试通过（content 8 项，含映射表写入/回填、分页、outbox 清理 SQL 方言验证）。

## 2026-10-31 前端接分页 / 共享层收敛 / 并发测试修复

- 列表接上分页：任务、公告、动态、相册、文件、档案六个面板都改为分页加载，底部统一 `ListMore` 组件
  （四端共用「加载更多 / 没有更多了」交互）。此前后端已支持 `page`/`size`，但前端仍只取第一页。
- 筛选与统计的作用域显式化：`keyword`/`status` 筛选与「文件占用、档案总数」这类统计天然只覆盖已加载数据，
  未加载完时面板会写明「基于已加载的 N 条」，避免被误读成数据丢失。
- 共享层新增 `paged-list.mjs`（纯逻辑，5 项 node 单测）、`use-paged-list.js`（状态机）、`ListMore.vue`、
  `uni-client.js`；`TaskPanel` / `NoticesPanel` / `DynamicsPanel` / `EntryEditor` 迁入 `apps/shared/panels`。
- 移动端不再自绘简化列表：动态与公告复用共享面板（原来只渲染纯文本行，没有图片也点不开编辑）。
- 两个 H5 端的 `utils/request.js` 收敛为 `uni-client.js` 的薄转出口。此前一份有 `upload`/`loadImageUrl`、
  一份没有，导致共享面板在移动端构建时报 `loadImageUrl is not exported`。
- 修复 `PasswordAuthenticationIntegrationTest` 全量跑偶发失败：H2 默认锁等待 1s，8 线程并发登录靠
  `family_white_list` 行锁串行化，负载高时超时后会话即关闭，表现为 `JDBC rollback failed: Connection is closed`。
  已把测试库 `LOCK_TIMEOUT` 调到 15s（用 1ms 可稳定复现同一异常，确认是测试环境而非业务缺陷）。

## 2026-10-31 筛选下推服务端 / 补齐业务事件消费者

- 任务筛选下推到 SQL：`/api/tasks` 新增可选 `keyword`（标题或负责人）与 `status`（`TODO`/`IN_PROGRESS`/`COMPLETED`/`OVERDUE`）。
  分页接上之后，客户端本地过滤只能看到当前一页，「符合条件但还没加载」的任务会被彻底藏起来；改由数据库判断。
  关键字条件强制包在 `and(...)` 内，否则会和家庭隔离条件拼成 OR 而串户；未知状态值返回 400 而不是静默返回全量。
- 任务面板改为服务端筛选：输入停顿 300ms 后从第一页重拉，翻页继续带同一组筛选条件。
- 新增 `content/messaging/FileEventConsumer`：消费 `file.deleted`，调用 `ContentFileMappingService#detachFile`
  把该文件从映射表与旧列 `file_ids` 里一起摘掉。此前文件删除后内容仍留着文件 id，前端只能渲染破图；
  这是没有业务消费者的直接后果——`task.*`、`file.*` 事件原来只有 im-svc 那个「广播到 WebSocket」的消费者。
  消费按 `eventType` 判类型（本服务队列绑定 `#`，会收到全站事件），操作天然幂等，重投只白跑一次。
- 后端 61 项测试通过（content 10 项，新增文件删除清理引用、任务筛选下推两组）。

## 2026-10-31 筛选全面下推 / 移动端面板补齐

- 内容（`/api/{kind}`）、文件（`/api/files`）、档案（`/api/archives`）也改为服务端筛选：
  内容匹配标题或正文，文件匹配文件名或 MIME，档案匹配标题并支持 `category`。
  三处关键字条件同样用 `and(...)` 分组，避免与可见性条件拼成 OR 把私有数据带出来。
- 动态、相册、文件、档案四个面板跟随改造：关键字进查询串、300ms 防抖、从第一页重拉。
  筛选期间隐藏统计块（「全部文件 / 占用空间 / 档案总数」统计的本来就是列表内容），改为显示结果条数。
- `CalendarPanel` / `AlbumsPanel` / `FilesPanel` / `ArchivePanel` 迁入 `apps/shared/panels`；
  移动端补齐到六个 tab（任务 / 动态 / 公告 / 相册 / 文件 / 档案），全部复用共享面板，不再各写一份。
- 后端 64 项测试通过（file 与 archive 各新增一组筛选测试，content 增加关键字+可见性交叉验证）。

## 2026-10-31 共享面板收尾 / 移动端功能对齐

- `MembersPanel` / `ChatPanel` / `AnniversaryPanel` / `GlobalSearch` 迁入 `apps/shared/panels`（现共 11 个面板 + `EntryEditor`），
  家庭端与移动端每个功能都只剩一份实现。
- 共享面板不再认识宿主的 tab 名称：搜索命中与「去日历看看」只上报数据源 key（`tasks` / `calendar` / `dynamics` / …），
  各端用 `navTargets` 映射到自己的结构。此前 `GlobalSearch` 直接发 `{tab:"家庭圈", circleTab:"动态"}`，只有家庭端对得上。
- 移动端从「六个平铺 tab」整理为五个语义分组：任务 / 日程（家庭日历·纪念日）/ 家庭圈（动态·相册）/ 消息（聊天 + 未读角标）/ 我的家（公告·文件·档案·成员），
  全局搜索移到顶栏。手机屏放不下九个平铺 tab，分组后仍能横向滚动而不用翻找。
- 移动端 `App.vue` 补齐 `--c-*` 全套语义色（`--c-sky` / `--c-sage` / `--c-sand` / `--c-plum` 及其 soft 变体）：
  H5 上引用未定义的 CSS 变量不报错、只静默丢样式，缺变量比缺类名更难发现。

## 2026-11-01 实时刷新 / 看板指标 / 时光轴共享

- **多设备实时刷新补上客户端**：后端一直有「事件 → Outbox → im-svc → 同家庭广播 `{"type":"refresh"}`」链路，
  但没有任何客户端连 `/ws/im`，等于断头路。新增 `apps/shared/family-socket.js`：JWT 换一次性票据 → 握手 →
  收到 refresh 后重拉当前面板；`realtime.mjs` 把 400ms 内的多次事件合并成一次刷新（4 项单测）。
  聊天面板不参与（它自己轮询，重拉会冲掉草稿）。
- 顺手修掉上线即失效的隐患：`ImSocket` 的握手来源白名单原来硬编码 `http://localhost:*`，
  换成真实域名会被 403 静默拒绝。改为 `family.im.allowed-origins`（`FAMILY_IM_ALLOWED_ORIGINS`）可配。
- **管理端任务看板改服务端分页**：此前一次取 500 条在本地筛，超出部分永远看不到。现在关键字（300ms 防抖）与状态
  都进查询串，每页 12 条带上一页/下一页。指标不能拿当前页算，另开 `GET /api/tasks/stats` 返回
  `{total, pending, completed, overdue}`，逾期口径与列表筛选共用同一段条件。
- `AlbumTimeline`（照片时光轴）迁入 `apps/shared/panels`，移动端家庭圈补上第三个子标签，功能与家庭端对齐。
- 测试：content 12 项、shared 单测 18 项通过；管理端与两端 H5 构建通过。分页断言改用独立家庭，
  避免「别的测试先造了几条任务」把总数断言打歪。

## 生产上线前必须验收（发布阻断项）

MySQL 与 Nacos 实例联调、真实短信/微信认证、对象存储、SFU/TURN 部署与压测、双小程序真机回归、监控告警及备份恢复演练。详见 `docs/企业级工程化交付记录.md`。

