<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { listTasks, taskStats, createTask, updateTask, updateTaskStatus } from "../api/task";
import { listMembers } from "../api/member";
import { localDate, prevTaskStatus } from "../../../shared/tasks.mjs";
import CountTo from "../components/CountTo.vue";
import BrandMark from "../components/BrandMark.vue";

const emit = defineEmits(["logout", "navigate"]);
const loading = ref(false), tasks = ref([]), members = ref([]), dialogVisible = ref(false);
const form = ref({ title: "", deadline: "", assigneeId: null });
const editingId = ref(null);
const isEditing = computed(() => editingId.value !== null);
// 截止日按设备本地日期判断，避免北京时间清晨仍使用前一天的 UTC 日期。
const today = ref(localDate());
const keyword = ref(""), statusFilter = ref("ALL"), loadError = ref("");
// 筛选和分页都交给了后端：这里只有「当前页」，不再是全量任务。
const PAGE_SIZE = 12;
const page = ref(1), hasNext = ref(false), stats = ref({ total: 0, pending: 0, completed: 0, overdue: 0 });
const statusText = { TODO: "待开始", IN_PROGRESS: "进行中", COMPLETED: "已完成" };
const statusType = { TODO: "info", IN_PROGRESS: "warning", COMPLETED: "success" };

const now = new Date();
const dateText = `${now.getMonth() + 1} 月 ${now.getDate()} 日`;
const weekText = "星期" + "日一二三四五六"[now.getDay()];

/** 列表请求序号：快速改筛选时只认最后一次的响应，避免慢请求把新结果盖回去。 */
let listTicket = 0;
async function loadTasks() {
  const mine = ++listTicket;
  loadError.value = "";
  loading.value = true;
  try {
    const rows = await listTasks({
      page: page.value,
      size: PAGE_SIZE,
      keyword: keyword.value.trim(),
      status: statusFilter.value === "ALL" ? "" : statusFilter.value
    });
    if (mine !== listTicket) return;
    tasks.value = rows;
    hasNext.value = rows.length === PAGE_SIZE;
  } catch (error) {
    if (mine !== listTicket) return;
    loadError.value = error.message || "工作台加载失败";
    ElMessage.error(loadError.value);
  } finally {
    if (mine === listTicket) loading.value = false;
  }
}

/** 指标是家庭全局口径，单独取；失败不打断列表，保留上一次的数字。 */
async function loadStats() {
  try { stats.value = await taskStats(); }
  catch (error) { if (!loadError.value) ElMessage.error(error.message || "指标加载失败"); }
}

async function load() {
  today.value = localDate();
  await Promise.all([loadTasks(), loadStats()]);
}

/** 关键字输入防抖：每敲一个字都打一次库没必要。 */
let debounce = null;
watch(keyword, () => {
  clearTimeout(debounce);
  debounce = setTimeout(() => { page.value = 1; loadTasks(); }, 300);
});
watch(statusFilter, () => { page.value = 1; loadTasks(); });
onBeforeUnmount(() => clearTimeout(debounce));

