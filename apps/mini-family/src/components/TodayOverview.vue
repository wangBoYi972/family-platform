<script setup>
import { computed, onMounted, ref } from "vue";
import { request } from "../utils/request";
import { filterTasks, localDate } from "../../../shared/tasks.mjs";
import CountTo from "./CountTo.vue";

const emit = defineEmits(["session-ended", "go"]);
const today = ref(localDate());
const tasks = ref([]), events = ref([]), dynamics = ref([]), members = ref([]);
const loading = ref(false), error = ref("");

const backlog = computed(() => filterTasks(tasks.value, { status: "ALL", today: today.value }));
const todayTasks = computed(() => backlog.value.filter(item => item.status !== "COMPLETED" && item.deadline && item.deadline <= today.value));
const overdue = computed(() => backlog.value.filter(item => item.status !== "COMPLETED" && item.deadline && item.deadline < today.value));
const doneRate = computed(() => {
  if (!tasks.value.length) return 0;
  return Math.round((tasks.value.filter(item => item.status === "COMPLETED").length / tasks.value.length) * 100);
});
const monthEvents = computed(() => {
  const prefix = today.value.slice(0, 7);
  return events.value.filter(item => item.eventDate?.startsWith(prefix) || item.yearly);
});

function nextOccurrence(row) {
  if (!row.eventDate) return null;
  const [year, month, day] = row.eventDate.split("-").map(Number);
  const base = new Date(today.value + "T00:00:00");
  const target = new Date(row.yearly ? base.getFullYear() : year, month - 1, day);
  if (row.yearly && target < base) target.setFullYear(base.getFullYear() + 1);
  const days = Math.round((target - base) / 86400000);
  if (days < 0) return null;
  return { days, title: row.title, yearly: !!row.yearly, date: localDate(target) };
}
const countdowns = computed(() => events.value
  .map(nextOccurrence)
  .filter(Boolean)
  .sort((a, b) => a.days - b.days)
  .slice(0, 2));
const recentDynamics = computed(() => dynamics.value.slice(0, 3));
const weekday = computed(() => ["星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"][new Date(today.value + "T00:00:00").getDay()]);
const dateCaption = computed(() => {
  const [, month, day] = today.value.split("-");
  return `${Number(month)} 月 ${Number(day)} 日`;
});

async function load() {
  today.value = localDate();
  loading.value = true;
  error.value = "";
  try {
    const [taskList, calendar, feed, people] = await Promise.all([
      request("/tasks"), request("/calendar"), request("/dynamics"), request("/users/members")
    ]);
    tasks.value = taskList;
    events.value = calendar;
    dynamics.value = feed;
    members.value = people;
  } catch (e) {
    error.value = e.message || "首页数据加载失败";
    if (e.status === 401) emit("session-ended");
  } finally { loading.value = false; }
}
function go(tab) { emit("go", tab); }
const metrics = [
  { key: "members", label: "家庭成员", tab: "我的家" },
  { key: "events", label: "本月安排", tab: "日历" },
  { key: "done", label: "任务完成率", tab: "任务" },
  { key: "dynamics", label: "最近动态", tab: "家庭圈" }
];
function metricValue(key) {
  if (key === "members") return members.value.length;
  if (key === "events") return monthEvents.value.length;
  if (key === "done") return doneRate.value;
  return dynamics.value.length;
}
onMounted(load);
defineExpose({ load });
</script>

<template>
  <view class="overview">
    <!-- 今日窄条 -->
    <view class="today-card mf-card mf-rise" style="--i:0">
      <view class="today-info">
        <text class="today-note">{{ todayTasks.length ? `今天有 ${todayTasks.length} 件事要处理` : '今天没有到期待办，安心陪家人' }}</text>
        <text class="today-sub">{{ weekday }} · {{ dateCaption }}<text v-if="overdue.length" class="warn"> · 已逾期 {{ overdue.length }} 件</text></text>
      </view>
      <view v-if="countdowns.length" class="countdown-list">
        <view v-for="(item, index) in countdowns" :key="item.title" class="countdown mf-press" :style="{ '--i': index }" @click="go({ tab: '日历', calTab: '纪念日' })">
          <text class="days">{{ item.days === 0 ? '今天' : item.days + ' 天' }}</text>
          <text class="countdown-title">{{ item.title }}</text>
        </view>
      </view>
    </view>

    <!-- 家庭数据 -->
    <view class="metrics">
      <view v-for="(item, index) in metrics" :key="item.key" class="metric mf-card mf-card--flat mf-lift mf-press mf-rise" :style="{ '--i': index + 1 }" @click="go(item.tab)">
        <CountTo class="figure" :value="metricValue(item.key)" :suffix="item.key === 'done' ? '%' : ''" />
        <text class="caption">{{ item.label }}</text>
      </view>
    </view>

    <!-- 加载 / 出错 -->
    <view v-if="loading" class="metrics">
      <view v-for="index in 4" :key="index" class="metric mf-card mf-card--flat"><view class="mf-skeleton sk-block small"></view></view>
    </view>
    <view v-else-if="error" class="state mf-card mf-card--flat"><text>{{ error }}</text><button class="retry" @click="load">重新加载</button></view>
  </view>
</template>

<style scoped>
.overview{display:flex;flex-direction:column;gap:16px}

/* 今日窄条 */
.today-card{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:12px 18px;padding:16px 22px;border-radius:var(--r-lg);background:rgba(255,255,255,.9)}
.today-info{min-width:0}
.today-note{display:block;font-size:14px;font-weight:640;color:var(--c-ink)}
.today-sub{display:block;font-size:11px;color:var(--c-muted);margin-top:4px}
.warn{color:var(--c-danger)}
.countdown-list{display:flex;gap:10px;flex-wrap:wrap}
.countdown{display:flex;align-items:center;gap:9px;padding:7px 12px 7px 7px;margin:0 -4px;border-radius:var(--r-pill);cursor:pointer;transition:transform var(--t-fast) var(--e-spring)}
.countdown:active{transform:scale(.95);background:rgba(196,121,90,.08)}
.days{background:var(--c-primary-deep);color:#fffdf8;border-radius:2px;padding:4px 8px;font-size:11px;font-weight:650;flex-shrink:0}
.countdown-title{font-size:12px;font-weight:560;color:var(--c-ink);max-width:130px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}

/* 数据卡 */
.metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:12px}
.metric{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:5px;padding:16px 8px;text-align:center;border-radius:16px;cursor:pointer}
.metric .figure{font-size:22px}
.metric::before{content:'';position:absolute;left:50%;top:0;width:34px;height:3px;margin-left:-17px;border-radius:2px;background:var(--c-primary);opacity:0;transition:opacity var(--t) var(--e-out)}
.metric:hover::before{opacity:1}

/* 状态 */
.state{text-align:center;color:var(--c-muted);font-size:13px;padding:26px 12px;border-radius:var(--r-lg)}
.state button{margin-top:10px;font-size:12px}
.retry{border-radius:var(--r-pill);background:var(--c-primary-soft);color:var(--c-primary-deep);border:0;padding:8px 20px}

/* 骨架屏 */
.sk-block{height:52px}
.sk-block.small{height:44px}

@media(max-width:760px){
  .today-card{padding:15px 18px}
  .metrics{grid-template-columns:repeat(4,1fr);gap:8px}
  .metric{padding:12px 4px;border-radius:14px}
  .metric .figure{font-size:18px}
  .caption{font-size:10px}
}
</style>
