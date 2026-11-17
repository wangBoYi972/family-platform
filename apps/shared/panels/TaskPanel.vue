<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
// 共享面板：鉴权请求由宿主应用通过 @app 别名注入（apps/*/src/utils/request.js）。
import { request } from "@app/utils/request";
import { canManageTask, localDate, nextTaskStatus, prevTaskStatus, taskStatusLabel } from "../tasks.mjs";
import { pageQuery } from "../paged-list.mjs";
import { usePagedList } from "../use-paged-list.js";
import ListMore from "../ListMore.vue";

const emit = defineEmits(["session-ended"]);
const keyword = ref(""), status = ref("ALL");
const hasFilter = computed(() => keyword.value.trim() !== "" || status.value !== "ALL");
// 筛选条件进查询串：前端一次只拿得到一页，在本地过滤会把「符合条件但还没加载」的任务彻底藏起来。
function taskPath() {
  const params = [];
  const text = keyword.value.trim();
  if (text) params.push("keyword=" + encodeURIComponent(text));
  if (status.value !== "ALL") params.push("status=" + status.value);
  return params.length ? "/tasks?" + params.join("&") : "/tasks";
}
const {
  items: tasks,
  loading,
  loadingMore,
  exhausted,
  error: listError,
  refresh: refreshTasks,
  reload: reloadTasks,
  loadMore,
  reset: resetTasks
} = usePagedList((page, size) => request(pageQuery(taskPath(), page, size)));

const members = ref([]), session = ref(null);
const saving = ref(false), creating = ref(false), updating = ref(new Set());
const errorMessage = ref(""), memberError = ref("");
const editingId = ref(null);
const isEditing = computed(() => editingId.value !== null);
const today = ref(localDate());
const newForm = () => ({ title: "", deadline: "", assigneeId: null });
const form = ref(newForm());
const filters = [{ value: "ALL", label: "全部" }, { value: "TODO", label: "待开始" }, { value: "IN_PROGRESS", label: "进行中" }, { value: "COMPLETED", label: "已完成" }, { value: "OVERDUE", label: "已逾期" }];
const memberOptions = computed(() => [{ id: null, displayName: "暂不指派（由创建者跟进）" }, ...members.value]);
const selectedMember = computed(() => memberOptions.value.find(item => item.id === form.value.assigneeId)?.displayName || "暂不指派（由创建者跟进）");
function report(error) {
  if (!error) return;
  if (error.status === 401) emit("session-ended");
  uni.showToast({ title: error.message || "请求失败，请重试", icon: "none" });
}
async function refreshList() {
  const ok = await refreshTasks();
  if (!ok) {
    errorMessage.value = listError.value?.message || "任务加载失败，请重试";
    report(listError.value);
  }
  return ok;
}
async function load() {
  today.value = localDate();
  errorMessage.value = "";
  // 身份与列表并行：身份失败只影响操作按钮的显隐，不阻塞列表展示。
  const identity = request("/auth/session").then(value => ({ value })).catch(error => ({ error }));
  const [ok, identityResult] = await Promise.all([refreshList(), identity]);
  if (identityResult.value) session.value = identityResult.value;
  if (identityResult.error) report(identityResult.error);
  if (!ok) return;
}
/** 实时刷新：只看列表，不动筛选条件、不回第一页。 */
async function refreshListInPlace() {
  if (await reloadTasks()) return;
  report(listError.value);
}
let keywordTimer = null;
watch(keyword, () => {
  // 输入停顿 300ms 再查，避免每个字符打一次接口
  clearTimeout(keywordTimer);
  keywordTimer = setTimeout(refreshList, 300);
});
watch(status, () => { clearTimeout(keywordTimer); refreshList(); });
async function loadMoreTasks() {
  if (await loadMore()) return;
  report(listError.value);
}
async function loadMembers() {
  memberError.value = "";
  try { members.value = await request("/users/members"); }
  catch (error) { memberError.value = "成员加载失败，可重试或暂不指派"; report(error); }
}
function closeForm() {
  creating.value = false;
  editingId.value = null;
  form.value = newForm();
}
function openCreate() {
  if (saving.value) return;
  if (creating.value && !isEditing.value) return closeForm();
  editingId.value = null;
  form.value = newForm();
  creating.value = true;
  loadMembers();
}
function openEdit(task) {
  if (saving.value) return;
  editingId.value = task.id;
  form.value = { title: task.title || "", deadline: task.deadline || "", assigneeId: task.assigneeId ?? null };
  creating.value = true;
  loadMembers();
}
async function submitTask() {
  if (saving.value) return;
  const title = form.value.title.trim();
  if (!title) return uni.showToast({ title: "请输入任务标题", icon: "none" });
  const id = editingId.value;
  const payload = { ...form.value, title, deadline: form.value.deadline || null };
  saving.value = true;
  try {
    if (id) await request("/tasks/" + id, { method: "PUT", data: payload });
    else await request("/tasks", { method: "POST", data: payload });
    closeForm();
    uni.showToast({ title: id ? "任务已更新" : "任务已创建", icon: "none" });
    await load();
  } catch (error) {
    report(error);
    if (error.status === 409 || error.status === 404) { closeForm(); await load(); }
  }
  finally { saving.value = false; }
}
async function changeStatus(task, target) {
  if (!target || !canManageTask(task, session.value) || updating.value.has(task.id)) return;
  updating.value.add(task.id);
  try {
    await request("/tasks/" + task.id + "/status", { method: "PATCH", data: { status: target } });
    task.status = target;
    uni.showToast({ title: "任务状态已更新", icon: "none" });
  } catch (error) {
    report(error);
    if (error.status === 409) await load();
  } finally { updating.value.delete(task.id); }
}
function advance(task) { changeStatus(task, nextTaskStatus(task.status)); }
function revert(task) { changeStatus(task, prevTaskStatus(task.status)); }
onMounted(load);
onUnmounted(() => { clearTimeout(keywordTimer); resetTasks(); });
defineExpose({ load, sync: refreshListInPlace });
</script>

