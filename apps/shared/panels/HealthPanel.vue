<script setup>
import { computed, onMounted, ref } from "vue";
import { request } from "@app/utils/request";
import {
  familyToday, familyDateOf, formatDoseTimes, parseDoseTimes, todayDoses, todayFollowUps
} from "../health-schedule.mjs";

const emit = defineEmits(["session-ended"]);
const today = ref(familyToday());
const plans = ref([]), dosesFromServer = ref([]), visits = ref([]), session = ref(null);
const loading = ref(false), saving = ref(false), error = ref("");
const medForm = ref(null), visitForm = ref(null);

function emptyMedForm() {
  return { id: "", name: "", dosage: "", note: "", times: "", startDate: today.value, endDate: today.value, visibility: "FAMILY", enabled: true };
}
function emptyVisitForm() {
  return { id: "", title: "", hospital: "", note: "", visitAt: today.value + "T09:00", remindDays: 0, visibility: "FAMILY" };
}
medForm.value = emptyMedForm();
visitForm.value = emptyVisitForm();

const doses = computed(() => todayDoses(plans.value, {
  today: today.value,
  checks: dosesFromServer.value.filter(item => item.taken)
}));
const todayVisits = computed(() => todayFollowUps(visits.value, today.value));
// 今天的复诊已在上方「复诊」段单独列出，这里只排今天之后的，避免同一条出现两遍。
const upcomingVisits = computed(() => visits.value
  .filter(item => familyDateOf(item.visitAt) > today.value)
  .sort((a, b) => String(a.visitAt).localeCompare(String(b.visitAt))));

function report(e) {
  if (!e) return;
  if (e.status === 401) emit("session-ended");
  uni.showToast({ title: e.message || "请求失败", icon: "none" });
}
function ownerLabel(id) {
  return session.value?.userId === Number(id) ? "本人" : "家人";
}
function canManage(item) {
  return !!session.value && String(item.ownerId) === String(session.value.userId);
}
function stamp(value) { return value ? String(value).slice(0, 16).replace("T", " ") : ""; }
function visibilityLabel(visibility) { return visibility === "FAMILY" ? "全家可见" : "仅本人"; }
/** 与账本等面板一致：用回调包成 Promise，避免依赖 uni.showModal 的 Promise 化行为。 */
function confirmModal(options) {
  return new Promise(resolve => {
    uni.showModal({ ...options, success: res => resolve(!!res.confirm), fail: () => resolve(false) });
  });
}

async function load() {
  // 长时间挂着或跨天打开时按家庭时区重算「今天」，否则仍按昨天的日期过滤。
  today.value = familyToday();
  loading.value = true;
  error.value = "";
  const [planResult, doseResult, visitResult, sessionResult] = await Promise.all([
    request("/archives/medications").then(value => ({ value })).catch(e => ({ e })),
    request("/archives/medications/today").then(value => ({ value })).catch(e => ({ e })),
    request("/archives/follow-ups/upcoming?days=30").then(value => ({ value })).catch(e => ({ e })),
    request("/auth/session").then(value => ({ value })).catch(e => ({ e }))
  ]);
  if (planResult.e || doseResult.e || visitResult.e || sessionResult.e) {
    error.value = (planResult.e || doseResult.e || visitResult.e || sessionResult.e).message || "加载失败";
    if (planResult.e) report(planResult.e);
  } else {
    plans.value = planResult.value;
    dosesFromServer.value = doseResult.value;
    visits.value = visitResult.value;
    session.value = sessionResult.value;
  }
  loading.value = false;
}

async function checkIn(dose) {
  if (saving.value || dose.taken) return;
  saving.value = true;
  try {
    await request(`/archives/medications/${dose.planId}/checks`, {
      method: "POST", data: { time: dose.time }
    });
    uni.showToast({ title: "已记录服用", icon: "none" });
    await load();
  } catch (e) { report(e); }
  finally { saving.value = false; }
}

async function saveMedication() {
  if (saving.value) return;
  const form = medForm.value;
  const times = parseDoseTimes(form.times);
  if (!form.name.trim() || !times.length) return uni.showToast({ title: "请填写药名和服用时刻", icon: "none" });
  if (!/^\d{4}-\d{2}-\d{2}$/.test(form.startDate) || !/^\d{4}-\d{2}-\d{2}$/.test(form.endDate))
    return uni.showToast({ title: "开始日和结束日请按 YYYY-MM-DD 填写", icon: "none" });
  if (form.endDate < form.startDate) return uni.showToast({ title: "结束日不能早于开始日", icon: "none" });
  saving.value = true;
  try {
    const payload = {
      name: form.name.trim(), dosage: form.dosage.trim(), note: form.note.trim(), times,
      startDate: form.startDate, endDate: form.endDate, visibility: form.visibility, enabled: form.enabled
    };
    if (form.id) await request(`/archives/medications/${form.id}`, { method: "PUT", data: payload });
    else await request("/archives/medications", { method: "POST", data: payload });
    uni.showToast({ title: "用药计划已保存", icon: "none" });
    medForm.value = emptyMedForm();
    await load();
  } catch (e) { report(e); }
  finally { saving.value = false; }
}

