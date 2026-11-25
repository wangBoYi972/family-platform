# 公开介绍站

这个目录同时放着两类东西：

- 公开介绍页：`index.html`、`404.html`、`favicon.svg`、`assets/`
- 仓库原有的设计笔记与验收记录（Markdown）

GitHub Pages 从 **`main` 分支的 `/docs` 目录**发布介绍页。预期地址：

<https://wangBoYi972.github.io/family-platform/>

主机名在 DNS 里不区分大小写。若 GitHub 打开后显示成全小写的 `wangboyi972.github.io`，路径仍然是 `/family-platform/`。

尚未配置自定义域名，请不要在本目录添加 `CNAME`。

## 怎样打开 Pages

介绍页合并进 `main` 之后，仓库管理员在 GitHub 上设置：

1. Settings → Pages
2. Build and deployment → Source 选择 **Deploy from a branch**
3. Branch 选择 **`main`**，文件夹选择 **`/docs`**
4. 保存

在此之前不要把 Pages 指到当前的 `main`：那时 `/docs` 里还没有 `_config.yml`，Jekyll 会把设计笔记一并做成网页。其中包含本地开发示例，不适合作为介绍站内容。

Pages 只有在上述设置生效、并且这次改动已经在 `main` 上之后才会上线。合并前的预览地址不会出现。

## 设计笔记为什么不会跟介绍页一起公开

`_config.yml` 的 `exclude` 排除了本目录全部 Markdown。这些文件继续留在 Git 仓库里，供克隆后阅读，但不会进入 Pages 构建结果。

不要在 `docs/` 添加 `.nojekyll`。那个文件会关掉 Jekyll，Markdown 就会按原文件被 Pages 公开。

介绍页是纯静态页面：没有登录，没有收集个人信息的表单，也没有后端。页面里的家居、账本、档案、消息等区域是示例界面，使用标明的演示数据，不会请求后端。同目录的 `iot-panel-preview.html` 是家居面板的另一份静态预览，介绍页不改写它。

视觉对齐管理端的液态玻璃样式，色值、圆角、按钮和顶栏来自 `apps/admin-web/src/styles/variables.css` 与 `apps/admin-web/src/App.vue`，小屋徽标来自 `apps/admin-web/src/components/BrandMark.vue`。家庭端 `apps/mini-family/src/styles/theme.css` 使用同一套色值。