<template>
  <view class="task-panel">
    <view class="toolbar"><text class="section-title">家庭任务</text><button size="mini" :disabled="saving" @click="openCreate">{{ creating && !isEditing ? "收起" : "新建任务" }}</button><button size="mini" :disabled="loading" @click="load">刷新</button></view>
    <view v-if="creating" class="task-card form">
      <text class="form-title">{{ isEditing ? "编辑任务" : "新建任务" }}</text>
      <input v-model="form.title" class="input" maxlength="80" placeholder="任务标题（必填）" :disabled="saving" />
      <picker mode="selector" :range="memberOptions" range-key="displayName" :disabled="saving" @change="form.assigneeId = memberOptions[Number($event.detail.value)].id"><view class="picker">负责人：{{ selectedMember }}</view></picker>
      <view v-if="memberError" class="error"><text>{{ memberError }}</text><button size="mini" @click="loadMembers">重试成员加载</button></view>
      <picker mode="date" :value="form.deadline" :disabled="saving" @change="form.deadline = $event.detail.value"><view class="picker">{{ form.deadline ? '截止：' + form.deadline : '选择截止日期（可选）' }}</view></picker>
      <button v-if="form.deadline" size="mini" :disabled="saving" @click="form.deadline = ''">清除日期</button>
      <button class="primary" :loading="saving" :disabled="saving" @click="submitTask">{{ isEditing ? "保存修改" : "创建任务" }}</button>
      <button v-if="isEditing" size="mini" :disabled="saving" @click="closeForm">取消编辑</button>
    </view>
    <input v-model="keyword" class="input" placeholder="搜索任务或负责人（服务端筛选）" />
    <view class="filters"><button v-for="item in filters" :key="item.value" size="mini" :class="{ selected: status === item.value }" @click="status = item.value">{{ item.label }}</button></view>
    <view v-if="errorMessage" class="error"><text>{{ errorMessage }}</text><button size="mini" @click="load">重新加载</button></view>
    <view v-else-if="loading" class="empty">加载中…</view>
    <view v-else-if="!tasks.length" class="empty">{{ hasFilter ? "没有符合条件的任务，试试其他筛选" : "还没有任务，点击新建安排家庭事项" }}</view>
    <view v-else class="task-list">
      <view v-for="task in tasks" :key="task.id" class="task-card">
        <view class="task-heading"><text class="task-title">{{ task.title }}</text><text :class="['badge', String(task.status).toLowerCase()]">{{ taskStatusLabel[task.status] || task.status }}</text></view>
        <text class="meta">负责人：{{ task.assigneeName || '未指派（由创建者跟进）' }}</text>
        <text :class="['meta', { overdue: task.status !== 'COMPLETED' && task.deadline && task.deadline < today }]">截止：{{ task.deadline || '未设置' }}{{ task.status !== 'COMPLETED' && task.deadline && task.deadline < today ? ' · 已逾期' : '' }}</text>
        <view v-if="canManageTask(task, session)" class="task-actions">
          <button v-if="nextTaskStatus(task.status)" class="primary" size="mini" :loading="updating.has(task.id)" :disabled="updating.has(task.id)" @click="advance(task)">{{ task.status === 'TODO' ? '开始处理' : '标记完成' }}</button>
          <button v-if="prevTaskStatus(task.status)" class="ghost" size="mini" :disabled="updating.has(task.id)" @click="revert(task)">{{ task.status === 'COMPLETED' ? '重新处理' : '撤销开始' }}</button>
          <button class="ghost" size="mini" :disabled="saving" @click="openEdit(task)">编辑</button>
        </view>
        <text v-else-if="task.status !== 'COMPLETED'" class="meta">由创建者、负责人或管理员处理</text>
      </view>
    </view>
    <ListMore :count="tasks.length" :loading="loadingMore" :exhausted="exhausted" :has-error="!!listError" @more="loadMoreTasks" />
  </view>
