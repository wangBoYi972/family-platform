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

## 2026-11-01（续）刷新不打断翻页 / 管理端内容页分页

- **实时刷新改成「就地刷新」**：`usePagedList` 新增 `reload()`，把已翻过的 1..N 页重拉回来原地替换，
  不再重置到第一页。此前用户翻到第 3 页时，别的设备改一条数据就把他拽回顶部、滚动位置也丢，
  比不刷新更烦人。失败时保留原内容只记错误，绝不清空。
- 页码由 `summarizeReload`（`paged-list.mjs`，4 项单测）重新推算：中途有人删数据会让后面的页变短，
  页码必须收回来，否则下一次「加载更多」会跳过一整段；全满页时保守地认为还有下一页。
- 面板统一导出 `sync()`（分页面板 = `reload`，不分页 = `load`），宿主的实时刷新只认这一个方法名，
  不会再有人顺手调 `load()` 把位置打掉。动态与相册复用已解析的图片地址，刷新不再重复换一次 URL。
- **管理端内容页（公告 / 日历 / 档案）改服务端分页 + 关键字**：此前不传 `page`，后端按老默认取前 500 条，
  第 501 条之后在界面上等于不存在，搜索也只覆盖已加载的部分。现在每页 12 条、关键字 300ms 防抖，
  编辑留在当前页、删除后末页空了自动退一页。成员列表没有服务端关键字，仍是本地过滤。

## 2026-11-01（续二）聊天历史可回溯

- **修一个「聊天越用越看不到新消息」的 bug**：`GET /im/conversations/{id}/messages` 原来是
  「正序 + `LIMIT 100`」，等于永远只返回**最早**的 100 条。会话一旦超过 100 条，最新消息再也不会出现在界面上，
  4 秒轮询也追不上。现在不传游标时返回**最近** 100 条（倒序取完再翻回正序），轮询的 `after` 语义不变。
- 新增 `before` 游标与 `limit`（默认 100、上限 200），聊天面板顶部出现「查看更早的消息」，
  拉回来的历史前插后靠 `scroll-into-view` 把视口钉在原来那一条上，不会把人甩到别的楼层。
- 测试：`ImIntegrationTest` 新增一例——130 条消息里，首次进入拿到第 31~130 条，
  `before` 补回第 1~30 条且仍是正序，`after` 在最新一条之后为空。

## 2026-11-01（续三）聊天改走实时推送

- **新消息秒到**：`ImServiceImpl#send` 在事务提交后把整条消息下发成 `{"type":"message",...}` 帧，
  聊天面板收到直接上屏，不再等 4 秒轮询。帧里带齐 id / 发送人 / 类型 / 正文 / 时间——
  只推一个 id 等于让客户端再拉一次，白搭一条推送。
- **必须提交后再推**：提前推会把一条最终回滚掉的消息显示在家人屏幕上（原来那句 `notifyFamily` 就在事务里）。
- **顺手去掉一处浪费**：以前每发一条消息，全家人所有面板（任务、公告、相册、文件…）都被 refresh 一遍。
  现在消息只推给聊天面板，其它面板不再被聊天消息牵着重拉。
- **轮询降级为兜底**：通道连上时 20 秒一轮、断开时回到 4 秒；推送负责及时性，轮询只负责「漏了也能自己追上」。
- **未读角标补兜底**：聊天页没打开时面板不挂载，收不到推送也发不出未读事件，角标会一直停在 0。
  宿主改为自己拉会话列表用 `sumUnread()` 求和（与面板内同一套口径，4 项单测）。
- **多实例**：结构化帧经 Redis `family:ws:event` 转发，坏帧只丢那一条并打 WARN，不让监听线程挂掉。

## 2026-11-01（续四）私聊帧定向投递、已读回执实时化

- **私聊正文不再进全家人的连接**：原来 `message` 帧按家庭广播，客户端只是「不是当前会话就不渲染」——
  帧本身还是明文躺在别的设备上，同住一个家庭就能拿到别人的私聊。
  现在 `ImServiceImpl#audience` 按会话算收件人（群聊 `null` = 整户，私聊 = 双方），
  `ImSocket#sendLocal` 握手时记下 `userId` 后按名单投递，认不出身份就跳过。Redis 信封里也带上 `audience`，
  本地直发与跨实例转发共用同一份「谁该收到」的判断。
- **已读回执从轮询改成推送**：`read` 接口在事务提交后下发 `{"type":"read",conversationId,userId,lastMessageId}`。
  `ReadReceiptMapper#advance` 改为返回影响行数（SQL 本就带 `last_message_id < #{message}`），
  返回 0 说明是原地重复上报，不发帧——否则每开一次会话全家都收到一条无变化的回执。
- **客户端去掉每 4 秒一次的 `/read`**：面板新增 `live` 属性（宿主传 `socketOpen`），
  连着实时通道时不拉回执、发完消息也不多一次往返；断开时 `poll` 的 else 分支才补。
  合并口径抽到 `apps/shared/receipts.mjs`：`mergeReceipt` 只进不退（重连后旧帧后到不能把「已读」退回去），
  `readMemberIds` 排除自己（原来自己会以「家人」的名字出现在已读名单里）。5 项单测。
- 测试：`ImSocketTest` 新增两例——定向帧只到收件人、同家庭第三者收不到；已读重复上报只推一次且只给双方。
  后端全量 66 例通过（im 6），`node --test apps/shared` 31 例通过，家庭端 / 移动端 H5 / 管理端构建通过。

## 2026-11-01（续五）全局搜索改服务端检索

- **搜不到的假象**：`GlobalSearch` 一直是「打开就并行拉八类数据，本地 `includes`」。看似简单，
  实际只在一份（后端默认前 500 条、每类再切 8 条）里找——家里数据一多，明明存在的公告、档案就是搜不出来，
  而且每次点开搜索都把全家内容下载一遍。
- 现在按 `keyword` 下推：任务/日程/动态/相册/公告/档案/文件七个接口本来就支持 `keyword + page + size`，
  客户端只取每类 8 条；成员接口没有关键字参数，一个家庭就几个人，仍整份取回本地筛。
- 输入停顿 220ms 才发请求，并用递增序号丢弃「打字快时后发先回」的过期结果；每类命中数达到上限显示 `8+`。
- 后端这部分早有覆盖：`ContentIntegrationTest` 的「内容关键字下推」保证标题与正文都参与匹配且不越过可见性，
  任务侧另有关键字/状态/逾期三者的下推用例。

## 2026-11-01（续六）多设备未读角标对齐

- **场景**：同一账号在手机和电脑都登录，手机上读完消息，电脑上的未读角标还挂着——
  要等下一条新消息进来重拉会话列表才清得掉。
- `read` 帧本来就推给会话双方，现在把它用起来：面板挂载时，收到「**自己**在读一个非当前会话」的帧
  就重拉一次会话列表（别人的已读不影响我的未读数，不跟着跑）；面板没挂载时宿主对 `read` 帧也调 `refreshUnread()`。
- 至此「多设备实时」闭环：新消息实时上屏、已读状态实时可见、已读后的角标实时对齐，三件事都走同一条 `/ws/im`。

## 生产上线前必须验收（发布阻断项）

MySQL 与 Nacos 实例联调、真实短信/微信认证、对象存储、SFU/TURN 部署与压测、双小程序真机回归、监控告警及备份恢复演练。详见 `docs/企业级工程化交付记录.md`。

