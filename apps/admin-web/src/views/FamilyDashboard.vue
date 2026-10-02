<script setup>
import { ref } from "vue";

const activeMenu = ref("概览");
const menus = ["概览", "成员", "任务", "档案", "通知"];
const tasks = [
  { title: "整理本周家庭相册", owner: "妈妈", deadline: "今天", state: "进行中" },
  { title: "确认国庆出行安排", owner: "全家", deadline: "9月30日", state: "待确认" },
  { title: "更新体检档案", owner: "爸爸", deadline: "10月3日", state: "待开始" }
];
const members = [
  { name: "王博弈", role: "管理员", phone: "138****1024" },
  { name: "妈妈", role: "成员", phone: "139****2546" },
  { name: "爸爸", role: "成员", phone: "186****3478" }
];
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark">家</span>
        <strong>家庭综合平台</strong>
      </div>
      <div class="topbar-actions">
        <el-badge is-dot><el-button text>通知</el-button></el-badge>
        <el-avatar :size="32">王</el-avatar>
      </div>
    </header>

    <main class="workspace">
      <aside class="sidebar">
        <el-menu :default-active="activeMenu" class="nav-menu">
          <el-menu-item v-for="menu in menus" :key="menu" :index="menu" @click="activeMenu = menu">
            {{ menu }}
          </el-menu-item>
        </el-menu>
        <div class="family-context">
          <span>当前家庭</span>
          <strong>王家</strong>
          <small>3 位成员</small>
        </div>
      </aside>

      <section class="content">
        <div class="page-heading">
          <div>
            <p class="eyebrow">家庭工作台</p>
            <h1>早上好，王博弈</h1>
            <p class="subtitle">今天有 2 项待办需要家庭成员共同确认。</p>
          </div>
          <el-button type="primary">新建任务</el-button>
        </div>

        <section class="metrics" aria-label="家庭概览">
          <div class="metric"><span>本周已完成</span><strong>8</strong><small>项家庭任务</small></div>
          <div class="metric"><span>待处理任务</span><strong>3</strong><small>含 1 项全员任务</small></div>
          <div class="metric"><span>本月新增档案</span><strong>6</strong><small>均已设置访问范围</small></div>
        </section>

        <section class="content-grid">
          <article class="section-block">
            <div class="section-heading">
              <div><p class="eyebrow">任务</p><h2>近期安排</h2></div>
              <el-button text>查看全部</el-button>
            </div>
            <el-table :data="tasks" class="task-table">
              <el-table-column prop="title" label="事项" min-width="220" />
              <el-table-column prop="owner" label="负责人" width="92" />
              <el-table-column prop="deadline" label="截止时间" width="112" />
              <el-table-column label="状态" width="104">
                <template #default="scope">
                  <el-tag :type="scope.row.state === '进行中' ? 'success' : 'info'" effect="plain">{{ scope.row.state }}</el-tag>
                </template>
              </el-table-column>
            </el-table>
          </article>

          <article class="section-block">
            <div class="section-heading">
              <div><p class="eyebrow">权限</p><h2>家庭成员</h2></div>
              <el-button text>管理白名单</el-button>
            </div>
            <ul class="member-list">
              <li v-for="member in members" :key="member.phone">
                <el-avatar :size="36">{{ member.name.slice(0, 1) }}</el-avatar>
                <div class="member-info"><strong>{{ member.name }}</strong><span>{{ member.phone }}</span></div>
                <div class="member-role"><el-tag size="small" effect="plain">{{ member.role }}</el-tag><small>已启用</small></div>
              </li>
            </ul>
          </article>
        </section>

        <section class="notice-bar">
          <span class="eyebrow">家庭公告</span>
          <strong>国庆出行安排将在本周日晚完成确认，请成员及时补充可出行时间。</strong>
          <el-button text>查看公告</el-button>
        </section>
      </section>
    </main>
  </div>
</template>

