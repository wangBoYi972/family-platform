<script setup>
import { computed, onMounted, ref } from "vue";
import { request } from "@app/utils/request";
import { localDate } from "@shared/tasks.mjs";
// 折算逻辑与两个宿主的「当天提醒」共用一份实现，改口径不要只改这里
import { buildAnniversary as build } from "@shared/anniversary-remind.mjs";

const emit = defineEmits(["session-ended", "go"]);
const rows = ref([]), members = ref([]);
const loading = ref(false), error = ref("");
const filter = ref("全部");

const today = ref(localDate());

const FILTERS = ["全部", "每年", "一次性"];

/** 把每条日程折算成「下一次还有多少天」；已过去的一次性日程单独标记。 */
const items = computed(() => rows.value.map(row => build(row, today.value)).filter(Boolean).sort((a, b) => a.sort - b.sort));
const yearly = computed(() => items.value.filter(item => item.yearly));
const upcoming = computed(() => items.value.filter(item => item.days >= 0));
const nearest = computed(() => upcoming.value[0] || null);
const thisYearLeft = computed(() => upcoming.value.filter(item => item.date.startsWith(String(new Date().getFullYear()))).length);
const past = computed(() => items.value.filter(item => item.days < 0));

const visible = computed(() => {
  if (filter.value === "每年") return yearly.value;
  if (filter.value === "一次性") return items.value.filter(item => !item.yearly);
  return items.value;
});

const buckets = computed(() => [
  { key: "today", label: "就在今天", hint: "别忘了说一声", pick: item => item.days === 0 },
  { key: "week", label: "7 天内", hint: "可以开始准备", pick: item => item.days > 0 && item.days <= 7 },
  { key: "month", label: "30 天内", hint: "提前记在心里", pick: item => item.days > 7 && item.days <= 30 },
  { key: "later", label: "更远一些", hint: "日子还长", pick: item => item.days > 30 },
  { key: "past", label: "已经过去", hint: "一次性的安排", pick: item => item.days < 0 }
]
  .map(bucket => ({ ...bucket, items: visible.value.filter(bucket.pick) }))
  .filter(bucket => bucket.items.length));

function ownerName(row) {
  const person = members.value.find(item => String(item.id) === String(row.creatorId));
  return person ? person.displayName : "";
}
function when(item) {
  if (item.days === 0) return "今天";
  if (item.days === 1) return "明天";
  if (item.days < 0) return "已过";
  return item.days + " 天";
}
function openCalendar() { emit("go", { key: "calendar" }); }

async function load() {
  loading.value = true;
  error.value = "";
  today.value = localDate();
  try {
    const [list, people] = await Promise.all([request("/calendar"), request("/users/members")]);
    rows.value = list;
    members.value = people;
  } catch (e) {
    error.value = e.message || "纪念日加载失败";
    if (e.status === 401) emit("session-ended");
  } finally {
    loading.value = false;
  }
}

// 不分页的面板：sync 与 load 等价，统一方法名好让宿主一视同仁。
defineExpose({ load, sync: load });
onMounted(load);
</script>

