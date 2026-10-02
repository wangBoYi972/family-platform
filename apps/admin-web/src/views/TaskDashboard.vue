<script setup>
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { listTasks, createTask, updateTaskStatus } from "../api/task";
import { listMembers } from "../api/member";
import { filterTasks, localDate } from "../../../shared/tasks.mjs";

const emit = defineEmits(["logout", "navigate"]);
const loading = ref(false), tasks = ref([]), members = ref([]), dialogVisible = ref(false);
const form = ref({ title: "", deadline: "", assigneeId: null });
// 截止日按设备本地日期判断，避免北京时间清晨仍使用前一天的 UTC 日期。
const today = ref(localDate());
const keyword = ref(""), statusFilter = ref("ALL"), loadError = ref("");
const filteredTasks = computed(() => filterTasks(tasks.value, { keyword: keyword.value, status: statusFilter.value, today: today.value }));
const pendingTasks = computed(() => tasks.value.filter((item) => item.status !== "COMPLETED"));
const completedTasks = computed(() => tasks.value.filter((item) => item.status === "COMPLETED"));
const overdueCount = computed(() => pendingTasks.value.filter((item) => item.deadline && item.deadline < today.value).length);
const statusText = { TODO: "待开始", IN_PROGRESS: "进行中", COMPLETED: "已完成" };
const statusType = { TODO: "info", IN_PROGRESS: "warning", COMPLETED: "success" };

async function load() {
  if (loading.value) return;
  today.value = localDate();
  loadError.value = "";
  loading.value = true;
  try { [tasks.value, members.value] = await Promise.all([listTasks(), listMembers()]); }
  catch (error) { loadError.value = error.message || "工作台加载失败"; ElMessage.error(loadError.value); }
  finally { loading.value = false; }
}
function resetForm() { form.value = { title: "", deadline: "", assigneeId: null }; }
async function submitTask() {
  if (saving.value) return;
  if (!form.value.title.trim()) return ElMessage.warning("请填写任务标题");
  saving.value = true;
  try { await createTask({ ...form.value, title: form.value.title.trim(), deadline: form.value.deadline || null }); dialogVisible.value = false; resetForm(); ElMessage.success("任务已创建"); await load(); }
  catch (error) { ElMessage.error(error.message || "任务创建失败"); }
  finally { saving.value = false; }
}
const saving = ref(false), updating = ref(new Set());
async function changeStatus(task, status) {
  if (updating.value.has(task.id)) return;
  updating.value.add(task.id);
  try { await updateTaskStatus(task.id, status); task.status = status; ElMessage.success("任务状态已更新"); }
  catch (error) { ElMessage.error(error.message || "状态更新失败"); }
  finally { updating.value.delete(task.id); }
}
function formatDeadline(value) { return !value ? "未设置" : value === today.value ? "今天" : value; }
onMounted(load);
</script>

<template>
  <div class="app-shell">
    <header class="topbar"><div class="brand"><span class="brand-mark">家</span><strong>家庭综合平台</strong></div><div class="topbar-actions"><el-button text @click="emit('navigate', '通知')">通知</el-button><el-avatar :size="32">家</el-avatar><el-button text @click="emit('logout')">退出</el-button></div></header>
    <main class="workspace">
      <aside class="sidebar"><el-menu default-active="任务" class="nav-menu"><el-menu-item index="任务">任务工作台</el-menu-item><el-menu-item index="成员" @click="emit('navigate', '成员')">家庭成员</el-menu-item><el-menu-item index="档案" @click="emit('navigate', '档案')">家庭档案</el-menu-item><el-menu-item index="通知" @click="emit('navigate', '通知')">公告通知</el-menu-item><el-menu-item index="日历" @click="emit('navigate', '日历')">家庭日历</el-menu-item></el-menu><div class="family-context"><span>当前家庭</span><strong>家庭空间</strong><small>{{ members.length }} 位成员</small></div></aside>
      <section class="content" v-loading="loading">
        <div class="page-heading"><div><p class="eyebrow">家庭工作台</p><h1>家庭近期安排</h1><p class="subtitle">任务、成员和家庭安排集中在这里管理。</p></div><el-button type="primary" @click="dialogVisible = true">新建任务</el-button></div>
        <nav class="mobile-nav" aria-label="家庭功能"><el-button v-for="view in ['成员','档案','通知','日历']" :key="view" @click="emit('navigate', view)">{{ view }}</el-button></nav><el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon /><section class="metrics"><div class="metric"><span>待处理任务</span><strong>{{ pendingTasks.length }}</strong><small>需要继续跟进</small></div><div class="metric"><span>已完成任务</span><strong>{{ completedTasks.length }}</strong><small>已完成事项</small></div><div class="metric"><span>已逾期任务</span><strong>{{ overdueCount }}</strong><small>建议优先处理</small></div></section>
        <section class="section-block"><div class="section-heading"><div><p class="eyebrow">任务清单</p><h2>全部家庭任务</h2></div><el-button text @click="load">刷新</el-button></div><div class="filters"><el-input v-model="keyword" clearable placeholder="搜索任务或负责人" aria-label="搜索任务或负责人" /><el-select v-model="statusFilter" aria-label="任务状态"><el-option label="全部" value="ALL" /><el-option label="待开始" value="TODO" /><el-option label="进行中" value="IN_PROGRESS" /><el-option label="已完成" value="COMPLETED" /><el-option label="已逾期" value="OVERDUE" /></el-select></div><p class="list-note">展示最近 500 条任务，可按状态和负责人查找。</p><el-empty v-if="!loading && !loadError && !filteredTasks.length" :description="tasks.length ? '没有符合条件的任务' : '还没有任务，请先创建家庭安排'" /><el-table v-else :data="filteredTasks" class="task-table"><el-table-column prop="title" label="事项" min-width="260" /><el-table-column label="负责人" width="130"><template #default="scope">{{ scope.row.assigneeName || "未指派" }}</template></el-table-column><el-table-column label="截止时间" width="130"><template #default="scope">{{ formatDeadline(scope.row.deadline) }}</template></el-table-column><el-table-column label="状态" width="130"><template #default="scope"><el-tag :type="statusType[scope.row.status]" effect="plain">{{ statusText[scope.row.status] }}</el-tag></template></el-table-column><el-table-column label="操作" width="180"><template #default="scope"><el-button v-if="scope.row.status === 'TODO'" :disabled="updating.has(scope.row.id)" text @click="changeStatus(scope.row, 'IN_PROGRESS')">开始</el-button><el-button v-if="scope.row.status === 'IN_PROGRESS'" :disabled="updating.has(scope.row.id)" text type="success" @click="changeStatus(scope.row, 'COMPLETED')">完成</el-button></template></el-table-column></el-table></section>
      </section>
    </main>
    <el-dialog v-model="dialogVisible" title="新建家庭任务" width="min(440px, 94vw)" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving" @closed="resetForm"><el-form label-position="top" :disabled="saving"><el-form-item label="任务标题" required><el-input v-model="form.title" maxlength="80" placeholder="例如：整理本周家庭相册" /></el-form-item><el-form-item label="负责人"><el-select v-model="form.assigneeId" clearable placeholder="暂不指派，由创建者跟进"><el-option v-for="member in members" :key="member.id" :label="member.displayName" :value="member.id" /></el-select></el-form-item><el-form-item label="截止日期"><el-date-picker v-model="form.deadline" type="date" value-format="YYYY-MM-DD" /></el-form-item></el-form><template #footer><el-button :disabled="saving" @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" :disabled="saving" @click="submitTask">创建任务</el-button></template></el-dialog>
  </div>
