<script setup>
import { ref, onMounted, onUnmounted } from "vue";
import { ElMessage } from "element-plus";
import { getSession, logout } from "./api/auth";
import { clearSession } from "./api/request";
import AccountView from "./views/AccountView.vue";
import LoginView from "./views/LoginView.vue";
import TaskDashboard from "./views/TaskDashboard.vue";
import ContentView from "./views/ContentView.vue";
import AiSettingsView from "./views/AiSettingsView.vue";
import BrandMark from "./components/BrandMark.vue";

const authenticated = ref(Boolean(localStorage.getItem("family_access_token")));
const activeView = ref("任务");
const contentKind = ref("notices");
const contentKinds = { 成员: "members", 档案: "archives", 通知: "notices", 日历: "calendar" };
const menus = [
  { id: "任务", label: "任务工作台" },
  { id: "成员", label: "家庭成员" },
  { id: "档案", label: "家庭档案" },
  { id: "通知", label: "公告通知" },
  { id: "日历", label: "家庭日历" },
  { id: "AI助手", label: "AI 家庭助手" }
];
const now = new Date();
const dateText = `${now.getMonth() + 1} 月 ${now.getDate()} 日`;
const weekText = "星期" + "日一二三四五六"[now.getDay()];
function navigate(view) {
  activeView.value = view === "概览" || view === "任务" ? "任务" : view;
  contentKind.value = contentKinds[view] || "notices";
}

function handleAuthenticated() {
  activeView.value = "任务";
  authenticated.value = true;
}

function ended() {
  authenticated.value = false;
}
async function handleLogout() {
  try { await logout(); }
  catch (error) { ElMessage.error("退出未完成：" + error.message); }
}
onMounted(async () => {
  window.addEventListener("family-session-ended", ended);
  if (!authenticated.value) return;
  try { const session = await getSession(); if (session.role !== "ADMIN" || session.client !== "admin") clearSession(); }
  catch { clearSession(); }
});
onUnmounted(() => window.removeEventListener("family-session-ended", ended));
</script>

<template>
  <LoginView v-if="!authenticated" @authenticated="handleAuthenticated" />
  <div v-else class="admin">
    <header class="topbar">
      <div class="brand"><BrandMark :size="24" class="brand-mark" /><strong>把时间，留给家人</strong></div>
      <div class="topbar-actions">
        <span class="date-pill">{{ dateText }} · {{ weekText }}</span>
        <el-avatar :size="40" class="avatar">家</el-avatar>
        <el-button text @click="handleLogout">退出</el-button>
      </div>
    </header>
    <div class="workspace">
      <aside class="sidebar">
        <nav class="nav" aria-label="管理功能">
          <button v-for="item in menus" :key="item.id" type="button" :class="{ on: activeView === item.id }" @click="navigate(item.id)">{{ item.label }}</button>
        </nav>
        <div class="family-context">
          <span>当前身份</span>
          <strong>家庭管理员</strong>
          <small>账号、档案与助手都在这本里</small>
        </div>
      </aside>
      <main class="pane">
        <Transition name="page" mode="out-in">
          <TaskDashboard v-if="activeView === '任务'" key="task" />
          <AccountView v-else-if="activeView === '成员'" key="account" @navigate="navigate" />
          <AiSettingsView v-else-if="activeView === 'AI助手'" key="ai" />
          <ContentView v-else :key="'content-' + contentKind" :kind="contentKind" @navigate="navigate" />
        </Transition>
      </main>
    </div>
  </div>
</template>

<style>
.admin { min-height: 100vh; }
.topbar {
  position: sticky; top: 16px; z-index: 20;
  display: flex; align-items: center; justify-content: space-between;
  height: 72px; margin: 16px 16px 0; padding: 0 28px;
  background: var(--liquid-surface);
  border: var(--liquid-outline);
  border-radius: var(--liquid-radius);
  backdrop-filter: blur(var(--liquid-blur));
  -webkit-backdrop-filter: blur(var(--liquid-blur));
  box-shadow: 0 10px 30px rgba(134, 187, 255, .28);
}
.brand { display: flex; align-items: center; gap: 14px; font-size: 20px; font-weight: 600; color: #142B53; font-family: var(--font-display); letter-spacing: 0.04em; }
.brand-mark { color: var(--c-primary-deep); }
.topbar-actions { display: flex; align-items: center; gap: 20px; }
.date-pill {
  padding: 0; border: 0; font-size: 14px; color: #4F7DC6;
  background: transparent; letter-spacing: 0.06em;
}
.avatar { background: #4F7DC6; color: #F4F7FF; font-weight: 600; border-radius: 30px; }
.workspace { display: grid; grid-template-columns: 260px minmax(0, 1fr); gap: 16px; padding: 16px; min-height: calc(100vh - 104px); }
.sidebar { display: flex; flex-direction: column; justify-content: space-between; padding: 28px 18px 24px; background: var(--liquid-surface); border: var(--liquid-outline); border-radius: var(--liquid-radius); backdrop-filter: blur(var(--liquid-blur)); -webkit-backdrop-filter: blur(var(--liquid-blur)); box-shadow: 0 10px 30px rgba(134, 187, 255, .28); }
.nav { display: flex; flex-direction: column; gap: 6px; }
.nav button {
  appearance: none; height: 52px; padding: 0 16px; text-align: left;
  border: 0; border-left: 0; border-radius: 16px;
  background: transparent; color: #4F7DC6; font: inherit; font-size: 15px; letter-spacing: 0.02em; cursor: pointer;
}
.nav button:hover { color: #142B53; background: rgba(255, 255, 255, .45); }
.nav button.on { color: #142B53; font-weight: 600; background: rgba(255, 255, 255, .72); border-left-color: transparent; }
.family-context {
  display: grid; gap: 6px; padding: 16px; border-radius: 20px; font-size: 12px; color: #4F7DC6;
  background: rgba(255, 255, 255, .4); border: var(--liquid-outline);
}
.family-context strong { color: #142B53; font-size: 16px; font-weight: 600; font-family: var(--font-display); letter-spacing: 0.02em; }
.pane { min-width: 0; padding: 12px 8px 32px; background: transparent; color: #142B53; }
.page-enter-active { transition: opacity 220ms var(--e-out); }
.page-leave-active { transition: opacity 140ms var(--e-out); }
.page-enter-from, .page-leave-to { opacity: 0; }
@media (max-width: 960px) {
  .topbar { padding: 0 16px; }
  .workspace { grid-template-columns: 1fr; }
  .sidebar { flex-direction: row; align-items: center; gap: 12px; padding: 12px 16px; overflow-x: auto; }
  .nav { flex-direction: row; }
  .nav button { white-space: nowrap; border-left: 0; border-bottom: 2px solid transparent; height: 36px; }
  .nav button.on { border-bottom-color: transparent; background: rgba(255, 255, 255, .72); }
  .family-context { display: none; }
  .pane { padding: 24px 16px 48px; }
}
@media (prefers-reduced-motion: reduce) {
  .page-enter-active, .page-leave-active { transition-duration: 1ms; }
}
</style>
