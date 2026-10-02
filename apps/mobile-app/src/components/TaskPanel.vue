<script setup>
import { computed, onMounted, onUnmounted, ref } from "vue";
import { request } from "../utils/request";
import { canManageTask, filterTasks, localDate, nextTaskStatus, taskStatusLabel } from "../../../shared/tasks.mjs";

const emit = defineEmits(["session-ended"]);
const tasks = ref([]), members = ref([]), session = ref(null);
const loading = ref(false), saving = ref(false), creating = ref(false), updating = ref(new Set());
const errorMessage = ref(""), memberError = ref(""), keyword = ref(""), status = ref("ALL");
const today = ref(localDate());
const newForm = () => ({ title: "", deadline: "", assigneeId: null });
const form = ref(newForm());
const filters = [{ value: "ALL", label: "全部" }, { value: "TODO", label: "待开始" }, { value: "IN_PROGRESS", label: "进行中" }, { value: "COMPLETED", label: "已完成" }, { value: "OVERDUE", label: "已逾期" }];
const filteredTasks = computed(() => filterTasks(tasks.value, { keyword: keyword.value, status: status.value, today: today.value }));
const memberOptions = computed(() => [{ id: null, displayName: "暂不指派（由创建者跟进）" }, ...members.value]);
const selectedMember = computed(() => memberOptions.value.find(item => item.id === form.value.assigneeId)?.displayName || "暂不指派（由创建者跟进）");
let sequence = 0;
function report(error) {
  if (error.status === 401) emit("session-ended");
  uni.showToast({ title: error.message || "请求失败，请重试", icon: "none" });
}
async function load() {
  const current = ++sequence;
  today.value = localDate();
  loading.value = true;
  errorMessage.value = "";
  try {
    const [list, identity] = await Promise.all([request("/tasks"), request("/auth/session")]);
    if (current !== sequence) return;
    tasks.value = list;
    session.value = identity;
  } catch (error) {
    if (current !== sequence) return;
    errorMessage.value = error.message || "任务加载失败，请重试";
    report(error);
  } finally { if (current === sequence) loading.value = false; }
}
async function loadMembers() {
  memberError.value = "";
  try { members.value = await request("/users/members"); }
  catch (error) { memberError.value = "成员加载失败，可重试或暂不指派"; report(error); }
}
function openCreate() {
  if (saving.value) return;
  creating.value = !creating.value;
  if (creating.value) loadMembers();
}
async function createTask() {
  if (saving.value) return;
  const title = form.value.title.trim();
  if (!title) return uni.showToast({ title: "请输入任务标题", icon: "none" });
  saving.value = true;
  try {
    await request("/tasks", { method: "POST", data: { ...form.value, title, deadline: form.value.deadline || null } });
    form.value = newForm();
    creating.value = false;
    uni.showToast({ title: "任务已创建", icon: "none" });
    await load();
  } catch (error) { report(error); }
  finally { saving.value = false; }
}
async function advance(task) {
  const next = nextTaskStatus(task.status);
  if (!next || !canManageTask(task, session.value) || updating.value.has(task.id)) return;
  updating.value.add(task.id);
  try {
    await request("/tasks/" + task.id + "/status", { method: "PATCH", data: { status: next } });
    task.status = next;
    uni.showToast({ title: "任务状态已更新", icon: "none" });
  } catch (error) {
    report(error);
    if (error.status === 409) await load();
  } finally { updating.value.delete(task.id); }
}
onMounted(load);
onUnmounted(() => { sequence++; });
defineExpose({ load });
</script>

