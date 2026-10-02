<script setup>
import { ref, onMounted } from "vue";
import { request, taskStatusLabel } from "../../utils/request";

const props = defineProps({ token: String });
const tasks = ref([]);
const members = ref([]);
const loading = ref(false);
const creating = ref(false);
const submitting = ref(false);
const form = ref({ title: "", deadline: "", assigneeId: "" });

async function load() {
  if (!props.token) return;
  loading.value = true;
  try {
    tasks.value = await request("/tasks");
  } catch (error) {
    uni.showToast({ title: error.message || "加载失败", icon: "none" });
  } finally {
    loading.value = false;
  }
}

async function loadMembers() {
  if (members.value.length) return;
  try {
    members.value = await request("/users/members");
  } catch (error) {
    // 成员下拉不可用时静默降级为仅标题创建
  }
}

async function createTask() {
  if (submitting.value) return;
  const title = form.value.title.trim();
  if (!title) return uni.showToast({ title: "请输入任务标题", icon: "none" });
  submitting.value = true;
  try {
    await request("/tasks", {
      method: "POST",
      data: {
        title,
        deadline: form.value.deadline || undefined,
        assigneeId: form.value.assigneeId ? Number(form.value.assigneeId) : undefined
      }
    });
    form.value = { title: "", deadline: "", assigneeId: "" };
    creating.value = false;
    uni.showToast({ title: "已创建", icon: "none" });
    await load();
  } catch (error) {
    uni.showToast({ title: error.message || "创建失败", icon: "none" });
  } finally {
    submitting.value = false;
  }
}

async function advance(task) {
  const next = task.status === "TODO" ? "IN_PROGRESS" : "COMPLETED";
  try {
    await request("/tasks/" + task.id + "/status", { method: "PATCH", data: { status: next } });
    await load();
  } catch (error) {
    uni.showToast({ title: error.message || "操作失败", icon: "none" });
  }
}

onMounted(() => {
  load();
  loadMembers();
});
defineExpose({ load });
</script>

<template>
  <view class="panel">
    <view class="toolbar">
      <text class="section">家庭任务</text>
      <text class="link" @click="creating = !creating">{{ creating ? "收起" : "＋ 新建任务" }}</text>
    </view>

    <view v-if="creating" class="form card">
      <input v-model="form.title" class="input" maxlength="80" placeholder="任务标题（必填）" />
      <picker mode="date" :value="form.deadline" @change="form.deadline = $event.detail.value">
        <view class="picker">{{ form.deadline ? "截止：" + form.deadline : "选择截止日期（可选）" }}</view>
      </picker>
      <picker mode="selector" :range="members" range-key="displayName" @change="form.assigneeId = members[$event.detail.value]?.id ?? ''">
        <view class="picker">{{ form.assigneeId ? "负责人：" + (members.find(m => String(m.id) === String(form.assigneeId))?.displayName || "") : "选择负责人（可选）" }}</view>
      </picker>
      <button class="primary" :loading="submitting" :disabled="submitting" @click="createTask">创建任务</button>
    </view>

    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="!tasks.length" class="empty">暂无任务，点击右上角新建</view>
    <view v-else class="list">
      <view v-for="item in tasks" :key="item.id" class="row card">
        <view class="row-head">
          <text class="row-title">{{ item.title }}</text>
          <text :class="['badge', 'status-' + item.status.toLowerCase()]">{{ taskStatusLabel[item.status] || item.status }}</text>
        </view>
        <view class="row-meta">
          <text v-if="item.assigneeName">负责人：{{ item.assigneeName }}</text>
          <text v-if="item.deadline">｜截止：{{ item.deadline }}</text>
        </view>
        <button v-if="item.status !== 'COMPLETED'" size="mini" class="action" @click="advance(item)">
          {{ item.status === "TODO" ? "开始处理" : "标记完成" }}
        </button>
      </view>
    </view>
  </view>
</template>

<style scoped>
.panel{display:flex;flex-direction:column;gap:24rpx}
.toolbar{display:flex;justify-content:space-between;align-items:center}
.section{font-size:36rpx;font-weight:600}
.link{color:var(--family-primary);font-size:28rpx}
.card{background:#fff;border-radius:16rpx;padding:26rpx;box-shadow:0 2rpx 12rpx rgba(29,42,38,.04)}
.form{display:flex;flex-direction:column;gap:20rpx}
.input{height:84rpx;padding:0 24rpx;background:#fbfcfb;border:1px solid var(--family-border);border-radius:12rpx;box-sizing:border-box}
.picker{height:84rpx;line-height:84rpx;padding:0 24rpx;background:#fbfcfb;border:1px solid var(--family-border);border-radius:12rpx;color:var(--family-text);box-sizing:border-box}
.primary{margin-top:8rpx;background:var(--family-primary);color:#fff;border-radius:12rpx}
.row{display:flex;flex-direction:column;gap:12rpx}
.row-head{display:flex;justify-content:space-between;align-items:center}
.row-title{font-size:31rpx;font-weight:600}
.row-meta{color:var(--family-muted);font-size:24rpx}
.badge{font-size:22rpx;padding:6rpx 16rpx;border-radius:999rpx}
.status-todo{background:#fdf1e3;color:#b26a00}
.status-in_progress{background:#e5f0fb;color:#2563a8}
.status-completed{background:#e7f3ec;color:#2f6b5e}
.action{margin-top:8rpx;align-self:flex-start;background:var(--family-primary);color:#fff}
.empty{padding:80rpx 0;text-align:center;color:var(--family-muted)}
</style>
