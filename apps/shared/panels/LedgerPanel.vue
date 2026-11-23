<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
// 共享面板：鉴权请求由宿主应用通过 @app 别名注入（apps/*/src/utils/request.js）。
import { request } from "@app/utils/request";
import { localDate } from "../tasks.mjs";
import { pageQuery } from "../paged-list.mjs";
import { usePagedList } from "../use-paged-list.js";
import ListMore from "../ListMore.vue";
import {
  LEDGER_CATEGORIES,
  evenSplit,
  formatYuan,
  splitSummary,
  toCents,
  validateLedgerForm
} from "../ledger-split.mjs";

const emit = defineEmits(["session-ended"]);
const today = localDate();
const month = ref(today.slice(0, 7));
const filterCategory = ref("ALL");
const filterPayer = ref(null);
const keyword = ref("");
const categoryFilters = ["ALL", ...LEDGER_CATEGORIES];

function listPath() {
  const params = [];
  if (month.value) params.push("month=" + encodeURIComponent(month.value));
  if (filterCategory.value !== "ALL") params.push("category=" + encodeURIComponent(filterCategory.value));
  if (filterPayer.value) params.push("payerId=" + filterPayer.value);
  const text = keyword.value.trim();
  if (text) params.push("keyword=" + encodeURIComponent(text));
  return "/ledger/entries" + (params.length ? "?" + params.join("&") : "");
}

const {
  items: entries,
  loading,
  loadingMore,
  exhausted,
  error: listError,
  refresh: refreshEntries,
  reload: reloadEntries,
  loadMore,
  reset: resetEntries
} = usePagedList((page, size) => request(pageQuery(listPath(), page, size)));

const members = ref([]), session = ref(null), summary = ref(null);
const errorMessage = ref(""), summaryError = ref(""), saving = ref(false), deleting = ref(null);
const creating = ref(false), editingId = ref(null);
const memberNames = computed(() => Object.fromEntries(members.value.map(m => [m.id, m.displayName])));
const payerOptions = computed(() => [{ id: null, displayName: "全部垫付人" }, ...members.value]);
const selectedPayerName = computed(
  () => payerOptions.value.find(m => m.id === filterPayer.value)?.displayName || "全部垫付人"
);
const selectedPayer = computed(
  () => members.value.find(m => m.id === form.value.payerId)?.displayName || "请选择"
);
const selectedFilterCategory = computed(() => (filterCategory.value === "ALL" ? "全部" : filterCategory.value));

function report(error) {
  if (!error) return;
  if (error.status === 401) emit("session-ended");
  uni.showToast({ title: error.message || "请求失败，请重试", icon: "none" });
}

async function refreshList() {
  const ok = await refreshEntries();
  if (!ok) {
    errorMessage.value = listError.value?.message || "账本加载失败，请重试";
    report(listError.value);
  }
  return ok;
}

async function loadSummary() {
  summaryError.value = "";
  try {
    // 汇总永远走服务端独立接口，不能拿当前这一页在本地加总。
    summary.value = await request("/ledger/summary?month=" + encodeURIComponent(month.value));
  } catch (error) {
    summary.value = null;
    summaryError.value = error.message || "月度汇总加载失败";
    report(error);
  }
}

async function load() {
  errorMessage.value = "";
  const identity = request("/auth/session").then(value => ({ value })).catch(error => ({ error }));
  const roster = request("/users/members").then(value => ({ value })).catch(error => ({ error }));
  const [ok, idResult, rosterResult] = await Promise.all([refreshList(), identity, roster, loadSummary()]);
  if (idResult.value) session.value = idResult.value;
  else if (idResult.error) report(idResult.error);
  if (rosterResult.value) members.value = rosterResult.value;
  else if (rosterResult.error) report(rosterResult.error);
  return ok;
}

/** 实时刷新：保留当前页码与筛选条件，汇总也一并重算。 */
async function sync() {
  if (!(await reloadEntries())) report(listError.value);
  await loadSummary();
}