async function editMedication(item) {
  medForm.value = {
    id: item.id, name: item.name, dosage: item.dosage || "", note: item.note || "",
    times: formatDoseTimes(item.times), startDate: item.startDate, endDate: item.endDate,
    visibility: item.visibility, enabled: item.enabled
  };
}

async function removeMedication(item) {
  const confirmed = await confirmModal({
    title: "删除用药计划",
    content: `删除「${item.name}」后当天记录也会清除，确定吗？`,
    confirmText: "删除"
  });
  if (!confirmed) return;
  try {
    await request(`/archives/medications/${item.id}`, { method: "DELETE" });
    if (medForm.value.id === item.id) medForm.value = emptyMedForm();
    await load();
  } catch (e) { report(e); }
}

async function saveFollowUp() {
  if (saving.value) return;
  const form = visitForm.value;
  // 手输时间可能是「2026-11-23 09:00」，提交前统一成后端 LocalDateTime 认的 T 分隔。
  const visitAt = String(form.visitAt || "").trim().replace(" ", "T").slice(0, 16);
  if (!form.title.trim() || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(visitAt))
    return uni.showToast({ title: "请填写复诊标题和正确的时间", icon: "none" });
  saving.value = true;
  try {
    const payload = {
      title: form.title.trim(), hospital: form.hospital.trim(), note: form.note.trim(),
      visitAt, remindDays: Math.min(30, Math.max(0, Number(form.remindDays) || 0)), visibility: form.visibility
    };
    if (form.id) await request(`/archives/follow-ups/${form.id}`, { method: "PUT", data: payload });
    else await request("/archives/follow-ups", { method: "POST", data: payload });
    uni.showToast({ title: "复诊已保存", icon: "none" });
    visitForm.value = emptyVisitForm();
    await load();
  } catch (e) { report(e); }
  finally { saving.value = false; }
}

function editFollowUp(item) {
  visitForm.value = {
    id: item.id, title: item.title, hospital: item.hospital || "", note: item.note || "",
    visitAt: stampFull(item.visitAt), remindDays: item.remindDays, visibility: item.visibility
  };
}
/** 回填就诊时间：后端 LocalDateTime 只认 ISO 的 T 分隔，截到分钟即可，不能换成空格。 */
function stampFull(value) {
  if (!value) return "";
  return String(value).replace(" ", "T").slice(0, 16);
}

async function removeFollowUp(item) {
  const confirmed = await confirmModal({
    title: "删除复诊",
    content: `删除「${item.title}」后无法恢复，确定吗？`,
    confirmText: "删除"
  });
  if (!confirmed) return;
  try {
    await request(`/archives/follow-ups/${item.id}`, { method: "DELETE" });
    if (visitForm.value.id === item.id) visitForm.value = emptyVisitForm();
    await load();
  } catch (e) { report(e); }
}

onMounted(load);
defineExpose({ load, sync: load });
</script>