function goPage(next) {
  if (next < 1 || (next > page.value && !hasNext.value)) return;
  page.value = next;
  loadTasks();
}
function resetForm() { form.value = { title: "", deadline: "", assigneeId: null }; }
function openCreate() { editingId.value = null; resetForm(); dialogVisible.value = true; }
function closeDialog() { dialogVisible.value = false; resetForm(); editingId.value = null; }
function openEdit(task) {
  editingId.value = task.id;
  form.value = { title: task.title || "", deadline: task.deadline || "", assigneeId: task.assigneeId ?? null };
  dialogVisible.value = true;
}
async function submitTask() {
  if (saving.value) return;
  if (!form.value.title.trim()) return ElMessage.warning("请填写任务标题");
  const id = editingId.value;
  saving.value = true;
  try {
    const payload = { ...form.value, title: form.value.title.trim(), deadline: form.value.deadline || null };
    if (id) await updateTask(id, payload); else await createTask(payload);
    dialogVisible.value = false; resetForm();
    ElMessage.success(id ? "任务已更新" : "任务已创建");
    // 新建的排在最新一页之外，回到第一页才看得见
    if (!id) page.value = 1;
    await load();
  }
  catch (error) { ElMessage.error(error.message || (id ? "任务更新失败" : "任务创建失败")); }
  finally { saving.value = false; }
}
const saving = ref(false), updating = ref(new Set());
async function changeStatus(task, status) {
  if (updating.value.has(task.id)) return;
  updating.value.add(task.id);
  try {
    await updateTaskStatus(task.id, status);
    task.status = status;
    ElMessage.success("任务状态已更新");
    // 状态变了，「待处理 / 已完成 / 已逾期」的指标跟着变
    loadStats();
    // 当前筛选是「待开始」这类时，这条任务应该立刻从列表里消失
    if (statusFilter.value !== "ALL") loadTasks();
  }
  catch (error) { ElMessage.error(error.message || "状态更新失败"); }
  finally { updating.value.delete(task.id); }
}
function formatDeadline(value) { return !value ? "未设置" : value === today.value ? "今天" : value; }
async function loadMembers() {
  try { members.value = await listMembers(); }
  catch { /* 成员名单只影响指派人下拉，拿不到也不该让整页报错 */ }
}
onMounted(() => { load(); loadMembers(); });
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="brand"><BrandMark :size="24" class="brand-mark" /><strong>把时间，留给家人</strong></div>
      <div class="topbar-actions">
        <span class="date-pill">{{ dateText }} · {{ weekText }}</span>
        <el-button text @click="emit('navigate', '通知')">通知</el-button>
        <el-avatar :size="32" class="avatar">家</el-avatar>
        <el-button text @click="emit('logout')">退出</el-button>
      </div>
    </header>

    <main class="workspace">
      <aside class="sidebar">
        <el-menu default-active="任务" class="nav-menu">
          <el-menu-item index="任务">任务工作台</el-menu-item>
          <el-menu-item index="成员" @click="emit('navigate', '成员')">家庭成员</el-menu-item>
          <el-menu-item index="档案" @click="emit('navigate', '档案')">家庭档案</el-menu-item>
          <el-menu-item index="通知" @click="emit('navigate', '通知')">公告通知</el-menu-item>
          <el-menu-item index="日历" @click="emit('navigate', '日历')">家庭日历</el-menu-item>
        </el-menu>
        <div class="family-context mf-rise" style="--i: 6">
          <span>当前家庭</span>
          <strong>家庭空间</strong>
          <small>{{ members.length }} 位成员</small>
        </div>
      </aside>

      <section class="content" v-loading="loading">
        <div class="page-heading">
          <div>
            <p class="eyebrow">家庭工作台</p>
            <h1>家庭近期安排</h1>
            <div class="mf-rule"></div>
            <p class="subtitle">任务、成员和家庭安排集中在这里管理。</p>
          </div>
          <el-button type="primary" class="create-button" @click="openCreate">＋ 新建任务</el-button>
        </div>

        <nav class="mobile-nav" aria-label="家庭功能"><el-button v-for="view in ['成员','档案','通知','日历']" :key="view" @click="emit('navigate', view)">{{ view }}</el-button></nav>

        <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />

        <!-- Bento 指标 -->
        <section class="metrics">
          <div class="metric metric--clay mf-card mf-card--flat mf-lift mf-rise" style="--i: 0">
            <span class="metric-icon">◷</span>
            <CountTo class="metric-value" :value="stats.pending" />
            <span class="metric-label">待处理任务</span>
            <small>需要继续跟进</small>
          </div>
          <div class="metric metric--sage mf-card mf-card--flat mf-lift mf-rise" style="--i: 1">
            <span class="metric-icon">✓</span>
            <CountTo class="metric-value" :value="stats.completed" />
            <span class="metric-label">已完成任务</span>
            <small>已完成事项</small>
          </div>
          <div class="metric metric--danger mf-card mf-card--flat mf-lift mf-rise" style="--i: 2">
            <span class="metric-icon">!</span>
            <CountTo class="metric-value" :value="stats.overdue" />
            <span class="metric-label">已逾期任务</span>
            <small>建议优先处理</small>
          </div>
        </section>

        <section class="section-block">
          <div class="section-heading">
            <div><p class="eyebrow">任务清单</p><h2>全部家庭任务</h2></div>
            <el-button text @click="load">刷新</el-button>
          </div>
          <div class="filters">
            <el-input v-model="keyword" clearable placeholder="搜索任务或负责人" aria-label="搜索任务或负责人" />
            <el-select v-model="statusFilter" aria-label="任务状态"><el-option label="全部" value="ALL" /><el-option label="待开始" value="TODO" /><el-option label="进行中" value="IN_PROGRESS" /><el-option label="已完成" value="COMPLETED" /><el-option label="已逾期" value="OVERDUE" /></el-select>
          </div>
          <p class="list-note">按状态与负责人在服务端筛选，共 {{ stats.total }} 条任务，每页 {{ PAGE_SIZE }} 条。</p>
          <el-empty v-if="!loading && !loadError && !tasks.length" :description="stats.total ? '没有符合条件的任务' : '还没有任务，请先创建家庭安排'" />
          <el-table v-else :data="tasks" class="task-table">
            <el-table-column prop="title" label="事项" min-width="260" />
            <el-table-column label="负责人" width="130"><template #default="scope">{{ scope.row.assigneeName || "未指派" }}</template></el-table-column>
            <el-table-column label="截止时间" width="130"><template #default="scope">{{ formatDeadline(scope.row.deadline) }}</template></el-table-column>
            <el-table-column label="状态" width="130"><template #default="scope"><el-tag :type="statusType[scope.row.status]" effect="plain">{{ statusText[scope.row.status] }}</el-tag></template></el-table-column>
            <el-table-column label="操作" width="240"><template #default="scope"><el-button v-if="scope.row.status === 'TODO'" :disabled="updating.has(scope.row.id)" text @click="changeStatus(scope.row, 'IN_PROGRESS')">开始</el-button><el-button v-if="scope.row.status === 'IN_PROGRESS'" :disabled="updating.has(scope.row.id)" text type="success" @click="changeStatus(scope.row, 'COMPLETED')">完成</el-button><el-button v-if="scope.row.status !== 'TODO'" :disabled="updating.has(scope.row.id)" text @click="changeStatus(scope.row, prevTaskStatus(scope.row.status))">{{ scope.row.status === 'COMPLETED' ? '重开' : '撤销' }}</el-button><el-button text :disabled="saving" @click="openEdit(scope.row)">编辑</el-button></template></el-table-column>
          </el-table>
          <div v-if="!loading && (page > 1 || hasNext)" class="pager">
            <el-button text :disabled="page <= 1" @click="goPage(page - 1)">上一页</el-button>
            <span class="pager-text">第 {{ page }} 页</span>
            <el-button text :disabled="!hasNext" @click="goPage(page + 1)">下一页</el-button>
          </div>
        </section>
      </section>
    </main>

    <el-dialog v-model="dialogVisible" :title="isEditing ? '编辑家庭任务' : '新建家庭任务'" width="min(440px, 94vw)" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving" @closed="closeDialog"><el-form label-position="top" :disabled="saving"><el-form-item label="任务标题" required><el-input v-model="form.title" maxlength="80" placeholder="例如：整理本周家庭相册" /></el-form-item><el-form-item label="负责人"><el-select v-model="form.assigneeId" clearable placeholder="暂不指派，由创建者跟进"><el-option v-for="member in members" :key="member.id" :label="member.displayName" :value="member.id" /></el-select></el-form-item><el-form-item label="截止日期"><el-date-picker v-model="form.deadline" type="date" value-format="YYYY-MM-DD" /></el-form-item></el-form><template #footer><el-button :disabled="saving" @click="closeDialog">取消</el-button><el-button type="primary" :loading="saving" :disabled="saving" @click="submitTask">{{ isEditing ? '保存修改' : '创建任务' }}</el-button></template></el-dialog>
  </div>