</template>

<style scoped>
.task-panel,.task-list,.form{display:flex;flex-direction:column;gap:24rpx}.toolbar{display:flex;align-items:center;gap:16rpx}.toolbar button{margin:0}.section-title{flex:1;font-size:32rpx;font-weight:600}.task-card{display:flex;flex-direction:column;gap:16rpx;padding:28rpx;background:var(--c-surface);border:1px solid var(--c-line);border-radius:20rpx;box-shadow:0 2rpx 12rpx rgba(58,52,46,.05)}.input,.picker{padding:22rpx;border:1px solid var(--family-border);border-radius:12rpx;background:var(--c-surface-2);font-size:28rpx}.input{height:88rpx;box-sizing:border-box}.filters{display:flex;flex-wrap:wrap;gap:12rpx}.filters button{margin:0;border-radius:8rpx;padding:0 26rpx;line-height:56rpx;height:56rpx;font-size:24rpx;background:#fffdf8;color:var(--family-text);border:1px solid rgba(58,52,46,.18)}.selected,.primary{background:var(--c-primary-deep);color:#fffdf8;border-color:#6e2b20}.task-heading{display:flex;justify-content:space-between;align-items:center;gap:16rpx}.task-title{font-weight:650;overflow-wrap:anywhere}.badge{flex-shrink:0;font-size:22rpx;padding:6rpx 18rpx;border-radius:8rpx;background:var(--c-sand-soft);color:var(--c-sand)}.badge.in_progress{background:var(--c-sky-soft);color:var(--c-sky)}.badge.completed{background:var(--c-sage-soft,#e6efe9);color:var(--c-sage,#8ca394)}.badge.overdue,.badge.todo.overdue{background:rgba(185,106,91,.14);color:var(--c-danger)}.meta{color:var(--family-muted);font-size:24rpx}.overdue,.error{color:var(--c-danger)}.empty,.error{padding:36rpx 0;text-align:center}.primary{align-self:flex-start;margin:0;padding:0 30rpx;height:60rpx;line-height:60rpx;border-radius:8rpx;font-size:24rpx}.form .primary{align-self:stretch;height:80rpx;line-height:80rpx;font-size:28rpx;border-radius:16rpx}
.form-title{font-size:28rpx;font-weight:650}
.task-actions{display:flex;flex-wrap:wrap;align-items:center;gap:12rpx}
.task-actions button{margin:0}
.ghost{align-self:auto;margin:0;padding:0 26rpx;height:60rpx;line-height:60rpx;border-radius:8rpx;font-size:24rpx;background:var(--c-surface-2);color:var(--family-text);border:1px solid var(--c-line)}
</style>