</template>

<style scoped>
.filters{display:flex;gap:12px;margin-bottom:12px}.filters .el-select{width:150px;flex-shrink:0}.list-note{font-size:12px;color:var(--family-muted)}.mobile-nav{display:none}@media(max-width:960px){.mobile-nav{display:flex;flex-wrap:wrap;gap:8px;margin-top:24px}.mobile-nav .el-button{margin:0}.filters{flex-direction:column}.filters .el-select{width:100%}}
.app-shell{min-height:100vh}.topbar{display:flex;align-items:center;justify-content:space-between;height:64px;padding:0 40px;border-bottom:1px solid var(--family-border)}.brand{display:flex;align-items:center;gap:10px;font-size:15px}.brand-mark{display:grid;width:28px;height:28px;place-items:center;color:#fff;background:var(--family-primary);border-radius:6px;font-size:14px}.topbar-actions{display:flex;align-items:center;gap:10px}.workspace{display:grid;grid-template-columns:216px minmax(0,1fr);max-width:1440px;min-height:calc(100vh - 64px);margin:0 auto}.sidebar{display:flex;flex-direction:column;justify-content:space-between;padding:32px 20px 24px;border-right:1px solid var(--family-border)}.nav-menu{border-right:0}.nav-menu :deep(.el-menu-item){height:42px;margin-bottom:4px;border-radius:6px;color:var(--family-muted)}.nav-menu :deep(.el-menu-item:hover),.nav-menu :deep(.el-menu-item.is-active){color:var(--family-primary);background:var(--family-primary-soft)}.family-context{display:grid;gap:4px;padding:16px;color:var(--family-muted);background:var(--family-surface);border-radius:6px;font-size:13px}.family-context strong{color:var(--family-text);font-size:15px}.content{padding:56px 64px 64px}.page-heading,.section-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:24px}.eyebrow{margin:0 0 8px;color:var(--family-muted);font-size:12px;letter-spacing:.08em;text-transform:uppercase}.page-heading h1,.section-heading h2{margin:0;font-weight:600;letter-spacing:-.02em}.page-heading h1{font-size:30px}.section-heading h2{font-size:20px}.subtitle{margin:12px 0 0;color:var(--family-muted)}.metrics{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));margin:48px 0;border-top:1px solid var(--family-border);border-bottom:1px solid var(--family-border)}.metric{display:grid;gap:8px;padding:24px 28px;border-right:1px solid var(--family-border)}.metric:last-child{border-right:0}.metric span,.metric small{color:var(--family-muted);font-size:13px}.metric strong{font-size:30px;font-weight:600;line-height:1}.section-heading{margin-bottom:20px}.task-table{border-top:1px solid var(--family-border)}.task-table :deep(th.el-table__cell){color:var(--family-muted);font-weight:400;background:transparent}.task-table :deep(.el-table__inner-wrapper::before){display:none}@media(max-width:960px){.topbar{padding:0 24px}.workspace{grid-template-columns:1fr}.sidebar{display:none}.content{padding:40px 24px}.metrics{grid-template-columns:1fr}.metric{border-right:0;border-bottom:1px solid var(--family-border)}.metric:last-child{border-bottom:0}}
</style>