<template>
  <view class="health">
    <view class="toolbar">
      <view>
        <text class="title">用药与复诊</text>
        <text class="hint">今天的药按家庭时区 {{ today }} 计算；健康内容加密保存</text>
      </view>
      <button size="mini" :disabled="loading" @click="load">刷新</button>
    </view>
    <view v-if="error" class="state"><text>{{ error }}</text><button size="mini" @click="load">重新加载</button></view>
    <view v-else-if="loading" class="state">正在加载健康安排…</view>

    <view v-else class="section">
      <view class="section-head">
        <text class="section-title">今天的药</text>
        <text class="section-note">{{ doses.length }} 次 · 未服用 {{ doses.filter(item => !item.taken).length }} 次</text>
      </view>

      <view v-if="!doses.length" class="state">今天没有需要服用的药。先新增一条用药计划吧。</view>
      <view v-else class="list">
        <view v-for="item in doses" :key="item.planId + item.time" class="card dose">
          <view class="dose-main">
            <text class="row-title">{{ item.name }}</text>
            <text class="meta">{{ item.time }} · {{ item.dosage || '按医嘱服用' }}</text>
          </view>
          <text v-if="item.taken" class="tag tag-open">已服用</text>
          <button v-else size="mini" class="primary" :disabled="saving" @click="checkIn(item)">勾选已服用</button>
        </view>
      </view>

      <view class="form card">
        <view class="form-head">
          <text class="form-title">{{ medForm.id ? '修改用药计划' : '记一种药' }}</text>
          <text v-if="medForm.id" class="link" @click="medForm = emptyMedForm()">取消编辑</text>
        </view>
        <input v-model="medForm.name" class="input" maxlength="80" placeholder="药名（必填）" :disabled="saving" />
        <input v-model="medForm.dosage" class="input" maxlength="80" placeholder="剂量，如 1 片 / 10 ml" :disabled="saving" />
        <input v-model="medForm.times" class="input" placeholder="每天服用时刻，如 08:00，20:00" :disabled="saving" />
        <view class="row">
          <input v-model="medForm.startDate" class="input" placeholder="开始日 YYYY-MM-DD" :disabled="saving" />
          <input v-model="medForm.endDate" class="input" placeholder="结束日 YYYY-MM-DD" :disabled="saving" />
        </view>
        <textarea v-model="medForm.note" class="textarea" maxlength="500" placeholder="备注（选填），如饭后服用" :disabled="saving" />
        <view class="switch-row"><text>家人可见</text><switch :checked="medForm.visibility === 'FAMILY'" :disabled="saving" color="var(--c-primary)" @change="medForm.visibility = $event.detail.value ? 'FAMILY' : 'PRIVATE'" /></view>
        <view class="switch-row"><text>启用计划</text><switch :checked="medForm.enabled" :disabled="saving" color="var(--c-primary)" @change="medForm.enabled = $event.detail.value" /></view>
        <text class="hint">设为仅本人时，其他成员和管理员都看不到这条用药。</text>
        <view class="actions"><button class="primary" :disabled="saving" @click="saveMedication">{{ medForm.id ? '保存修改' : '添加用药' }}</button></view>
      </view>

      <view v-if="plans.length" class="sub-list">
        <text class="sub-title">全部用药</text>
        <view v-for="item in plans" :key="item.id" class="card">
          <view class="row-head">
            <view class="head-main">
              <text class="row-title">{{ item.name }}</text>
              <text class="meta">{{ ownerLabel(item.ownerId) }} · {{ formatDoseTimes(item.times) }} · {{ item.startDate }} 至 {{ item.endDate }}</text>
            </view>
            <text :class="['tag', item.visibility === 'FAMILY' ? 'tag-open' : 'tag-private']">{{ visibilityLabel(item.visibility) }}</text>
          </view>
          <view v-if="canManage(item)" class="actions">
            <button size="mini" @click="editMedication(item)">编辑</button>
            <button size="mini" class="danger" @click="removeMedication(item)">删除</button>
          </view>
        </view>
      </view>

      <view class="section-head second">
        <text class="section-title">复诊</text>
        <text class="section-note">未来 30 天 · 今天 {{ todayVisits.length }} 条</text>
      </view>

      <view v-if="todayVisits.length" class="list">
        <view v-for="item in todayVisits" :key="'today-' + item.id" class="card dose">
          <view class="dose-main">
            <text class="row-title">{{ item.title }}</text>
            <text class="meta">今天 {{ stamp(item.visitAt) }} · {{ item.hospital || '未填写医院' }}</text>
          </view>
          <text class="tag tag-open">今天复诊</text>
        </view>
      </view>

      <view class="form card">
        <view class="form-head">
          <text class="form-title">{{ visitForm.id ? '修改复诊' : '记一条复诊' }}</text>
          <text v-if="visitForm.id" class="link" @click="visitForm = emptyVisitForm()">取消编辑</text>
        </view>
        <input v-model="visitForm.title" class="input" maxlength="120" placeholder="复诊标题（必填）" :disabled="saving" />
        <input v-model="visitForm.hospital" class="input" maxlength="120" placeholder="医院或科室" :disabled="saving" />
        <input v-model="visitForm.visitAt" class="input" placeholder="就诊时间 YYYY-MM-DDTHH:mm" :disabled="saving" />
        <view class="row">
          <input v-model.number="visitForm.remindDays" class="input" type="number" placeholder="提前提醒天数" :disabled="saving" />
        </view>
        <textarea v-model="visitForm.note" class="textarea" maxlength="500" placeholder="备注（选填）" :disabled="saving" />
        <view class="switch-row"><text>家人可见</text><switch :checked="visitForm.visibility === 'FAMILY'" :disabled="saving" color="var(--c-primary)" @change="visitForm.visibility = $event.detail.value ? 'FAMILY' : 'PRIVATE'" /></view>
        <text class="hint">设为仅本人时，其他成员和管理员都看不到这条复诊。</text>
        <view class="actions"><button class="primary" :disabled="saving" @click="saveFollowUp">{{ visitForm.id ? '保存修改' : '添加复诊' }}</button></view>
      </view>

      <view v-if="upcomingVisits.length" class="sub-list">
        <text class="sub-title">接下来的复诊</text>
        <view v-for="item in upcomingVisits" :key="item.id" class="card">
          <view class="row-head">
            <view class="head-main">
              <text class="row-title">{{ item.title }}</text>
              <text class="meta">{{ ownerLabel(item.ownerId) }} · {{ stamp(item.visitAt) }} · {{ item.hospital || '未填写医院' }}</text>
            </view>
            <text :class="['tag', item.visibility === 'FAMILY' ? 'tag-open' : 'tag-private']">{{ visibilityLabel(item.visibility) }}</text>
          </view>
          <view v-if="canManage(item)" class="actions">
            <button size="mini" @click="editFollowUp(item)">编辑</button>
            <button size="mini" class="danger" @click="removeFollowUp(item)">删除</button>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