<style scoped>
.app-shell { min-height: 100vh; }
.topbar { display: flex; align-items: center; justify-content: space-between; height: 64px; padding: 0 40px; border-bottom: 1px solid var(--family-border); }
.brand { display: flex; align-items: center; gap: 10px; font-size: 15px; }
.brand-mark { display: grid; width: 28px; height: 28px; place-items: center; color: #fff; background: var(--family-primary); border-radius: 6px; font-size: 14px; }
.topbar-actions { display: flex; align-items: center; gap: 16px; }
.workspace { display: grid; grid-template-columns: 216px minmax(0, 1fr); max-width: 1440px; min-height: calc(100vh - 64px); margin: 0 auto; }
.sidebar { display: flex; flex-direction: column; justify-content: space-between; padding: 32px 20px 24px; border-right: 1px solid var(--family-border); }
.nav-menu { border-right: 0; }
.nav-menu :deep(.el-menu-item) { height: 42px; margin-bottom: 4px; border-radius: 6px; color: var(--family-muted); }
.nav-menu :deep(.el-menu-item:hover), .nav-menu :deep(.el-menu-item.is-active) { color: var(--family-primary); background: var(--family-primary-soft); }
.family-context { display: grid; gap: 4px; padding: 16px; color: var(--family-muted); background: var(--family-surface); border-radius: 6px; font-size: 13px; }
.family-context strong { color: var(--family-text); font-size: 15px; }
.content { padding: 56px 64px 64px; }
.page-heading, .section-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 24px; }
.eyebrow { margin: 0 0 8px; color: var(--family-muted); font-size: 12px; letter-spacing: .08em; text-transform: uppercase; }
.page-heading h1, .section-heading h2 { margin: 0; font-weight: 600; letter-spacing: -.02em; }
.page-heading h1 { font-size: 32px; }
.section-heading h2 { font-size: 20px; }
.subtitle { margin: 12px 0 0; color: var(--family-muted); }
.metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); margin: 48px 0; border-top: 1px solid var(--family-border); border-bottom: 1px solid var(--family-border); }
.metric { display: grid; gap: 8px; padding: 24px 28px; border-right: 1px solid var(--family-border); }
.metric:last-child { border-right: 0; }
.metric span, .metric small { color: var(--family-muted); font-size: 13px; }
.metric strong { font-size: 30px; font-weight: 600; line-height: 1; }
.content-grid { display: grid; grid-template-columns: minmax(0, 1.4fr) minmax(288px, .85fr); gap: 40px; }
.section-heading { margin-bottom: 20px; }
.task-table { border-top: 1px solid var(--family-border); }
.task-table :deep(th.el-table__cell) { color: var(--family-muted); font-weight: 400; background: transparent; }
.task-table :deep(.el-table__inner-wrapper::before) { display: none; }
.task-table :deep(.el-table__row:hover > td.el-table__cell) { background: var(--family-surface); }
.member-list { padding: 0; margin: 0; list-style: none; border-top: 1px solid var(--family-border); }
.member-list li { display: flex; align-items: center; gap: 12px; padding: 16px 0; border-bottom: 1px solid var(--family-border); }
.member-info { display: grid; gap: 4px; }
.member-info strong { font-weight: 600; }
.member-info span, .member-role small { color: var(--family-muted); font-size: 12px; }
.member-role { display: grid; justify-items: end; gap: 5px; margin-left: auto; }
.notice-bar { display: flex; align-items: center; gap: 16px; margin-top: 56px; padding: 20px 0; border-top: 1px solid var(--family-border); border-bottom: 1px solid var(--family-border); }
.notice-bar .eyebrow { margin: 0; white-space: nowrap; }
.notice-bar strong { flex: 1; font-size: 14px; font-weight: 400; }
.notice-bar :deep(.el-button) { flex: 0 0 auto; }
@media (max-width: 960px) {
  .topbar { padding: 0 24px; }
  .workspace { grid-template-columns: 1fr; }
  .sidebar { display: none; }
  .content { padding: 40px 24px; }
  .metrics, .content-grid { grid-template-columns: 1fr; }
  .metric { border-right: 0; border-bottom: 1px solid var(--family-border); }
  .metric:last-child { border-bottom: 0; }
  .notice-bar { align-items: flex-start; flex-wrap: wrap; }
}
</style>
