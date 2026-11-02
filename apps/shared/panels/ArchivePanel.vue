<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import { request } from "@app/utils/request";
import { pageQuery } from "../paged-list.mjs";
import { usePagedList } from "../use-paged-list.js";
import { ACTION_LABELS, fillDays, percent } from "@shared/audit-view.mjs";
import ListMore from "../ListMore.vue";

const emit = defineEmits(["session-ended"]);
const categories = [{ value: "ALL", label: "全部" }, { value: "IDENTITY", label: "证件" }, { value: "HEALTH", label: "健康" }, { value: "DOCUMENT", label: "文档" }];
const categoryLabel = { IDENTITY: "证件", HEALTH: "健康", DOCUMENT: "文档" };
const keyword = ref(""), filter = ref("ALL");
// 关键字与分类都交给服务端：分页后本地过滤只能覆盖已加载的档案
function archivePath() {
  const params = [];
  const text = keyword.value.trim();
  if (text) params.push("keyword=" + encodeURIComponent(text));
  if (filter.value !== "ALL") params.push("category=" + filter.value);
  return params.length ? "/archives?" + params.join("&") : "/archives";
}
const {
  items: rows, loading, loadingMore, exhausted, error: listError,
  refresh, reload, loadMore
} = usePagedList((page, size) => request(pageQuery(archivePath(), page, size)));
const members = ref([]), session = ref(null);
const error = computed(() => listError.value?.message || "");
const searching = computed(() => keyword.value.trim() !== "" || filter.value !== "ALL");
const creating = ref(false), saving = ref(false), opened = ref(new Set());
const details = ref({}), audits = ref({});
const form = ref({ id: "", title: "", category: "HEALTH", visibility: "FAMILY", body: "" });

const memberMap = computed(() => Object.fromEntries(members.value.map(item => [String(item.id), item.displayName])));
const stats = computed(() => ({
  total: rows.value.length,
  shared: rows.value.filter(item => item.visibility === "FAMILY").length,
  health: rows.value.filter(item => item.category === "HEALTH").length
}));

function report(error) {
  if (!error) return;
  if (error.status === 401) emit("session-ended");
  uni.showToast({ title: error.message || "请求失败", icon: "none" });
}
function ownerLabel(id) { return memberMap.value[String(id)] || "家庭成员"; }
function canManage(item) { return !!session.value && (session.value.role === "ADMIN" || String(item.ownerId) === String(session.value.userId)); }
function stamp(value) { return value ? String(value).slice(0, 10) : ""; }
function stampFull(value) { return value ? String(value).slice(0, 16).replace("T", " ") : ""; }

// 审计总览（仅管理员）：谁在看、哪天看的多、什么动作，纯 CSS 条形图不引图表库。
// summary 接口按家庭聚合近 30 天日志，能看到私有档案的访问记录，所以只给管理员。
const showSummary = ref(false), summary = ref(null), summaryLoading = ref(false);
const isAdmin = computed(() => session.value?.role === "ADMIN");
const summaryDays = computed(() => fillDays(summary.value?.byDay || [], 30));
const summaryMax = computed(() => Math.max(...summaryDays.value.map(d => d.count), 0));
const summaryUsers = computed(() => {
  const list = (summary.value?.byUser || []).slice(0, 5);
  const max = Math.max(...list.map(item => item.count), 0);
  return list.map(item => ({ name: ownerLabel(item.name), count: item.count, width: percent(item.count, max) }));
});
function actionLabel(key) { return ACTION_LABELS[key] || key; }
async function toggleSummary() {
  showSummary.value = !showSummary.value;
  if (!showSummary.value || summary.value || summaryLoading.value) return;
  summaryLoading.value = true;
  try { summary.value = await request("/archives/audit/summary"); }
  catch (e) { showSummary.value = false; report(e); }
  finally { summaryLoading.value = false; }
}