<template>
  <view class="anniversary">
    <view class="metrics">
      <view class="metric"><text class="figure">{{ nearest ? (nearest.days === 0 ? '今天' : nearest.days + ' 天') : '—' }}</text><text class="caption">{{ nearest ? '最近：' + nearest.title : '还没有安排' }}</text></view>
      <view class="metric"><text class="figure">{{ yearly.length }}</text><text class="caption">每年纪念</text></view>
      <view class="metric"><text class="figure">{{ thisYearLeft }}</text><text class="caption">今年还剩</text></view>
      <view class="metric"><text class="figure">{{ past.length }}</text><text class="caption">已过去</text></view>
    </view>

    <view class="toolbar">
      <view class="filters">
        <text v-for="item in FILTERS" :key="item" class="chip" :class="{ active: filter === item }" @click="filter = item">{{ item }}</text>
      </view>
      <text class="link" @click="openCalendar">去日历添加 →</text>
    </view>

    <view v-if="error" class="state"><text>{{ error }}</text><button size="mini" @click="load">重新加载</button></view>
    <view v-else-if="loading" class="state">正在算一算还有多久…</view>
    <view v-else-if="!buckets.length" class="state">
      <text>{{ rows.length ? '这类日期暂时没有' : '还没有纪念日' }}</text>
      <text class="hint">在日历里新建日程时勾上「每年纪念这一天」，生日、结婚纪念日就会出现在这里。</text>
    </view>

    <view v-else class="buckets">
      <view v-for="bucket in buckets" :key="bucket.key" class="bucket">
        <view class="bucket-head">
          <text class="bucket-label">{{ bucket.label }}</text>
          <text class="bucket-hint">{{ bucket.hint }}</text>
        </view>
        <view v-for="item in bucket.items" :key="item.id" class="row">
          <view class="days" :class="{ soon: item.days >= 0 && item.days <= 7, gone: item.days < 0 }">{{ when(item) }}</view>
          <view class="body">
            <view class="title-row">
              <text class="title">{{ item.title }}</text>
              <text v-if="item.pinned" class="tag pin">置顶</text>
              <text v-if="item.yearly" class="tag">每年</text>
            </view>
            <text class="meta">
              {{ item.date }}<text v-if="item.yearly && item.nth > 1"> · 第 {{ item.nth }} 个</text><text v-if="item.yearly && item.origin"> · 起于 {{ item.origin }}</text>
            </text>
            <text v-if="item.body" class="note">{{ item.body }}</text>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.anniversary{display:flex;flex-direction:column;gap:16px}
.metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:12px}
.metric{background:rgba(255,255,255,.54);border:1px solid rgba(255,255,255,.8);border-radius:24px;padding:14px;text-align:center}
.figure{display:block;font-size:20px;font-weight:700;color:var(--c-primary)}
.caption{font-size:11px;color:var(--c-muted);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}
.filters{display:flex;gap:8px}
.chip{font-size:12px;color:var(--c-ink-2);background:var(--c-surface-2);border-radius:12px;padding:5px 11px}
.chip.active{background:var(--c-primary);color:var(--c-surface)}
.link{font-size:12px;color:var(--c-primary)}
.buckets{display:flex;flex-direction:column;gap:22px}
.bucket-head{display:flex;align-items:baseline;gap:10px;margin-bottom:10px}
.bucket-label{font-size:14px;font-weight:700;color:var(--c-ink)}
.bucket-hint{font-size:11px;color:var(--c-muted)}
.row{display:flex;align-items:flex-start;gap:14px;background:rgba(255,255,255,.54);border:1px solid rgba(255,255,255,.8);border-radius:24px;padding:13px 15px;margin-bottom:8px}
.days{min-width:58px;text-align:center;font-size:13px;font-weight:700;color:var(--c-surface);background:var(--c-muted);border-radius:11px;padding:6px 8px;flex-shrink:0}
.days.soon{background:var(--c-danger)}
.days.gone{background:var(--c-line)}
.body{flex:1;min-width:0}
.title-row{display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.title{font-size:13px;font-weight:600;color:var(--c-ink)}
.tag{font-size:10px;color:var(--c-sage);background:var(--c-sage-soft);border-radius:8px;padding:3px 8px}
.tag.pin{color:var(--c-plum);background:var(--c-plum-soft)}
.meta{display:block;font-size:11px;color:var(--c-muted);margin-top:4px}
.note{display:block;font-size:11px;color:var(--c-muted);margin-top:4px;line-height:1.7}
.state{text-align:center;color:var(--c-muted);font-size:13px;padding:34px 10px;display:flex;flex-direction:column;gap:8px;align-items:center}
.state button{font-size:11px}
.hint{font-size:11px;color:var(--c-muted);line-height:1.7;max-width:420px}
@media(max-width:700px){.metrics{grid-template-columns:repeat(2,1fr)}.figure{font-size:17px}}
</style>
