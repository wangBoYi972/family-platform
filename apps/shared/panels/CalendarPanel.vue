<script setup>
import EntryEditor from "./EntryEditor.vue";
import { computed, onMounted, ref } from "vue";
import { request } from "@app/utils/request";
import { localDate } from "../tasks.mjs";
import { dayCaption, monthCells } from "../calendar-marks.mjs";
const emit = defineEmits(["session-ended"]);
const selected = ref(localDate()), month = ref(new Date(new Date().getFullYear(), new Date().getMonth(), 1));
const rows = ref([]), loading = ref(false), error = ref(""), creating = ref(false), saving = ref(false);
const form = ref({ title: "", body: "", yearly: false });
const heading = computed(() => `${month.value.getFullYear()} 年 ${month.value.getMonth() + 1} 月`);
const cells = computed(() => monthCells(month.value.getFullYear(), month.value.getMonth()));
const selectedCaption = computed(() => dayCaption(selected.value));
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
// 不分页的面板：sync 与 load 等价，统一方法名好让宿主一视同仁。
defineExpose({ load, sync: load });
onMounted(load);
</script>
<template><view class="calendar-panel">
  <view class="calendar-card"><view class="month"><button size="mini" aria-label="上个月" @click="move(-1)">‹</button><text>{{ heading }}</text><button size="mini" aria-label="下个月" @click="move(1)">›</button></view>
  <view class="grid"><text v-for="day in ['一','二','三','四','五','六','日']" :key="day" class="weekday">{{ day }}</text><view v-for="(cell,index) in cells" :key="cell?.iso || 'blank'+index"><button v-if="cell" :class="['day', { selected: selected === cell.iso, today: cell.iso === localDate(), rest: cell.rest, festival: cell.festival, term: cell.term, work: cell.work }]" @click="selected = cell.iso"><text class="solar">{{ cell.day }}</text><text class="lunar">{{ cell.lunarShort }}</text><text class="extra">{{ cell.highlight }}</text><text v-if="cell.badge" class="badge">{{ cell.badge }}</text><text v-if="rows.some(row => matches(row, cell.iso))" class="dot">·</text></button></view></view>
  <text class="legend">日期下是农历。节日和节气写在第三行，休是放假，班是补班。</text></view>
  <view class="section-head"><view><text class="section">这一天的安排</text><text class="date-label">{{ selected }} · {{ selectedCaption }}</text></view><button size="mini" class="primary" @click="creating = !creating">{{ creating ? '收起' : '＋ 新日程' }}</button></view>
  <view v-if="creating" class="calendar-card form"><input v-model="form.title" maxlength="120" placeholder="准备一起做什么？" :disabled="saving"/><textarea v-model="form.body" maxlength="4000" placeholder="地点、准备事项，或想对家人说的话" :disabled="saving"/><view class="switch-row"><text>每年纪念这一天</text><switch :checked="form.yearly" :disabled="saving" color="var(--c-primary)" @change="form.yearly = $event.detail.value"/></view><text class="date-label">纪念日会在对应日期展示，暂不发送系统提醒。</text><button class="primary" :loading="saving" :disabled="saving" @click="save">保存家庭日程</button></view>
  <view v-if="error" class="empty"><text>{{ error }}</text><button size="mini" @click="load">重试</button></view><view v-else-if="loading" class="empty">正在加载日程…</view><view v-else-if="!entries.length" class="empty">今天留一点空白，也很好。<text class="date-label">点击“新日程”，安排下一次相聚。</text></view><view v-else v-for="row in entries" :key="row.id" class="calendar-card entry"><view class="entry-head"><text class="section">{{ row.title }}</text><EntryEditor kind="calendar" :entry="row" @changed="load" /></view><text class="date-label">{{ row.yearly ? '每年纪念日' : '家庭日程' }}</text><text class="body">{{ row.body || '一起留出时间，陪伴家人。' }}</text></view>
</view></template>
<style scoped>
.calendar-panel{display:flex;flex-direction:column;gap:20px}.calendar-card{background:rgba(255,255,255,.54);border:1px solid rgba(255,255,255,.8);border-radius:30px;padding:22px;box-shadow:0 10px 30px rgba(134,187,255,.28);backdrop-filter:blur(20px)}.entry-head{display:flex;align-items:center;justify-content:space-between;gap:12px}.entry-head :deep(.editor){margin-top:0}.month,.section-head,.switch-row{display:flex;align-items:center;justify-content:space-between}.month{font-weight:600}.month button{margin:0;width:36px;height:36px;padding:0;line-height:36px;border-radius:50%;background:rgba(255,255,255,.54);color:#142B53;border:1px solid rgba(255,255,255,.8)}.grid{display:grid;grid-template-columns:repeat(7,minmax(0,1fr));gap:4px;margin-top:22px;text-align:center}.weekday{font-size:11px;color:var(--c-muted);padding-bottom:8px}.day{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:1px;font-size:14px;padding:4px 0 8px;line-height:1.1;height:62px;background:transparent;border-radius:16px;position:relative;color:inherit}.day:after{border:0}.solar{font-size:14px}.lunar,.extra{font-size:10px;font-weight:400;min-height:12px}.lunar{color:var(--c-muted)}.extra{color:transparent}.festival .extra,.rest .extra{color:var(--c-primary)}.term .extra{color:#6f675f}.rest .solar{color:var(--c-primary)}.badge{position:absolute;top:2px;right:2px;font-size:9px;line-height:1;color:var(--c-primary)}.work .badge{color:#6f675f}.selected{background:#4F7DC6;color:#F4F7FF;box-shadow:0 8px 16px rgba(134,187,255,.4);border-radius:16px}.selected .lunar,.selected .extra,.selected .badge,.selected .dot{color:#F4F7FF}.today .solar{font-weight:700}.dot{position:absolute;bottom:0;left:0;right:0;color:var(--c-primary);font-size:14px;line-height:1}.legend{display:block;margin-top:12px;font-size:11px;color:var(--c-muted);line-height:1.6}.section{font-size:16px;font-weight:600}.date-label{display:block;font-size:11px;color:var(--c-muted);margin-top:6px;line-height:1.8}.primary{background:#4F7DC6;color:#F4F7FF;border-radius:30px;border:1px solid rgba(255,255,255,.8);margin:0;font-size:12px;padding:0 16px;height:36px;line-height:36px;box-shadow:0 8px 20px rgba(134,187,255,.45)}.form{display:flex;flex-direction:column;gap:14px}.form input,.form textarea{padding:14px;background:rgba(255,255,255,.72);border:1px solid rgba(255,255,255,.8);border-radius:20px;font-size:13px;width:100%;box-sizing:border-box;color:#142B53}.form input{height:46px}.form textarea{height:100px}.switch-row{font-size:12px}.empty{text-align:center;padding:30px 10px;color:var(--c-muted);font-size:13px}.entry{border-left:3px solid var(--c-primary)}.body{display:block;font-size:13px;line-height:1.9;margin-top:15px;white-space:pre-wrap}
</style>