async function load() {
  // 列表分页 + 身份/成员并行；身份失败不影响列表展示。
  const identity = request("/auth/session").then(value => ({ value })).catch(error => ({ error }));
  const people = request("/users/members").then(value => ({ value })).catch(error => ({ error }));
  const [ok, identityResult, peopleResult] = await Promise.all([refresh(), identity, people]);
  if (identityResult.value) session.value = identityResult.value;
  if (peopleResult.value) members.value = peopleResult.value;
  if (identityResult.error) report(identityResult.error);
  if (peopleResult.error) report(peopleResult.error);
  if (!ok) report(listError.value);
}
/** 实时刷新：只重拉列表，身份与成员不变；筛选条件与页码都保持。 */
async function sync() {
  if (await reload()) return;
  report(listError.value);
}
let keywordTimer = null;
watch(keyword, () => {
  clearTimeout(keywordTimer);
  keywordTimer = setTimeout(load, 300);
});
watch(filter, () => { clearTimeout(keywordTimer); load(); });
async function loadMoreArchives() {
  if (await loadMore()) return;
  report(listError.value);
}
function openCreate() {
  if (saving.value) return;
  creating.value = !creating.value;
  if (creating.value) form.value = { id: "", title: "", category: "HEALTH", visibility: "FAMILY", body: "" };
}
function openEdit(item) {
  if (saving.value) return;
  creating.value = false;
  form.value = { id: item.id, title: item.title, category: item.category, visibility: item.visibility, body: "" };
  details.value = { ...details.value, [item.id]: { body: "正在加载…", pending: true } };
  request("/archives/" + item.id)
    .then(detail => {
      details.value = { ...details.value, [item.id]: { body: detail.body, pending: false } };
      form.value.body = detail.body;
    })
    .catch(() => { details.value = { ...details.value, [item.id]: { body: "正文加载失败，可直接覆盖保存。", pending: false } }; });
}
async function save() {
  if (saving.value) return;
  const title = form.value.title.trim();
  if (!title || !form.value.body.trim()) return uni.showToast({ title: "请填写标题与正文", icon: "none" });
  saving.value = true;
  try {
    const payload = { title, category: form.value.category, visibility: form.value.visibility, body: form.value.body };
    if (form.value.id) await request("/archives/" + form.value.id, { method: "PUT", data: payload });
    else await request("/archives", { method: "POST", data: payload });
    uni.showToast({ title: "档案已保存", icon: "none" });
    creating.value = false;
    details.value = {};
    await load();
  } catch (e) { report(e); }
  finally { saving.value = false; }
}
async function remove(item) {
  try {
    const result = await uni.showModal({ title: "确认删除", content: `删除「${item.title}」后无法恢复，确定继续吗？`, confirmText: "删除" });
    if (!result.confirm) return;
    await request("/archives/" + item.id, { method: "DELETE" });
    uni.showToast({ title: "已删除", icon: "none" });
    await load();
  } catch (e) { if (!String(e?.errMsg || "").includes("cancel")) report(e); }
}
async function toggle(item) {
  const next = new Set(opened.value);
  if (next.has(item.id)) { next.delete(item.id); opened.value = next; return; }
  next.add(item.id);
  opened.value = next;
  if (details.value[item.id]) return;
  try {
    const [detail, audit] = await Promise.all([request("/archives/" + item.id), request("/archives/" + item.id + "/audit")]);
    details.value = { ...details.value, [item.id]: { body: detail.body } };
    audits.value = { ...audits.value, [item.id]: audit };
  } catch (e) { report(e); }
}
onMounted(load);
onUnmounted(() => clearTimeout(keywordTimer));
defineExpose({ load, sync });
</script>