/* 视觉与账本、任务、纪念日等面板一致：半透明玻璃卡片 + 30px 圆角 + rpx 单位。 */
.health{display:flex;flex-direction:column;gap:24rpx}
.toolbar{display:flex;align-items:flex-start;justify-content:space-between;gap:16rpx}
.title{font-size:32rpx;font-weight:650;color:var(--c-ink)}
.hint{display:block;font-size:22rpx;color:var(--family-muted);margin-top:8rpx;line-height:1.7}
.toolbar button{margin:0}
.section{display:flex;flex-direction:column;gap:20rpx}
.section-head{display:flex;align-items:baseline;justify-content:space-between;gap:16rpx}
/* 复诊段与「今天的药」之间留出分区间距；原来误写成 .section.second，选择器不匹配。 */
.section-head.second{margin-top:24rpx}
.section-title{font-size:30rpx;font-weight:650;color:var(--c-ink)}
.section-note{font-size:22rpx;color:var(--family-muted)}
.list,.sub-list{display:flex;flex-direction:column;gap:16rpx}
.card{background:rgba(255,255,255,.54);border:1px solid rgba(255,255,255,.8);border-radius:30px;box-shadow:0 10px 30px rgba(134,187,255,.22);backdrop-filter:blur(20px);-webkit-backdrop-filter:blur(20px);padding:28rpx}
.dose{display:flex;align-items:center;justify-content:space-between;gap:16rpx}
.dose-main{flex:1;min-width:0}
.row-head{display:flex;align-items:center;gap:16rpx;justify-content:space-between}
.head-main{flex:1;min-width:0}
.row-title{font-size:28rpx;font-weight:600;color:var(--c-ink);overflow-wrap:anywhere}
.meta{display:block;font-size:22rpx;color:var(--family-muted);margin-top:8rpx;line-height:1.6}
.tag{font-size:20rpx;padding:6rpx 18rpx;border-radius:30px;background:rgba(255,255,255,.72);color:var(--c-ink-2);flex-shrink:0}
.tag-open{background:var(--c-sage-soft);color:var(--c-sage)}
.tag-private{background:var(--c-sand-soft);color:var(--c-sand)}
.form{display:flex;flex-direction:column;gap:20rpx;padding:32rpx}
.form-head{display:flex;justify-content:space-between;align-items:baseline}
.form-title{font-size:28rpx;font-weight:650;color:var(--c-ink)}
.link{font-size:24rpx;color:var(--c-primary)}
.input,.textarea{width:100%;box-sizing:border-box;padding:22rpx;border:1px solid rgba(255,255,255,.8);border-radius:30px;background:rgba(255,255,255,.72);font-size:28rpx;color:#142B53}
.input{height:88rpx}
.textarea{height:164rpx}
.row{display:flex;gap:16rpx}
.row .input{flex:1;min-width:0}
.switch-row{display:flex;align-items:center;justify-content:space-between;font-size:26rpx;color:var(--c-ink)}
.actions{display:flex;gap:16rpx}
.actions button,.dose button{margin:0;padding:0 28rpx;height:64rpx;line-height:64rpx;border-radius:30px;font-size:24rpx;background:rgba(255,255,255,.62);color:var(--c-primary);border:1px solid rgba(255,255,255,.85)}
.actions button.primary,.dose button.primary{background:#4F7DC6;color:#F4F7FF;border-color:rgba(255,255,255,.8);box-shadow:0 8px 20px rgba(134,187,255,.45)}
.actions button.danger{background:var(--c-danger-soft);color:var(--c-danger)}
.sub-title{font-size:26rpx;font-weight:650;color:var(--c-ink-2);margin-top:8rpx}
.state{text-align:center;color:var(--family-muted);font-size:26rpx;padding:40rpx 20rpx;line-height:1.7}
.state button{margin-top:16rpx;font-size:24rpx}
</style>
