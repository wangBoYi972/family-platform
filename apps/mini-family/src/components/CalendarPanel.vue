<script setup>
import EntryEditor from "@shared/panels/EntryEditor.vue";
import { computed, onMounted, ref } from "vue";
import { request } from "../utils/request";
import { localDate } from "../../../shared/tasks.mjs";
const emit = defineEmits(["session-ended"]);
const selected = ref(localDate()), month = ref(new Date(new Date().getFullYear(), new Date().getMonth(), 1));
const rows = ref([]), loading = ref(false), error = ref(""), creating = ref(false), saving = ref(false);
const form = ref({ title: "", body: "", yearly: false });
const heading = computed(() => `${month.value.getFullYear()} 年 ${month.value.getMonth() + 1} 月`);
const days = computed(() => {
  const start = (month.value.getDay() + 6) % 7;
  const total = new Date(month.value.getFullYear(), month.value.getMonth() + 1, 0).getDate();
  return [...Array(start).fill(null), ...Array.from({ length: total }, (_, i) => localDate(new Date(month.value.getFullYear(), month.value.getMonth(), i + 1)))];
});
function matches(row, date) { return row.eventDate === date || (row.yearly && row.eventDate?.slice(5) === date.slice(5)); }
const entries = computed(() => rows.value.filter(row => matches(row, selected.value)));
function move(offset) { month.value = new Date(month.value.getFullYear(), month.value.getMonth() + offset, 1); selected.value = localDate(month.value); }
async function load() {
  loading.value = true; error.value = "";
  try { rows.value = await request("/calendar"); }
  catch (e) { error.value = e.message || "日历加载失败"; if (e.status === 401) emit("session-ended"); }
  finally { loading.value = false; }
}
async function save() {
  if (saving.value) return;
  if (!form.value.title.trim()) return uni.showToast({ title: "请填写日程标题", icon: "none" });
  saving.value = true;
  try {
    await request("/calendar", { method: "POST", data: { ...form.value, title: form.value.title.trim(), eventDate: selected.value, visibility: "FAMILY", pinned: false } });
    creating.value = false; form.value = { title: "", body: "", yearly: false }; await load();
  } catch (e) { uni.showToast({ title: e.message || "保存失败", icon: "none" }); if(e.status === 401) emit("session-ended"); }
  finally { saving.value = false; }
}
onMounted(load);
</script>
<template><view class="calendar-panel">
  <view class="calendar-card"><view class="month"><button size="mini" aria-label="上个月" @click="move(-1)">‹</button><text>{{ heading }}</text><button size="mini" aria-label="下个月" @click="move(1)">›</button></view>
  <view class="grid"><text v-for="day in ['一','二','三','四','五','六','日']" :key="day" class="weekday">{{ day }}</text><view v-for="(day,index) in days" :key="day || 'blank'+index"><button v-if="day" :class="['day', { selected: selected === day, today: day === localDate() }]" @click="selected = day">{{ Number(day.slice(-2)) }}<text v-if="rows.some(row => matches(row, day))" class="dot">·</text></button></view></view></view>
  <view class="section-head"><view><text class="section">这一天的安排</text><text class="date-label">{{ selected }}</text></view><button size="mini" class="primary" @click="creating = !creating">{{ creating ? '收起' : '＋ 新日程' }}</button></view>
  <view v-if="creating" class="calendar-card form"><input v-model="form.title" maxlength="120" placeholder="准备一起做什么？" :disabled="saving"/><textarea v-model="form.body" maxlength="4000" placeholder="地点、准备事项，或想对家人说的话" :disabled="saving"/><view class="switch-row"><text>每年纪念这一天</text><switch :checked="form.yearly" :disabled="saving" color="var(--c-primary)" @change="form.yearly = $event.detail.value"/></view><text class="date-label">纪念日会在对应日期展示，暂不发送系统提醒。</text><button class="primary" :loading="saving" :disabled="saving" @click="save">保存家庭日程</button></view>
  <view v-if="error" class="empty"><text>{{ error }}</text><button size="mini" @click="load">重试</button></view><view v-else-if="loading" class="empty">正在加载日程…</view><view v-else-if="!entries.length" class="empty">今天留一点空白，也很好。<text class="date-label">点击“新日程”，安排下一次相聚。</text></view><view v-else v-for="row in entries" :key="row.id" class="calendar-card entry"><view class="entry-head"><text class="section">{{ row.title }}</text><EntryEditor kind="calendar" :entry="row" @changed="load" /></view><text class="date-label">{{ row.yearly ? '每年纪念日' : '家庭日程' }}</text><text class="body">{{ row.body || '一起留出时间，陪伴家人。' }}</text></view>
</view></template>
<style scoped>
.calendar-panel{display:flex;flex-direction:column;gap:20px}.calendar-card{background:rgba(255,252,248,.9);border:1px solid var(--c-line);border-radius:20px;padding:22px;box-shadow:0 2px 12px rgba(58,52,46,.05)}.entry-head{display:flex;align-items:center;justify-content:space-between;gap:12px}.entry-head :deep(.editor){margin-top:0}.month,.section-head,.switch-row{display:flex;align-items:center;justify-content:space-between}.month{font-weight:600}.month button{margin:0;background:var(--c-surface-2);color:var(--c-sky)}.grid{display:grid;grid-template-columns:repeat(7,minmax(0,1fr));gap:5px;margin-top:22px;text-align:center}.weekday{font-size:11px;color:var(--c-muted);padding-bottom:10px}.day{font-size:13px;padding:0;line-height:42px;height:42px;background:transparent;border-radius:12px;position:relative}.day:after{border:0}.selected{background:linear-gradient(135deg,var(--c-primary),var(--c-primary-deep,#a6603f));color:#fff;box-shadow:0 2px 8px rgba(166,96,63,.3)}.today{font-weight:700}.dot{position:absolute;bottom:-11px;left:0;right:0;color:var(--c-primary)}.section{font-size:16px;font-weight:600}.date-label{display:block;font-size:11px;color:var(--c-muted);margin-top:6px;line-height:1.8}.primary{background:linear-gradient(135deg,var(--c-primary),var(--c-primary-deep,#a6603f));color:#fff;border-radius:11px;margin:0;font-size:12px}.form{display:flex;flex-direction:column;gap:14px}.form input,.form textarea{padding:14px;background:var(--c-surface-2);border-radius:10px;font-size:13px;width:100%;box-sizing:border-box}.form input{height:46px}.form textarea{height:100px}.switch-row{font-size:12px}.empty{text-align:center;padding:30px 10px;color:var(--c-muted);font-size:13px}.entry{border-left:3px solid var(--c-primary)}.body{display:block;font-size:13px;line-height:1.9;margin-top:15px;white-space:pre-wrap}
</style>
