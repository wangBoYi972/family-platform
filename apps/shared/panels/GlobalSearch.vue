<script setup>
import { computed, onUnmounted, ref, watch } from "vue";
import { request } from "@app/utils/request";

const emit = defineEmits(["navigate", "session-ended"]);
const open = ref(false), keyword = ref(""), loading = ref(false), error = ref(""), warning = ref("");
const groups = ref([]);
const history = ref(uni.getStorageSync("family_search_history") || []);
const activeType = ref("全部");

const HISTORY_KEY = "family_search_history";
/** 每类最多展示几条。搜索在服务端做，这里只是展示上限，不是检索上限。 */
const PER_SOURCE = 8;
/** 输入停顿多久才发请求：一边打字一边给八个服务发查询，纯属浪费。 */
const DEBOUNCE_MS = 220;

/** 每条数据源定义怎么查、命中后跳到哪里。 */
const sources = [
  {
    key: "tasks", path: "/tasks", label: "任务", icon: "✓",
    to: item => ({ id: item.id, title: item.title, desc: `负责人：${item.assigneeName || "未指派"} · ${statusLabel(item.status)}`, meta: item.deadline || "" })
  },
  {
    key: "calendar", path: "/calendar", label: "日程", icon: "▦",
    to: item => ({ id: item.id, title: item.title, desc: item.body || "家庭日程", meta: item.yearly ? `每年 ${item.eventDate?.slice(5) || ""}` : item.eventDate || "" })
  },
  {
    key: "dynamics", path: "/dynamics", label: "动态", icon: "▧",
    to: item => ({ id: item.id, title: item.title, desc: item.body || "和家人一起记住这一刻。", meta: (item.likes || 0) + " 人喜欢" })
  },
  {
    key: "albums", path: "/albums", label: "相册", icon: "◫",
    to: item => ({ id: item.id, title: item.title, desc: item.body || "时光相册", meta: item.eventDate || "" })
  },
  {
    key: "notices", path: "/notices", label: "公告", icon: "❢",
    to: item => ({ id: item.id, title: item.title, desc: item.body || "家人的叮嘱", meta: item.pinned ? "置顶" : "" })
  },
  {
    key: "archives", path: "/archives", label: "档案", icon: "▤",
    to: item => ({ id: item.id, title: item.title, desc: `${categoryLabel(item.category)} · ${item.visibility === "FAMILY" ? "全家可见" : "仅自己"}`, meta: String(item.createdAt || "").slice(0, 10) })
  },
  {
    key: "members", path: "/users/members", label: "家人", icon: "○",
    // 成员接口没有关键字/分页：一个家庭就几个人，整份取回来本地筛更省事
    local: true,
    text: item => `${item.displayName} ${item.role || ""}`,
    to: item => ({ id: item.id, title: item.displayName, desc: item.role === "ADMIN" ? "家庭管理员" : "家庭成员", meta: "" })
  },
  {
    key: "files", path: "/files", label: "文件", icon: "⌸",
    to: item => ({ id: item.id, title: item.name, desc: `${formatSize(item.sizeBytes)} · ${item.visibility === "FAMILY" ? "全家可见" : "仅自己"}`, meta: String(item.createdAt || "").slice(0, 10) })
  }
];

const statusText = { TODO: "待办", IN_PROGRESS: "进行中", COMPLETED: "已完成" };
// 兼容档案服务当前枚举，同时保留旧数据中的短枚举，避免历史记录显示成“其他”。
const categoryText = {
  IDENTITY: "证件",
  ID: "证件",
  HEALTH: "健康",
  DOCUMENT: "文档",
  DOC: "文档",
  OTHER: "其他"
};
function statusLabel(value) { return statusText[value] || "待办"; }
function categoryLabel(value) { return categoryText[value] || "其他"; }
function formatSize(bytes) {
  const size = Number(bytes) || 0;
  if (size < 1024) return size + " B";
  if (size < 1024 * 1024) return (size / 1024).toFixed(1) + " KB";
  return (size / 1024 / 1024).toFixed(1) + " MB";
}

const visibleGroups = computed(() =>
  activeType.value === "全部" ? groups.value : groups.value.filter(item => item.label === activeType.value)
);
const total = computed(() => groups.value.reduce((sum, group) => sum + group.items.length, 0));
const typeFilters = computed(() => ["全部", ...new Set(groups.value.map(item => item.label))]);

let seq = 0, timer = null;

/**
 * 关键字搜索。
 *
 * <p>检索放在服务端：原来是把八类数据整份拉回来本地 `includes`，看似简单，实际只搜得到
 * 「第一页」。家里数据一多，明明存在的公告、档案就是搜不出来，而且每次打开搜索都要
 * 打八个接口把全家内容下载一遍。
 */