<template>
  <view class="archive">
    <view class="toolbar">
      <view>
        <text class="title">家庭档案</text>
        <text class="hint">证件 · 健康记录 · 重要文档，谁看过都有记录</text>
      </view>
      <button size="mini" :disabled="loading" @click="load">刷新</button>
      <button size="mini" class="primary" :disabled="saving" @click="openCreate">{{ creating ? "收起" : "＋ 新建档案" }}</button>
    </view>

    <!-- 筛选时列表只剩匹配项，此时再显示「档案总数 / 健康记录 / 全家可见」会误导，改为显示结果条数 -->
    <view v-if="!searching" class="stats">
      <view class="stat"><text class="stat-value">{{ stats.total }}</text><text class="stat-label">档案总数</text></view>
      <view class="stat"><text class="stat-value">{{ stats.health }}</text><text class="stat-label">健康记录</text></view>
      <view class="stat"><text class="stat-value">{{ stats.shared }}</text><text class="stat-label">全家可见</text></view>
    </view>
    <text v-else class="scope-note">筛选结果 {{ rows.length }} 条{{ exhausted ? '' : '（还有更多，继续加载可看到全部匹配）' }}</text>
    <text v-if="!exhausted && rows.length" class="hint">统计与筛选基于已加载的 {{ rows.length }} 条，继续加载可扩大范围</text>

    <view v-if="isAdmin" class="summary-toggle">
      <button size="mini" @click="toggleSummary">{{ showSummary ? "收起访问总览" : "📊 访问总览（近 30 天）" }}</button>
    </view>
    <view v-if="showSummary" class="summary card">
      <view v-if="summaryLoading" class="state-inline">正在汇总访问记录…</view>
      <template v-else-if="summary">
        <text class="summary-title">近 30 天共 {{ summary.total }} 次访问</text>
        <view v-if="summary.total" class="chart" aria-label="近 30 天每日访问次数">
          <view v-for="day in summaryDays" :key="day.day" class="bar-slot" :title="day.day + '：' + day.count + ' 次'">
            <view class="bar" :style="{ height: percent(day.count, summaryMax) + '%' }"></view>
          </view>
        </view>
        <text class="axis-note">{{ summaryDays[0]?.day }} ~ {{ summaryDays[summaryDays.length - 1]?.day }}</text>
        <view v-if="summaryUsers.length" class="ranking">
          <text class="summary-title">谁看得最多</text>
          <view v-for="item in summaryUsers" :key="item.name" class="rank-row">
            <text class="rank-name">{{ item.name }}</text>
            <view class="rank-track"><view class="rank-bar" :style="{ width: item.width + '%' }"></view></view>
            <text class="rank-count">{{ item.count }}</text>
          </view>
        </view>
        <view v-if="summary.total" class="actions-line">
          <text v-for="item in summary.byAction" :key="item.name" class="tag">{{ actionLabel(item.name) }} {{ item.count }}</text>
        </view>
      </template>
    </view>

    <view v-if="creating" class="form card">
      <input v-model="form.title" class="input" maxlength="120" placeholder="档案标题（必填）" :disabled="saving" />
      <view class="switch-row"><text>家人可见</text><switch :checked="form.visibility === 'FAMILY'" :disabled="saving" color="var(--c-primary)" @change="form.visibility = $event.detail.value ? 'FAMILY' : 'PRIVATE'" /></view>
      <text class="hint">设为私有时，只有本人和管理员可以查看。</text>
      <view class="chips"><button v-for="item in categories.filter(item => item.value !== 'ALL')" :key="item.value" size="mini" :class="{ primary: form.category === item.value }" :disabled="saving" @click="form.category = item.value">{{ item.label }}</button></view>
      <textarea v-model="form.body" class="textarea" maxlength="20000" placeholder="记录内容：体检结论、疫苗时间、证件编号或存放位置…" :disabled="saving" />
      <view class="actions">
        <button class="primary" :loading="saving" :disabled="saving" @click="save">保存档案</button>
        <button size="mini" :disabled="saving" @click="creating = false">取消</button>
      </view>
    </view>

    <input v-model="keyword" class="input" placeholder="搜索档案标题或内容" />
    <view class="chips">
      <button v-for="item in categories" :key="item.value" size="mini" :class="{ primary: filter === item.value }" @click="filter = item.value">{{ item.label }}</button>
    </view>

    <view v-if="error" class="state"><text>{{ error }}</text><button size="mini" @click="load">重新加载</button></view>
    <view v-else-if="loading" class="state">正在加载档案…</view>
    <view v-else-if="!rows.length" class="state">{{ searching ? "没有匹配的档案，换个关键词试试" : "还没有档案，把体检单、疫苗记录或证件信息存进家庭保险箱。" }}</view>
    <view v-else class="list">
      <view v-for="item in rows" :key="item.id" class="card">
        <view class="row-head" @click="toggle(item)">
          <view class="head-main">
            <text class="row-title">{{ item.title }}</text>
            <text class="meta">{{ ownerLabel(item.ownerId) }} · {{ stamp(item.createdAt) }}</text>
          </view>
          <view class="tags">
            <text class="tag">{{ categoryLabel[item.category] || item.category }}</text>
            <text :class="['tag', item.visibility === 'FAMILY' ? 'tag-open' : 'tag-private']">{{ item.visibility === 'FAMILY' ? '全家可见' : '私有' }}</text>
          </view>
          <text class="caret">{{ opened.has(item.id) ? '收起' : '查看' }}</text>
        </view>
        <view v-if="opened.has(item.id)" class="detail">
          <text class="body">{{ details[item.id]?.body ?? '正在加载正文…' }}</text>
          <view v-if="audits[item.id]?.length" class="audit">
            <text class="audit-title">访问记录</text>
            <view v-for="(log, index) in audits[item.id].slice(-6).reverse()" :key="index" class="audit-row">
              <text>{{ ownerLabel(log.userId) }}</text><text class="meta">{{ stampFull(log.createdAt) }} · {{ log.action }}</text>
            </view>
          </view>
          <view v-if="canManage(item)" class="actions">
            <button size="mini" :disabled="saving" @click="openEdit(item)">编辑</button>
            <button size="mini" class="danger" @click="remove(item)">删除</button>
          </view>
        </view>
      </view>
    </view>
    <ListMore :count="rows.length" :loading="loadingMore" :exhausted="exhausted" :has-error="!!listError" @more="loadMoreArchives" />
  </view>