let keywordTimer = null;
watch(keyword, () => {
  clearTimeout(keywordTimer);
  keywordTimer = setTimeout(refreshList, 300);
});
watch([month, filterCategory, filterPayer], () => {
  clearTimeout(keywordTimer);
  refreshList();
  loadSummary();
});

function newForm() {
  return {
    type: "EXPENSE",
    amountText: "",
    category: "餐饮",
    note: "",
    occurredOn: today,
    payerId: members.value[0]?.id ?? null
  };
}
const form = ref(newForm());
const splitMode = ref("even");
const splitIds = ref([]);
const splitAmounts = ref({});

const amountCents = computed(() => toCents(form.value.amountText));
const currentSplits = computed(() => {
  if (form.value.type === "INCOME" || !splitIds.value.length) return [];
  if (splitMode.value === "even") return evenSplit(amountCents.value || 0, splitIds.value);
  return splitIds.value.map(id => ({ memberId: id, amountCents: toCents(splitAmounts.value[id]) }));
});
const payload = computed(() => ({
  type: form.value.type,
  amountCents: amountCents.value,
  category: form.value.category,
  note: form.value.note.trim(),
  occurredOn: form.value.occurredOn,
  payerId: form.value.payerId,
  splits: currentSplits.value
}));
const check = computed(() =>
  validateLedgerForm({ ...payload.value, activeMemberIds: members.value.map(m => m.id) })
);

function resetAverageAmounts() {
  const rows = evenSplit(amountCents.value || 0, splitIds.value);
  splitAmounts.value = Object.fromEntries(rows.map(r => [r.memberId, formatYuan(r.amountCents)]));
}
watch(() => form.value.type, type => {
  if (type === "INCOME") splitIds.value = [];
  else if (!splitIds.value.length) splitIds.value = members.value.map(m => m.id);
});
watch(() => form.value.amountText, () => {
  if (splitMode.value === "even") resetAverageAmounts();
});
watch(splitIds, () => {
  if (splitMode.value === "even") resetAverageAmounts();
});

function closeForm() {
  creating.value = false;
  editingId.value = null;
  form.value = newForm();
  splitIds.value = [];
  splitAmounts.value = {};
}

function openCreate() {
  if (saving.value) return;
  if (creating.value && !editingId.value) return closeForm();
  editingId.value = null;
  form.value = newForm();
  splitMode.value = "even";
  splitIds.value = members.value.map(m => m.id);
  resetAverageAmounts();
  creating.value = true;
}

function openEdit(entry) {
  if (saving.value) return;
  editingId.value = entry.id;
  form.value = {
    type: entry.type,
    amountText: formatYuan(entry.amountCents),
    category: entry.category,
    note: entry.note || "",
    occurredOn: entry.occurredOn,
    payerId: entry.payerId
  };
  splitIds.value = (entry.splits || []).map(s => s.memberId);
  splitAmounts.value = Object.fromEntries(
    (entry.splits || []).map(s => [s.memberId, formatYuan(s.amountCents)])
  );
  splitMode.value = "custom";
  creating.value = true;
}

function toggleSplit(id) {
  const next = [...splitIds.value];
  const at = next.indexOf(id);
  if (at >= 0) next.splice(at, 1);
  else next.push(id);
  splitIds.value = next;
}

function canManage(entry) {
  return !!session.value && (session.value.role === "ADMIN" || entry.createdBy === session.value.userId);
}

async function submit() {
  if (saving.value) return;
  if (!check.value.ok) return uni.showToast({ title: check.value.errors[0], icon: "none" });
  saving.value = true;
  try {
    const id = editingId.value;
    if (id) await request("/ledger/entries/" + id, { method: "PUT", data: payload.value });
    else await request("/ledger/entries", { method: "POST", data: payload.value });
    closeForm();
    uni.showToast({ title: id ? "账目已更新" : "已记账", icon: "none" });
    await load();
  } catch (error) {
    report(error);
  } finally {
    saving.value = false;
  }
}