async function runSearch() {
  const q = keyword.value.trim();
  const current = ++seq;
  if (!q) {
    groups.value = [];
    error.value = "";
    warning.value = "";
    loading.value = false;
    return;
  }
  loading.value = true;
  error.value = "";
  warning.value = "";
  const lowered = q.toLocaleLowerCase();
  const results = await Promise.allSettled(
    sources.map(source => source.local
      ? request(source.path)
      : request(source.path, { data: { keyword: q, page: 1, size: PER_SOURCE } }))
  );
  // 打字快时后发的请求可能先回来：只认最后一次输入的结果
  if (current !== seq) return;
  const failed = [];
  let unauthorized = false;
  const next = [];
  results.forEach((result, index) => {
    const source = sources[index];
    if (result.status === "rejected") {
      failed.push(source.label);
      unauthorized ||= result.reason?.status === 401;
      return;
    }
    const rows = Array.isArray(result.value) ? result.value : [];
    const items = (source.local
      ? rows.filter(item => String(source.text(item) || "").toLocaleLowerCase().includes(lowered))
      : rows)
      .slice(0, PER_SOURCE)
      .map(source.to);
    if (items.length) next.push({ key: source.key, label: source.label, icon: source.icon, items });
  });
  groups.value = next;
  loading.value = false;
  if (unauthorized) {
    error.value = "登录已失效，请重新登录";
    emit("session-ended");
  } else if (failed.length === sources.length) {
    error.value = "家庭内容暂时无法加载，请稍后重试";
  } else if (failed.length) {
    warning.value = `部分内容暂时不可用：${failed.join("、")}`;
  }
}

watch(keyword, () => {
  if (timer) clearTimeout(timer);
  timer = setTimeout(runSearch, DEBOUNCE_MS);
});
onUnmounted(() => { if (timer) clearTimeout(timer); });

function openPanel() {
  open.value = true;
  activeType.value = "全部";
  if (keyword.value.trim()) runSearch();
}
function close() { open.value = false; keyword.value = ""; }
function reload() { runSearch(); }

function record() {
  const value = keyword.value.trim();
  if (!value) return;
  const next = [value, ...history.value.filter(item => item !== value)].slice(0, 8);
  history.value = next;
  uni.setStorageSync(HISTORY_KEY, next);
}
function clearHistory() {
  history.value = [];
  uni.removeStorageSync(HISTORY_KEY);
}
function useHistory(value) { keyword.value = value; }

function jump(group, item) {
  record();
  close();
  // 只上报稳定的数据源 key（tasks/calendar/...），各端自己映射到本端的 tab 结构，
  // 否则共享组件会把家庭端的 tab 名称硬编码进移动端。
  emit("navigate", { key: group.key });
}

/** 把命中的关键字切成片段供模板着色，避免使用 v-html。 */
function segments(text) {
  const value = String(text || "");
  const q = keyword.value.trim();
  if (!q) return [{ text: value, hit: false }];
  const lower = value.toLocaleLowerCase();
  const target = q.toLocaleLowerCase();
  const parts = [];
  let cursor = 0;
  let found = lower.indexOf(target, cursor);
  while (found !== -1) {
    if (found > cursor) parts.push({ text: value.slice(cursor, found), hit: false });
    parts.push({ text: value.slice(found, found + target.length), hit: true });
    cursor = found + target.length;
    found = lower.indexOf(target, cursor);
  }
  if (cursor < value.length) parts.push({ text: value.slice(cursor), hit: false });
  return parts.length ? parts : [{ text: value, hit: false }];
}

defineExpose({ open: openPanel });
</script>

<template>
  <view v-if="open" class="search">
    <view class="search-panel">
      <view class="search-head">
        <text class="search-title">找找家里的东西</text>
        <text class="search-close" @click="close">×</text>
      </view>

      <view class="search-input-row">
        <text class="search-icon">⌕</text>
        <input v-model="keyword" class="search-input" confirm-type="search" placeholder="日程、任务、动态、成员、档案…" @confirm="record" />
        <text v-if="keyword" class="search-clear" @click="keyword = ''">×</text>
      </view>

      <view v-if="!keyword.trim()" class="search-history">
        <view class="history-head"><text>最近搜过</text><text v-if="history.length" class="history-clear" @click="clearHistory">清空</text></view>
        <view v-if="!history.length" class="search-hint">输入关键字后，会在日程、任务、动态、相册、公告、档案、成员和文件里一起找。</view>
        <view v-else class="history-tags">
          <text v-for="item in history" :key="item" class="history-tag" @click="useHistory(item)">{{ item }}</text>
        </view>
      </view>

      <view v-else>
        <view v-if="error" class="state"><text>{{ error }}</text><text class="retry" @click="reload()">重新加载</text></view>
        <view v-else-if="loading" class="state">正在整理家里的内容…</view>
        <view v-else>
          <view v-if="warning" class="partial-warning"><text>{{ warning }}</text><text class="retry" @click="reload()">重试</text></view>
          <view v-if="!total" class="state">没有找到和「{{ keyword.trim() }}」相关的内容</view>
          <view v-else class="result">
            <view class="filters">
              <text v-for="item in typeFilters" :key="item" class="filter" :class="{ active: activeType === item }" @click="activeType = item">{{ item }}</text>
            </view>
            <view class="result-scroll">
              <view v-for="group in visibleGroups" :key="group.key" class="group">
                <view class="group-head">
                  <text class="group-icon">{{ group.icon }}</text>
                  <text class="group-label">{{ group.label }}</text>
                  <text class="group-count">{{ group.items.length >= PER_SOURCE ? group.items.length + '+' : group.items.length }}</text>
                </view>
                <view v-for="item in group.items" :key="group.key + '-' + item.id" class="hit" @click="jump(group, item)">
                  <view class="hit-main">
                    <text class="hit-title"><text v-for="(part, index) in segments(item.title)" :key="index" :class="{ hitText: part.hit }">{{ part.text }}</text></text>
                    <text class="hit-desc"><text v-for="(part, index) in segments(item.desc)" :key="index" :class="{ hitText: part.hit }">{{ part.text }}</text></text>
                  </view>
                  <text v-if="item.meta" class="hit-meta">{{ item.meta }}</text>
                </view>
              </view>
            </view>
          </view>
        </view>
      </view>
    </view>
    <view class="search-mask" @click="close"></view>
  </view>
