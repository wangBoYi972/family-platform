<script setup>
import { computed, onMounted, ref } from "vue";
import { request } from "../utils/request";
import { pageQuery } from "../../../shared/paged-list.mjs";
import { usePagedList } from "../../../shared/use-paged-list.js";
import ListMore from "../../../shared/ListMore.vue";

const emit = defineEmits(["session-ended"]);
const categories = [{ value: "ALL", label: "全部" }, { value: "IDENTITY", label: "证件" }, { value: "HEALTH", label: "健康" }, { value: "DOCUMENT", label: "文档" }];
const categoryLabel = { IDENTITY: "证件", HEALTH: "健康", DOCUMENT: "文档" };
const {
  items: rows, loading, loadingMore, exhausted, error: listError,
  refresh, loadMore
} = usePagedList((page, size) => request(pageQuery("/archives", page, size)));
const members = ref([]), session = ref(null);
const error = computed(() => listError.value?.message || "");
const keyword = ref(""), filter = ref("ALL");
const creating = ref(false), saving = ref(false), opened = ref(new Set());
const details = ref({}), audits = ref({});
const form = ref({ id: "", title: "", category: "HEALTH", visibility: "FAMILY", body: "" });

const filtered = computed(() => rows.value.filter(item => {
  const matchesCategory = filter.value === "ALL" || item.category === filter.value;
  const query = keyword.value.trim().toLocaleLowerCase();
  return matchesCategory && (!query || (item.title + " " + (item.body || "")).toLocaleLowerCase().includes(query));
}));
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
defineExpose({ load });
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

    <view class="stats">
      <view class="stat"><text class="stat-value">{{ stats.total }}</text><text class="stat-label">档案总数</text></view>
      <view class="stat"><text class="stat-value">{{ stats.health }}</text><text class="stat-label">健康记录</text></view>
      <view class="stat"><text class="stat-value">{{ stats.shared }}</text><text class="stat-label">全家可见</text></view>
    </view>
    <text v-if="!exhausted && rows.length" class="hint">统计与筛选基于已加载的 {{ rows.length }} 条，继续加载可扩大范围</text>

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
    <view v-else-if="!filtered.length" class="state">{{ rows.length ? "没有匹配的档案，换个关键词试试" : "还没有档案，把体检单、疫苗记录或证件信息存进家庭保险箱。" }}</view>
    <view v-else class="list">
      <view v-for="item in filtered" :key="item.id" class="card">
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
</style>
