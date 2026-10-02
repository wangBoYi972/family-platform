<script setup>
import { ref, onMounted, onUnmounted } from "vue";
import { ElMessage } from "element-plus";
import { getSession, logout } from "./api/auth";
import { clearSession } from "./api/request";
import AccountView from "./views/AccountView.vue";
import LoginView from "./views/LoginView.vue";
import TaskDashboard from "./views/TaskDashboard.vue";
import ContentView from "./views/ContentView.vue";

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
  <LoginView v-if="!authenticated" @authenticated="handleAuthenticated" />
  <TaskDashboard v-else-if="activeView === '任务'" @logout="handleLogout" @navigate="navigate" />
  <AccountView v-else-if="activeView === '成员'" @navigate="navigate" />
  <ContentView v-else :key="contentKind" :kind="contentKind" @navigate="navigate" />
</template>