</template>

<style scoped>
.search{position:fixed;inset:0;z-index:120;display:flex;flex-direction:column}
.search-mask{position:absolute;inset:0;background:rgba(25,38,58,.32)}
.search-panel{position:relative;z-index:1;margin:90px auto 0;width:660px;max-width:calc(100% - 32px);max-height:calc(100% - 120px);display:flex;flex-direction:column;background:var(--c-surface);border-radius:20px;box-shadow:0 24px 60px rgba(23,42,70,.22);overflow:hidden}
.search-head{display:flex;align-items:center;justify-content:space-between;padding:18px 22px 0}
.search-title{font-size:14px;font-weight:600;color:var(--c-ink)}
.search-close{font-size:22px;line-height:1;color:var(--c-muted);padding:0 6px}
.search-input-row{display:flex;align-items:center;gap:10px;margin:12px 22px 0;padding:0 14px;height:46px;background:var(--c-surface-2);border:1px solid var(--c-line);border-radius:12px}
.search-icon{font-size:16px;color:var(--c-muted)}
.search-input{flex:1;font-size:14px;background:none;border:0;outline:none;color:var(--c-ink)}
.search-clear{font-size:18px;color:var(--c-muted);padding:0 4px}
.search-history{padding:16px 22px 24px}
.history-head{display:flex;align-items:center;justify-content:space-between;font-size:12px;color:var(--c-muted)}
.history-clear{color:var(--c-primary)}
.search-hint{margin-top:12px;font-size:12px;line-height:1.8;color:var(--c-muted)}
.history-tags{display:flex;flex-wrap:wrap;gap:8px;margin-top:12px}
.history-tag{background:var(--c-surface-2);border-radius:12px;padding:6px 12px;font-size:12px;color:var(--c-sky)}
.result{display:flex;flex-direction:column;min-height:0;margin-top:14px}
.filters{display:flex;flex-wrap:wrap;gap:8px;padding:0 22px 12px;border-bottom:1px solid var(--c-surface-2)}
.filter{font-size:12px;color:var(--c-ink-2);background:var(--c-surface-2);border-radius:12px;padding:5px 11px}
.filter.active{background:var(--c-primary);color:var(--c-surface)}
.result-scroll{overflow-y:auto;padding:8px 22px 22px;-webkit-overflow-scrolling:touch}
.group{margin-top:16px}
.group-head{display:flex;align-items:center;gap:8px;margin-bottom:8px}
.group-icon{font-size:13px;color:var(--c-primary)}
.group-label{font-size:13px;font-weight:600;color:var(--c-ink)}
.group-count{font-size:11px;color:var(--c-muted)}
.hit{display:flex;align-items:center;justify-content:space-between;gap:14px;border-radius:12px;padding:11px 13px;background:var(--c-primary-soft);margin-bottom:6px}
.hit-main{min-width:0;flex:1}
.hit-title{display:block;font-size:13px;font-weight:600;color:var(--c-ink);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.hit-desc{display:block;font-size:11px;color:var(--c-muted);margin-top:3px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.hit-meta{font-size:11px;color:var(--c-muted);flex-shrink:0}
.hitText{color:var(--c-danger);font-weight:700}
.state{text-align:center;color:var(--c-muted);font-size:13px;padding:34px 20px}
.partial-warning{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:12px 22px 0;padding:9px 12px;border:1px solid rgba(207,167,105,.35);border-radius:10px;background:rgba(207,167,105,.12);color:var(--c-ink-2);font-size:11px;line-height:1.5}
.partial-warning .retry{margin-top:0;white-space:nowrap}
.retry{display:block;margin-top:10px;color:var(--c-primary)}
@media(max-width:700px){.search-panel{margin-top:20px;width:calc(100% - 24px);max-height:calc(100% - 40px)}}
</style>