</template>

<style scoped>
.archive{display:flex;flex-direction:column;gap:14px}
.toolbar{display:flex;align-items:flex-start;justify-content:space-between;gap:10px}
.title{font-size:17px;font-weight:650}
.hint{display:block;font-size:11px;color:var(--c-muted);margin-top:5px;line-height:1.8}
.toolbar button{margin:0;font-size:12px}
.primary{background:var(--c-primary);color:white}
.scope-note{font-size:12px;color:var(--c-muted)}
.stats{display:grid;grid-template-columns:repeat(3,1fr);gap:12px}
.stat{background:white;border:1px solid var(--c-line);border-radius:14px;padding:14px;text-align:center}
.stat-value{display:block;font-size:22px;font-weight:700;color:var(--c-primary)}
.stat-label{display:block;font-size:11px;color:var(--c-muted);margin-top:4px}
.input,.textarea{width:100%;box-sizing:border-box;background:var(--c-surface);border:1px solid var(--c-line);border-radius:11px;padding:12px 14px;font-size:13px}
.input{height:44px}
.textarea{height:130px}
.form,.card{background:white;border:1px solid var(--c-line);border-radius:14px}
.card{padding:16px}
.form{display:flex;flex-direction:column;gap:12px;padding:18px}
.switch-row{display:flex;align-items:center;justify-content:space-between;font-size:13px}
.chips{display:flex;flex-wrap:wrap;gap:8px}
.chips button{margin:0;font-size:12px;background:var(--c-primary-soft);color:var(--c-primary);border-radius:11px}
.chips .primary{color:white}
.list{display:flex;flex-direction:column;gap:12px}
.row-head{display:flex;align-items:center;gap:12px;justify-content:space-between}
.head-main{flex:1;min-width:0}
.row-title{font-size:15px;font-weight:600;overflow-wrap:anywhere}
.meta{display:block;font-size:11px;color:var(--c-muted);margin-top:4px}
.tags{display:flex;gap:6px;flex-shrink:0}
.tag{font-size:11px;padding:3px 9px;border-radius:9px;background:var(--c-surface-2);color:var(--c-ink-2)}
.tag-open{background:var(--c-sage-soft);color:var(--c-sage)}
.tag-private{background:var(--c-sand-soft);color:var(--c-sand)}
.caret{font-size:11px;color:var(--c-primary);flex-shrink:0}
.detail{margin-top:12px;border-top:1px solid var(--c-surface-2);padding-top:12px;display:flex;flex-direction:column;gap:10px}
.body{font-size:13px;line-height:1.9;white-space:pre-wrap}
.audit{background:var(--c-surface-2);border-radius:11px;padding:10px 12px}
.audit-title{font-size:12px;font-weight:600}
.audit-row{display:flex;justify-content:space-between;gap:10px;font-size:11px;color:var(--c-ink-2);padding:3px 0}
.actions{display:flex;gap:8px}
.actions button{margin:0;font-size:12px}
.danger{background:var(--c-danger-soft);color:var(--c-danger)}
.state{text-align:center;color:var(--c-muted);font-size:13px;padding:26px 10px}
.state button{margin-top:8px;font-size:11px}
.summary-toggle{margin:4px 0}
.summary{margin-top:10px;display:flex;flex-direction:column;gap:12px}
.summary-title{font-size:13px;font-weight:700;color:var(--c-ink)}
.state-inline{font-size:12px;color:var(--c-muted)}
.chart{display:flex;align-items:flex-end;gap:2px;height:64px}
.bar-slot{flex:1;height:100%;display:flex;align-items:flex-end}
.bar{width:100%;min-height:2px;background:var(--c-primary);border-radius:2px 2px 0 0;opacity:.85}
.axis-note{font-size:10px;color:var(--c-muted)}
.ranking{display:flex;flex-direction:column;gap:7px}
.rank-row{display:flex;align-items:center;gap:10px}
.rank-name{width:72px;font-size:12px;color:var(--c-ink-2);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.rank-track{flex:1;height:10px;background:var(--c-primary-soft);border-radius:5px;overflow:hidden}
.rank-bar{height:100%;background:var(--c-primary);border-radius:5px}
.rank-count{min-width:26px;text-align:right;font-size:12px;font-weight:700;color:var(--c-primary)}
.actions-line{display:flex;gap:8px;flex-wrap:wrap}
</style>
