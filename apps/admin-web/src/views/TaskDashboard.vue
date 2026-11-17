<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { listTasks, taskStats, createTask, updateTask, updateTaskStatus } from "../api/task";
import { listMembers } from "../api/member";
import { localDate, prevTaskStatus } from "../../../shared/tasks.mjs";
import CountTo from "../components/CountTo.vue";
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

        <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />

        <!-- Bento 指标 -->
        <section class="metrics">
          <div class="metric metric--clay">
            <span class="metric-icon">◷</span>
            <CountTo class="metric-value" :value="stats.pending" />
            <span class="metric-label">待处理任务</span>
            <small>需要继续跟进</small>
          </div>
          <div class="metric metric--sage">
            <span class="metric-icon">✓</span>
            <CountTo class="metric-value" :value="stats.completed" />
            <span class="metric-label">已完成任务</span>
            <small>已完成事项</small>
          </div>
          <div class="metric metric--danger">
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

    <el-dialog v-model="dialogVisible" :title="isEditing ? '编辑家庭任务' : '新建家庭任务'" width="min(440px, 94vw)" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving" @closed="closeDialog"><el-form label-position="top" :disabled="saving"><el-form-item label="任务标题" required><el-input v-model="form.title" maxlength="80" placeholder="例如：整理本周家庭相册" /></el-form-item><el-form-item label="负责人"><el-select v-model="form.assigneeId" clearable placeholder="暂不指派，由创建者跟进"><el-option v-for="member in members" :key="member.id" :label="member.displayName" :value="member.id" /></el-select></el-form-item><el-form-item label="截止日期"><el-date-picker v-model="form.deadline" type="date" value-format="YYYY-MM-DD" /></el-form-item></el-form><template #footer><el-button :disabled="saving" @click="closeDialog">取消</el-button><el-button type="primary" :loading="saving" :disabled="saving" @click="submitTask">{{ isEditing ? '保存修改' : '创建任务' }}</el-button></template>    </el-dialog>
  </section>
</template>

<style scoped>
.filters{display:flex;gap:12px;margin-bottom:12px}
.filters .el-select{width:150px;flex-shrink:0}
.list-note{font-size:12px;color:var(--c-muted)}
.pager{display:flex;align-items:center;justify-content:center;gap:10px;margin-top:14px}
.pager-text{font-size:12px;color:var(--c-muted)}
.content{position:relative;z-index:1}
.page-heading,.section-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:24px}
.eyebrow{margin:0 0 14px;color:var(--c-primary);font-family:var(--font-display);font-size:15px;letter-spacing:.24em}
.page-heading h1,.section-heading h2{margin:0;font-weight:500;letter-spacing:.03em}
.page-heading h1{font-size:52px}
.section-heading h2{font-size:32px}
.subtitle{margin:18px 0 0;color:var(--c-ink-2);font-size:16px;line-height:1.8}
.create-button{border-radius:30px;padding:0 22px;height:46px;font-weight:500;letter-spacing:.08em}

/* ——— Bento 指标 ——— */
.metrics{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:18px;margin:40px 0 48px;border:0;background:transparent}
.metric{position:relative;display:flex;flex-direction:column;gap:8px;padding:28px 28px 24px;border-radius:30px;overflow:hidden;border:1px solid rgba(255,255,255,.8);background:rgba(255,255,255,.54);backdrop-filter:blur(20px);box-shadow:0 10px 30px rgba(134,187,255,.28)}
.metric:last-child{border-right:0}
.metric-icon{
  position:absolute;right:16px;top:16px;width:28px;height:28px;display:grid;place-items:center;
  border-radius: 50%;font-size:14px;font-weight:700;border:1px solid rgba(255,255,255,.8);background:rgba(255,255,255,.54);
}
.metric-value{font-family:var(--font-display);font-size:64px;font-weight:500;line-height:1}
.metric-label{font-size:16px;font-weight:500;color:var(--c-ink)}
.metric small{font-size:13px;color:var(--c-muted)}
.metric--clay,.metric--sage,.metric--danger{background:rgba(255,255,255,.54)}
.metric--clay .metric-icon,.metric--sage .metric-icon{color:#4F7DC6}
.metric--clay .metric-value,.metric--sage .metric-value{color:#142B53}
.metric--danger .metric-icon,.metric--danger .metric-value{color:#142B53}

/* ——— 表格 ——— */
.task-table :deep(th.el-table__cell){border-bottom:1px solid var(--c-line);font-family:var(--font-display);letter-spacing:.12em}
.task-table :deep(td.el-table__cell){border-bottom:1px solid var(--c-line-soft)}

@media(max-width:960px){
  .filters{flex-direction:column}
  .filters .el-select{width:100%}
  .metrics{grid-template-columns:1fr}
  .metric{border-right:0;border-bottom:1px solid rgba(58,52,46,.12)}
  .metric:last-child{border-bottom:0}
}
</style>
