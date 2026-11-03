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

const authenticated = ref(Boolean(localStorage.getItem("family_access_token")));
const activeView = ref("任务");
const contentKind = ref("notices");
const contentKinds = { 成员: "members", 档案: "archives", 通知: "notices", 日历: "calendar" };
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
  <Transition name="page" mode="out-in">
    <LoginView v-if="!authenticated" key="login" @authenticated="handleAuthenticated" />
    <TaskDashboard v-else-if="activeView === '任务'" key="task" @logout="handleLogout" @navigate="navigate" />
    <AccountView v-else-if="activeView === '成员'" key="account" @navigate="navigate" />
    <AiSettingsView v-else-if="activeView === 'AI助手'" key="ai" @navigate="navigate" />
    <ContentView v-else :key="'content-' + contentKind" :kind="contentKind" @navigate="navigate" />
  </Transition>
</template>

<style>
/* 页面切换：新页从下方淡入，旧页向上淡出 */
.page-enter-active { transition: opacity 320ms var(--e-out), transform 320ms var(--e-out); }
.page-leave-active { transition: opacity 200ms var(--e-out), transform 200ms var(--e-out); }
.page-enter-from { opacity: 0; transform: translate3d(0, 16px, 0); }
.page-leave-to { opacity: 0; transform: translate3d(0, -8px, 0); }
@media (prefers-reduced-motion: reduce) {
  .page-enter-active, .page-leave-active { transition-duration: 1ms; }
}
</style>