</template>

<style scoped>
.filters{display:flex;gap:12px;margin-bottom:12px}
.filters .el-select{width:150px;flex-shrink:0}
.list-note{font-size:12px;color:var(--c-muted)}
.pager{display:flex;align-items:center;justify-content:center;gap:10px;margin-top:14px}
.pager-text{font-size:12px;color:var(--c-muted)}
.mobile-nav{display:none}

/* ——— 外壳 ——— */
.app-shell{min-height:100vh}
.topbar{
  position:sticky;top:0;z-index:20;
  display:flex;align-items:center;justify-content:space-between;height:66px;padding:0 36px;
  background:var(--c-glass);
  -webkit-backdrop-filter:blur(22px) saturate(1.7);backdrop-filter:blur(22px) saturate(1.7);
  border-bottom:1px solid var(--c-glass-line);
}
.brand{display:flex;align-items:center;gap:11px;font-size:15px;color:var(--c-ink)}
.brand-mark{color:var(--c-primary-deep)}
.topbar-actions{display:flex;align-items:center;gap:12px}
.date-pill{
  padding:7px 15px;border-radius:var(--r-pill);font-size:12px;color:var(--c-ink-2);
  background:rgba(255,255,255,.6);border:1px solid var(--c-line);
}
.avatar{background:linear-gradient(135deg,#cd8465,var(--c-primary-deep));color:#fff;font-weight:600}

.workspace{display:grid;grid-template-columns:224px minmax(0,1fr);max-width:1440px;min-height:calc(100vh - 66px);margin:0 auto}

/* ——— 侧边栏 ——— */
.sidebar{display:flex;flex-direction:column;justify-content:space-between;padding:30px 18px 24px}
.nav-menu{border-right:0;background:transparent}
.nav-menu :deep(.el-menu-item){
  height:44px;margin-bottom:6px;border-radius:var(--r-md);color:var(--c-muted);
  transition:color var(--t) var(--e-out),background var(--t) var(--e-out),transform var(--t-fast) var(--e-spring);
}
.nav-menu :deep(.el-menu-item:hover){color:var(--c-primary-deep);background:rgba(196,121,90,.09);transform:translateX(3px)}
.nav-menu :deep(.el-menu-item.is-active){
  color:#fff;font-weight:600;
  background:linear-gradient(135deg,#cd8465,var(--c-primary));
  box-shadow:0 8px 20px -8px rgba(166,96,63,.85);
}
.family-context{
  display:grid;gap:5px;padding:18px;border-radius:var(--r-lg);font-size:13px;color:var(--c-muted);
  background:linear-gradient(150deg,rgba(196,121,90,.14),rgba(255,255,255,.72));
  border:1px solid var(--c-glass-line);box-shadow:var(--sh-1);
}
.family-context strong{color:var(--c-ink);font-size:15px}

/* ——— 内容 ——— */
.content{padding:44px 56px 64px;position:relative;z-index:1}
.page-heading,.section-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:24px}
.eyebrow{margin:0 0 8px;color:var(--c-muted);font-size:11px;letter-spacing:.24em;text-transform:uppercase}
.page-heading h1,.section-heading h2{margin:0;font-weight:700;letter-spacing:-.02em}
.page-heading h1{font-size:31px}
.section-heading h2{font-size:20px}
.subtitle{margin:14px 0 0;color:var(--c-muted);font-size:13px}
.create-button{border-radius:var(--r-pill);padding:0 22px;height:42px;font-weight:600}

/* ——— Bento 指标 ——— */
.metrics{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px;margin:34px 0 40px}
.metric{position:relative;display:flex;flex-direction:column;gap:4px;padding:22px 24px;border-radius:var(--r-lg);overflow:hidden}
.metric-icon{
  position:absolute;right:16px;top:14px;width:36px;height:36px;display:grid;place-items:center;
  border-radius:12px;font-size:16px;font-weight:700;
}
.metric-value{font-size:34px;font-weight:720;line-height:1.15;letter-spacing:-1px}
.metric-label{font-size:13px;font-weight:600;color:var(--c-ink)}
.metric small{font-size:12px;color:var(--c-muted)}
.metric--clay{background:linear-gradient(150deg,rgba(196,121,90,.16),rgba(255,255,255,.9))}
.metric--clay .metric-icon{background:rgba(196,121,90,.16);color:var(--c-primary-deep)}
.metric--clay .metric-value{color:var(--c-primary-deep)}
.metric--sage{background:linear-gradient(150deg,rgba(140,163,148,.2),rgba(255,255,255,.9))}
.metric--sage .metric-icon{background:rgba(140,163,148,.22);color:#5f7d68}
.metric--sage .metric-value{color:#5f7d68}
.metric--danger{background:linear-gradient(150deg,rgba(185,106,91,.18),rgba(255,255,255,.9))}
.metric--danger .metric-icon{background:rgba(185,106,91,.18);color:var(--c-danger)}
.metric--danger .metric-value{color:var(--c-danger)}

/* ——— 表格 ——— */
.task-table :deep(.el-table__body tbody tr){animation:mf-rise 420ms var(--e-out) both}
.task-table :deep(.el-table__body tbody tr:nth-child(1)){animation-delay:20ms}
.task-table :deep(.el-table__body tbody tr:nth-child(2)){animation-delay:50ms}
.task-table :deep(.el-table__body tbody tr:nth-child(3)){animation-delay:80ms}
.task-table :deep(.el-table__body tbody tr:nth-child(4)){animation-delay:110ms}
.task-table :deep(.el-table__body tbody tr:nth-child(5)){animation-delay:140ms}
.task-table :deep(.el-table__body tbody tr:nth-child(6)){animation-delay:170ms}
.task-table :deep(.el-table__body tbody tr:nth-child(7)){animation-delay:200ms}
.task-table :deep(.el-table__body tbody tr:nth-child(8)){animation-delay:230ms}
.task-table :deep(.el-table__body tbody tr:nth-child(n+9)){animation-delay:260ms}
.task-table :deep(th.el-table__cell){border-bottom:1px solid var(--c-line)}
.task-table :deep(td.el-table__cell){border-bottom:1px solid var(--c-line-soft)}

@media(max-width:960px){
  .mobile-nav{display:flex;flex-wrap:wrap;gap:8px;margin-top:24px}
  .mobile-nav .el-button{margin:0}
  .filters{flex-direction:column}
  .filters .el-select{width:100%}
  .workspace{grid-template-columns:1fr}
  .sidebar{flex-direction:row;align-items:center;gap:12px;padding:16px 20px;border-bottom:1px solid var(--c-line);overflow-x:auto}
  .family-context{display:none}
  .content{padding:28px 20px 48px}
  .metrics{grid-template-columns:1fr}
}
</style>
