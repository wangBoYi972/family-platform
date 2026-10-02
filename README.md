# 家庭综合平台

这是面向家庭内部使用的 Spring Cloud Alibaba 微服务项目。项目采用独立服务、独立数据归属与 Gateway 统一入口；Android APP 与两个微信小程序共享业务接口，但按客户端范围进行授权。

## 首期服务

- `family-gateway`：统一 API 入口、路由、跨域与后续 JWT 鉴权。
- `family-user-svc`：成员白名单、登录、家庭关系、角色权限。
- `family-content-svc`：相册、动态、日历、任务、公告。
- `family-file-svc`：私有文件上传、下载授权和资源元数据。
- `family-archive-svc`：家庭档案、健康记录、访问审计。
- `family-im-svc`：会话、消息、已读和 WebSocket 信令。
- `family-media-svc`：独立媒体服务，负责 SFU 与 TURN 对接。

## 当前状态

当前代码库已完成首期服务的基础业务闭环：

- Gateway 已提供统一路由与 JWT 前置校验；用户服务已提供白名单登录、会话校验、注销和家庭成员管理。
- 内容、文件、档案、IM 与媒体会话服务均维护独立数据表和 Flyway 迁移；家庭归属从已校验会话中取得，不信任客户端传入的家庭标识。
- 本地开发使用独立 H2 数据库；默认生产配置使用 MySQL、Nacos 与环境变量注入密钥和数据库密码。
- 管理端已接入成员、任务、公告、日历、档案等服务接口。

以下事项仍属于正式上线前的生产验收，而不是“已完成”的替代说法：MySQL 与 Nacos 实例联调、真实短信或微信认证、对象存储、SFU/TURN 部署与压测、双小程序真机回归、监控告警及备份恢复演练。

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

## 生产上线前必须验收（发布阻断项）

MySQL 与 Nacos 实例联调、真实短信/微信认证、对象存储、SFU/TURN 部署与压测、双小程序真机回归、监控告警及备份恢复演练。详见 `docs/企业级工程化交付记录.md`。