<template>
  <view class="task-panel">
    <view class="toolbar"><text class="section-title">家庭任务</text><button size="mini" :disabled="saving" @click="openCreate">{{ creating ? "收起" : "新建任务" }}</button><button size="mini" :disabled="loading" @click="load">刷新</button></view>
    <view v-if="creating" class="task-card form">
      <input v-model="form.title" class="input" maxlength="80" placeholder="任务标题（必填）" :disabled="saving" />
      <picker mode="selector" :range="memberOptions" range-key="displayName" :disabled="saving" @change="form.assigneeId = memberOptions[Number($event.detail.value)].id"><view class="picker">负责人：{{ selectedMember }}</view></picker>
      <view v-if="memberError" class="error"><text>{{ memberError }}</text><button size="mini" @click="loadMembers">重试成员加载</button></view>
      <picker mode="date" :value="form.deadline" :disabled="saving" @change="form.deadline = $event.detail.value"><view class="picker">{{ form.deadline ? '截止：' + form.deadline : '选择截止日期（可选）' }}</view></picker>
      <button v-if="form.deadline" size="mini" :disabled="saving" @click="form.deadline = ''">清除日期</button>
      <button class="primary" :loading="saving" :disabled="saving" @click="createTask">创建任务</button>
    </view>
    <input v-model="keyword" class="input" placeholder="搜索任务或负责人" />
    <view class="filters"><button v-for="item in filters" :key="item.value" size="mini" :class="{ selected: status === item.value }" @click="status = item.value">{{ item.label }}</button></view>
    <view v-if="errorMessage" class="error"><text>{{ errorMessage }}</text><button size="mini" @click="load">重新加载</button></view>
    <view v-else-if="loading" class="empty">加载中…</view>
    <view v-else-if="!filteredTasks.length" class="empty">{{ tasks.length ? "没有符合条件的任务，试试其他筛选" : "还没有任务，点击新建安排家庭事项" }}</view>
    <view v-else class="task-list">
      <view v-for="task in filteredTasks" :key="task.id" class="task-card">
        <view class="task-heading"><text class="task-title">{{ task.title }}</text><text class="badge">{{ taskStatusLabel[task.status] || task.status }}</text></view>
        <text class="meta">负责人：{{ task.assigneeName || '未指派（由创建者跟进）' }}</text>
        <text :class="['meta', { overdue: task.status !== 'COMPLETED' && task.deadline && task.deadline < today }]">截止：{{ task.deadline || '未设置' }}{{ task.status !== 'COMPLETED' && task.deadline && task.deadline < today ? ' · 已逾期' : '' }}</text>
        <button v-if="canManageTask(task, session) && nextTaskStatus(task.status)" class="primary" size="mini" :loading="updating.has(task.id)" :disabled="updating.has(task.id)" @click="advance(task)">{{ task.status === 'TODO' ? '开始处理' : '标记完成' }}</button>
        <text v-else-if="task.status !== 'COMPLETED'" class="meta">由创建者、负责人或管理员处理</text>
      </view>
    </view>
    <text class="meta">展示最近 500 条任务</text>
  </view>
</template>

<style scoped>
.task-panel,.task-list,.form{display:flex;flex-direction:column;gap:24rpx}.toolbar{display:flex;align-items:center;gap:16rpx}.toolbar button{margin:0}.section-title{flex:1;font-size:32rpx;font-weight:600}.task-card{display:flex;flex-direction:column;gap:16rpx;padding:28rpx;background:#fff;border:1px solid var(--family-border);border-radius:12rpx}.input,.picker{padding:22rpx;border:1px solid var(--family-border);border-radius:10rpx;background:#fff;font-size:28rpx}.input{height:88rpx;box-sizing:border-box}.filters{display:flex;flex-wrap:wrap;gap:12rpx}.filters button{margin:0}.selected,.primary{background:var(--family-primary);color:#fff}.task-heading{display:flex;justify-content:space-between;gap:16rpx}.task-title{font-weight:600;overflow-wrap:anywhere}.badge{flex-shrink:0;color:var(--family-primary);font-size:24rpx}.meta{color:var(--family-muted);font-size:24rpx}.overdue,.error{color:#b54735}.empty,.error{padding:36rpx 0;text-align:center}.primary{align-self:flex-start;margin:0}.form .primary{align-self:stretch}
</style>