async function remove(entry) {
  if (deleting.value !== null) return;
  const confirm = await new Promise(resolve =>
    uni.showModal({
      title: "删除这笔账",
      content: "删除后家庭汇总会同步重算，确定继续吗？",
      confirmText: "删除",
      confirmColor: "#b4563f",
      success: res => resolve(!!res.confirm),
      fail: () => resolve(false)
    })
  );
  if (!confirm) return;
  deleting.value = entry.id;
  try {
    await request("/ledger/entries/" + entry.id, { method: "DELETE" });
    uni.showToast({ title: "已删除", icon: "none" });
    if (editingId.value === entry.id) closeForm();
    await load();
  } catch (error) {
    report(error);
  } finally {
    deleting.value = null;
  }
}

onMounted(load);
onUnmounted(() => {
  clearTimeout(keywordTimer);
  resetEntries();
});
defineExpose({ load, sync });
</script>

<template>
  <view class="ledger-panel">
    <view class="toolbar">
      <text class="section-title">家庭账本</text>
      <button size="mini" :disabled="saving" @click="openCreate">{{ creating && !editingId ? "收起" : "记一笔" }}</button>
      <button size="mini" :disabled="loading" @click="load">刷新</button>
    </view>

    <view v-if="creating" class="card form">
      <text class="form-title">{{ editingId ? "编辑账目" : "记一笔" }}</text>
      <view class="segmented">
        <text :class="['seg', { on: form.type === 'EXPENSE' }]" @click="form.type = 'EXPENSE'">支出</text>
        <text :class="['seg', { on: form.type === 'INCOME' }]" @click="form.type = 'INCOME'">收入</text>
      </view>
      <input v-model="form.amountText" class="input" type="text" placeholder="金额（元，例如 0.10）" :disabled="saving" />
      <picker mode="selector" :range="LEDGER_CATEGORIES" :disabled="saving" @change="form.category = LEDGER_CATEGORIES[Number($event.detail.value)]">
        <view class="picker">分类：{{ form.category }}</view>
      </picker>
      <picker mode="date" :value="form.occurredOn" :disabled="saving" @change="form.occurredOn = $event.detail.value">
        <view class="picker">发生日期：{{ form.occurredOn }}</view>
      </picker>
      <picker mode="selector" :range="members" range-key="displayName" :disabled="saving" @change="form.payerId = members[Number($event.detail.value)]?.id">
        <view class="picker">{{ form.type === 'EXPENSE' ? '垫付人' : '收款人' }}：{{ selectedPayer }}</view>
      </picker>
      <input v-model="form.note" class="input" maxlength="200" placeholder="备注（选填）" :disabled="saving" />

      <view v-if="form.type === 'EXPENSE'" class="split-box">
        <view class="split-head">
          <text class="form-title">分摊人</text>
          <view class="segmented compact">
            <text :class="['seg', { on: splitMode === 'even' }]" @click="splitMode = 'even'; resetAverageAmounts()">平均</text>
            <text :class="['seg', { on: splitMode === 'custom' }]" @click="splitMode = 'custom'">指定金额</text>
          </view>
        </view>
        <view class="member-chips">
          <text
            v-for="m in members"
            :key="m.id"
            :class="['chip', { on: splitIds.includes(m.id) }]"
            @click="toggleSplit(m.id)"
          >{{ m.displayName }}</text>
        </view>
        <view v-for="m in members.filter(x => splitIds.includes(x.id))" :key="'split-' + m.id" class="split-row">
          <text>{{ m.displayName }}</text>
          <input
            v-if="splitMode === 'custom'"
            v-model="splitAmounts[m.id]"
            class="input small"
            type="text"
            placeholder="金额（元）"
            :disabled="saving"
          />
          <text v-else class="split-amount">¥{{ formatYuan(toCents(splitAmounts[m.id])) }}</text>
        </view>
        <text class="hint">分摊合计：{{ check.sum || 0 }} 分 / 账单：{{ amountCents || 0 }} 分</text>
      </view>

      <text v-if="!check.ok" class="error-text">{{ check.errors[0] }}</text>
      <button class="primary" :loading="saving" :disabled="saving" @click="submit">{{ editingId ? "保存修改" : "保存账目" }}</button>
      <button v-if="editingId" size="mini" :disabled="saving" @click="closeForm">取消编辑</button>
    </view>

    <view class="card summary">
      <view class="summary-head">
        <text class="form-title">本月收支</text>
        <input v-model="month" class="input month" maxlength="7" placeholder="yyyy-MM" />
      </view>
      <text v-if="summaryError" class="error-text">{{ summaryError }}</text>
      <template v-else-if="summary">
        <view class="summary-total">
          <view><text class="metric-label">收入</text><text class="metric income">¥{{ formatYuan(summary.incomeCents) }}</text></view>
          <view><text class="metric-label">支出</text><text class="metric expense">¥{{ formatYuan(summary.expenseCents) }}</text></view>
        </view>
        <text class="hint">以下净额由服务端按整月账目计算：正数为该收，负数为该付。</text>
        <view v-for="m in summary.members" :key="m.memberId" class="net-row">
          <text>{{ m.displayName }}</text>
          <text :class="['net', { positive: m.netCents > 0, negative: m.netCents < 0 }]">
            {{ m.netCents > 0 ? '该收' : m.netCents < 0 ? '该付' : '持平' }} ¥{{ formatYuan(Math.abs(m.netCents)) }}
          </text>
        </view>
      </template>
    </view>

    <view class="filters">
      <picker mode="selector" :range="categoryFilters" :disabled="loading" @change="filterCategory = categoryFilters[Number($event.detail.value)]">
        <view class="picker">分类：{{ selectedFilterCategory }}</view>
      </picker>
      <picker mode="selector" :range="payerOptions" range-key="displayName" :disabled="loading" @change="filterPayer = payerOptions[Number($event.detail.value)]?.id">
        <view class="picker">{{ selectedPayerName }}</view>
      </picker>
    </view>
    <input v-model="keyword" class="input" placeholder="搜索备注或分类（服务端筛选）" />

    <view v-if="errorMessage" class="error"><text>{{ errorMessage }}</text><button size="mini" @click="load">重新加载</button></view>
    <view v-else-if="loading" class="empty">加载中…</view>
    <view v-else-if="!entries.length" class="empty">还没有账目，点「记一笔」开始</view>
    <view v-else class="entry-list">
      <view v-for="entry in entries" :key="entry.id" class="card entry">
        <view class="entry-head">
          <text class="entry-title">{{ entry.category }} · {{ entry.type === 'INCOME' ? '收入' : '支出' }}</text>
          <text :class="['entry-amount', entry.type]">{{ entry.type === 'INCOME' ? '+' : '-' }}¥{{ formatYuan(entry.amountCents) }}</text>
        </view>
        <text class="meta">{{ entry.occurredOn }} · {{ entry.type === 'EXPENSE' ? '垫付' : '收款' }}：{{ memberNames[entry.payerId] || '成员' }}</text>
        <text v-if="entry.note" class="meta">备注：{{ entry.note }}</text>
        <text class="meta">分摊：{{ splitSummary(entry.splits, memberNames) }}</text>
        <view v-if="canManage(entry)" class="entry-actions">
          <button size="mini" :disabled="saving || deleting !== null" @click="openEdit(entry)">编辑</button>
          <button size="mini" class="danger" :disabled="saving || deleting !== null" @click="remove(entry)">{{ deleting === entry.id ? '删除中…' : '删除' }}</button>
        </view>
      </view>
    </view>
    <ListMore :count="entries.length" :loading="loadingMore" :exhausted="exhausted" :has-error="!!listError" @more="loadMore" />
  </view>
</template>

<style scoped>
.ledger-panel,.entry-list,.form{display:flex;flex-direction:column;gap:24rpx}
.toolbar{display:flex;align-items:center;gap:16rpx}.toolbar button{margin:0}.section-title{flex:1;font-size:32rpx;font-weight:600}
.card{display:flex;flex-direction:column;gap:16rpx;padding:28rpx;background:rgba(255,255,255,.54);border:1px solid rgba(255,255,255,.8);border-radius:30px;box-shadow:0 10px 30px rgba(134,187,255,.22);backdrop-filter:blur(20px)}
.input,.picker{padding:22rpx;border:1px solid rgba(255,255,255,.8);border-radius:30px;background:rgba(255,255,255,.72);font-size:28rpx;color:#142B53}.input{height:88rpx;box-sizing:border-box}.input.small{height:64rpx;flex:1;font-size:24rpx}
.form-title{font-size:28rpx;font-weight:650}.meta,.hint{font-size:24rpx;color:var(--family-muted)}.hint{line-height:1.6}
.segmented{display:flex;gap:12rpx}.seg{padding:12rpx 28rpx;border-radius:30px;background:rgba(255,255,255,.6);color:#4F7DC6;font-size:26rpx}.seg.on{background:#4F7DC6;color:#F4F7FF}.segmented.compact .seg{padding:8rpx 20rpx;font-size:22rpx}
.split-box{display:flex;flex-direction:column;gap:14rpx;padding:20rpx;border-radius:24rpx;background:rgba(255,255,255,.38)}.split-head{display:flex;align-items:center;justify-content:space-between}
.member-chips{display:flex;flex-wrap:wrap;gap:12rpx}.chip{padding:10rpx 24rpx;border-radius:30px;border:1px solid rgba(255,255,255,.8);background:rgba(255,255,255,.6);color:#142B53;font-size:24rpx}.chip.on{background:#4F7DC6;color:#F4F7FF}
.split-row{display:flex;align-items:center;gap:16rpx;font-size:24rpx}.split-amount{margin-left:auto;color:#142B53}
.summary-head{display:flex;align-items:center;gap:16rpx}.summary-head .form-title{flex:1}.input.month{width:280rpx;height:72rpx;font-size:26rpx}
.summary-total{display:flex;gap:24rpx}.summary-total>view{display:flex;flex-direction:column;flex:1}.metric-label{font-size:22rpx;color:var(--family-muted)}.metric{font-size:36rpx;font-weight:650}.metric.income{color:var(--c-income)}.metric.expense{color:var(--c-expense)}
.net-row{display:flex;justify-content:space-between;font-size:26rpx}.net.positive{color:var(--c-income)}.net.negative{color:var(--c-expense)}
.filters{display:flex;gap:12rpx;flex-wrap:wrap}.filters .picker{padding:14rpx 24rpx;font-size:24rpx}
.entry-head{display:flex;justify-content:space-between;align-items:center;gap:16rpx}.entry-title{font-weight:650}.entry-amount{font-size:34rpx;font-weight:650}.entry-amount.expense{color:var(--c-expense)}.entry-amount.income{color:var(--c-income)}
.entry-actions{display:flex;gap:12rpx}.entry-actions button{margin:0}.danger{color:var(--c-danger)}
.primary,.error button{align-self:flex-start;margin:0;padding:0 30rpx;height:64rpx;line-height:64rpx;border-radius:30px;font-size:24rpx;background:#4F7DC6;color:#F4F7FF;border-color:rgba(255,255,255,.8);box-shadow:0 8px 20px rgba(134,187,255,.45)}
.form .primary{align-self:stretch;height:80rpx;line-height:80rpx;font-size:28rpx}
.error-text,.error{color:var(--c-danger)}.error-text{font-size:24rpx}.empty,.error{padding:36rpx 0;text-align:center}
</style>
