<script setup>
import { computed, onBeforeUnmount, ref } from "vue";
import { request } from "@app/utils/request";
import { pageQuery } from "../paged-list.mjs";
import { usePagedList } from "../use-paged-list.js";
import ListMore from "../ListMore.vue";
import { callDurationText, callOutcome, isCallCaller, isMissedCall } from "../call-history.mjs";

const emit = defineEmits(["close", "session-ended"]);

const visible = ref(false);
const selected = ref(null);
const detailPending = ref(false);
const detailNote = ref("");
const selfId = ref(null);
const members = ref([]);

// 纪要：转写是后台任务，详情页轮询到 READY/FAILED 为止
const minutes = ref(null);
const minutesLoading = ref(false);
const minutesError = ref("");
const showTranscript = ref(false);
let minutesTimer = null;
let minutesPolls = 0;

const memberMap = computed(() =>
  Object.fromEntries(members.value.map(item => [String(item.id), item.displayName]))
);
const {
  items: rows,
  loading,
  loadingMore,
  exhausted,
  error: listError,
  refresh,
  loadMore
} = usePagedList((page, size) => request(pageQuery("/media/calls/history", page, size)), {
  keyOf: item => item?.id
});
const error = computed(() => listError.value?.message || "");

function nameOf(id) {
  return memberMap.value[String(id)] || "家人";
}
function peers(call) {
  if (!call) return "";
  const others = (call.participants || [])
    .filter(item => String(item.userId) !== String(selfId.value))
    .map(item => nameOf(item.userId));
  return [...new Set(others)].join("、") || (isCallCaller(call, selfId.value) ? "对方" : "家人");
}
function startText(call) {
  return call?.createdAt ? String(call.createdAt).replace("T", " ").slice(0, 16) : "未知";
}
function duration(call) {
  return callDurationText(call);
}
function outcome(call) {
  return callOutcome(call, selfId.value);
}
function missed(call) {
  return isMissedCall(call, selfId.value);
}
function caller(call) {
  return isCallCaller(call, selfId.value);
}
function kindText(call) {
  return call?.kind === "VIDEO" ? "视频通话" : "语音通话";
}
function stateText(state) {
  return { JOINED: "已接听", INVITED: "邀请中", MISSED: "未接", REJECTED: "已拒绝", LEFT: "已离开" }[state] || state || "未知";
}
function reasonText(reason) {
  return { HANGUP: "主动挂断", NO_ANSWER: "无人接听", REJECTED: "已拒绝", LEFT: "中途离开", BUSY: "对方忙线", ERROR: "连接异常" }[reason] || reason || "—";
}

/** 身份与成员只影响姓名/主叫文案，失败时降级显示「家人」，不能挡住历史列表。 */
async function loadIdentity() {
  const [sessionResult, memberResult] = await Promise.all([
    request("/auth/session").then(value => ({ value })).catch(error => ({ error })),
    request("/users/members").then(value => ({ value })).catch(error => ({ error }))
  ]);
  if (sessionResult.value?.userId != null) selfId.value = sessionResult.value.userId;
  if (Array.isArray(memberResult.value)) members.value = memberResult.value;
}

async function load() {
  const ok = await refresh();
  if (!ok && listError.value?.status === 401) emit("session-ended");
  // 列表错误留在面板里展示；空数组是正常的「暂无记录」，绝不弹「请求失败」。
}

async function loadNext() {
  const ok = await loadMore();
  if (!ok && listError.value?.status === 401) emit("session-ended");
}

function open() {
  visible.value = true;
  selected.value = null;
  detailNote.value = "";
  loadIdentity();
  load();
}
function close() {
  visible.value = false;
  selected.value = null;
  resetMinutes();
}

function backToList() {
  selected.value = null;
  resetMinutes();
}

onBeforeUnmount(() => clearTimeout(minutesTimer));

/** 点列表项先看列表里已有的完整快照，再向详情接口取一次最新状态；刷新失败也能看到内容。 */
async function openDetail(call) {
  selected.value = call;
  detailNote.value = "";
  detailPending.value = true;
  resetMinutes();
  try {
    const fresh = await request("/media/calls/" + call.id);
    if (fresh) selected.value = fresh;
  } catch (e) {
    detailNote.value = e?.status === 401 ? "登录已过期，请重新进入" : "详情刷新失败，显示列表中的记录";
    if (e?.status === 401) emit("session-ended");
  } finally {
    detailPending.value = false;
  }
  loadMinutes(call.id);
}

function resetMinutes() {
  clearTimeout(minutesTimer);
  minutesTimer = null;
  minutesPolls = 0;
  minutes.value = null;
  minutesError.value = "";
  minutesLoading.value = false;
  showTranscript.value = false;
}

function minutesStatusText(status) {
  if (status === "UPLOADED") return "录音已提交，正在排队转写…";
  if (status === "TRANSCRIBING") return "正在转写通话内容并整理纪要…";
  return "";
}

/** 读取这通电话的纪要；仍在转写时每 4 秒再看一次，最多约 1 分钟。 */
async function loadMinutes(callId) {
  if (!callId) return;
  minutesLoading.value = true;
  try {
    const found = await request("/media/calls/" + callId + "/recording");
    minutes.value = found || null;
    minutesError.value = "";
    const pending = found && ["UPLOADED", "TRANSCRIBING"].includes(found.status);
    if (pending && minutesPolls < 15) {
      minutesPolls += 1;
      clearTimeout(minutesTimer);
      minutesTimer = setTimeout(() => loadMinutes(callId), 4000);
    }
  } catch (e) {
    minutesError.value = e?.status === 401
      ? "登录已过期，请重新进入"
      : e?.message || "纪要读取失败";
    if (e?.status === 401) emit("session-ended");
  } finally {
    minutesLoading.value = false;
  }
}

/** 失败重试只重新排队转写，录音文件还在，不需要重录。 */
async function retryMinutes() {
  const target = minutes.value;
  if (!target?.id) return;
  try {
    minutes.value = await request("/media/recordings/" + target.id + "/retry", { method: "POST" });
    minutesPolls = 0;
    loadMinutes(target.callId);
  } catch (e) {
    minutesError.value = e?.message || "重试失败";
  }
}

defineExpose({ open, close, load });
</script>

<template>
  <view v-if="visible" class="history-layer">
    <view class="history-mask" @click="close"></view>
    <view class="history-card">
      <template v-if="!selected">
        <view class="head">
          <text class="title">通话记录</text>
          <view class="head-actions">
            <button size="mini" :disabled="loading" @click="load">刷新</button>
            <button size="mini" @click="close">关闭</button>
          </view>
        </view>
        <text class="hint">最近 30 通 · 点一条查看详情</text>

        <view v-if="error" class="state">
          <text>{{ error }}</text>
          <button size="mini" @click="load">重试</button>
        </view>
        <view v-else-if="loading && !rows.length" class="state">正在加载通话记录…</view>
        <view v-else-if="!rows.length" class="state empty-state">
          <view class="empty-illustration">
            <text class="empty-icon">☎</text>
          </view>
          <text class="empty-title">还没有通话记录</text>
          <text class="hint">和家人通过一次电话后，记录会显示在这里。</text>
        </view>
        <view v-else class="list">
          <view v-for="item in rows" :key="item.id" :class="['row', { 'row-missed': missed(item) }]" @click="openDetail(item)">
            <view class="row-main">
              <text :class="['row-title', { missed: missed(item) }]">{{ peers(item) }}</text>
              <text class="row-meta">{{ startText(item) }} · 时长 {{ duration(item) }}</text>
            </view>
            <view class="row-side">
              <view class="tags">
                <text v-if="caller(item)" class="tag">主叫</text>
                <text v-if="missed(item)" class="tag tag-missed">未接</text>
                <text v-else class="tag">{{ outcome(item) }}</text>
                <text class="tag tag-kind">{{ kindText(item) }}</text>
              </view>
              <text class="caret">详情 ›</text>
            </view>
          </view>
        </view>
        <ListMore :count="rows.length" :loading="loadingMore" :exhausted="exhausted" :has-error="!!listError" @more="loadNext" />
      </template>

      <template v-else>
        <view class="head">
          <button size="mini" @click="backToList">‹ 返回列表</button>
          <text class="title">通话详情</text>
        </view>
        <view class="detail">
          <view class="detail-row"><text class="detail-label">对方 / 参与人</text><text class="detail-value">{{ peers(selected) }}</text></view>
          <view class="detail-row"><text class="detail-label">我方角色</text><text class="detail-value">{{ caller(selected) ? '主叫' : '被叫' }}</text></view>
          <view class="detail-row"><text class="detail-label">结果</text><text :class="['detail-value', { missed: missed(selected) }]">{{ outcome(selected) }}</text></view>
          <view class="detail-row"><text class="detail-label">开始时间</text><text class="detail-value">{{ startText(selected) }}</text></view>
          <view class="detail-row"><text class="detail-label">时长</text><text class="detail-value">{{ duration(selected) }}</text></view>
          <view class="detail-row"><text class="detail-label">通话方式</text><text class="detail-value">{{ kindText(selected) }}</text></view>
          <view class="detail-row"><text class="detail-label">结束原因</text><text class="detail-value">{{ reasonText(selected.endReason) }}</text></view>
          <view class="people">
            <text class="detail-label">参与者</text>
            <view v-for="person in selected.participants || []" :key="person.userId" class="person">
              <text class="person-name">{{ String(person.userId) === String(selfId) ? '我' : nameOf(person.userId) }}</text>
              <text :class="['person-state', { missed: person.state === 'MISSED' }]">{{ stateText(person.state) }}</text>
            </view>
          </view>

          <!-- AI 纪要：录音由通话方本机完成并上传，这里只读结果 -->
          <view class="minutes">
            <view class="minutes-head">
              <text class="detail-label">AI 通话纪要</text>
              <button
                v-if="minutes?.status === 'FAILED'"
                size="mini"
                :disabled="minutesLoading"
                @click="retryMinutes"
              >重新生成</button>
            </view>

            <text v-if="minutesLoading && !minutes" class="hint">正在读取纪要…</text>
            <text v-else-if="!minutes" class="hint">这通电话没有开启录音，所以没有纪要。通话中点「通话纪要」即可开启。</text>
            <template v-else>
              <text v-if="minutes.status !== 'READY'" class="hint">{{ minutesStatusText(minutes.status) }}</text>
              <text v-if="minutes.status === 'FAILED'" class="minutes-error">{{ minutes.error || '生成失败，可点右上角重新生成' }}</text>
              <text v-if="minutes.status === 'READY'" class="minutes-body">{{ minutes.minutes }}</text>
              <view v-if="minutes.transcript" class="transcript">
                <text class="transcript-toggle" @click="showTranscript = !showTranscript">
                  {{ showTranscript ? '收起转写原文' : '查看转写原文' }}
                </text>
                <text v-if="showTranscript" class="minutes-body">{{ minutes.transcript }}</text>
              </view>
            </template>
            <text v-if="minutesError" class="hint">{{ minutesError }}</text>
          </view>
        </view>
        <text v-if="detailPending" class="hint">正在刷新详情…</text>
        <text v-if="detailNote" class="hint">{{ detailNote }}</text>
      </template>
    </view>
  </view>
</template>

<style scoped>
/* 弹层与毛玻璃遮罩；颜色走主题变量以同时适配家庭端蓝、移动端绿 */
.history-layer{position:fixed;inset:0;z-index:880;display:flex;align-items:center;justify-content:center;padding:20px;box-sizing:border-box}
.history-mask{position:absolute;inset:0;background:rgba(24,42,74,.36);backdrop-filter:blur(6px);-webkit-backdrop-filter:blur(6px)}
.history-card{position:relative;width:min(560px,100%);max-height:88vh;display:flex;flex-direction:column;gap:14px;padding:24px;background:rgba(255,255,255,.82);border:1px solid rgba(255,255,255,.85);border-radius:30px;box-shadow:0 24px 60px rgba(134,187,255,.28);box-sizing:border-box;overflow:auto;backdrop-filter:blur(20px);-webkit-backdrop-filter:blur(20px)}

/* 头部：标题 + 胶囊按钮 */
.head{display:flex;align-items:center;justify-content:space-between;gap:10px}
.title{font-size:18px;font-weight:650;color:var(--c-ink);letter-spacing:.01em}
.head-actions{display:flex;gap:8px}
.head button{margin:0;font-size:12px;line-height:1.5;padding:0 16px;height:32px;border-radius:30px;background:rgba(255,255,255,.62);color:var(--c-primary);border:1px solid rgba(255,255,255,.85)}
.head button:disabled{opacity:.5}
.hint{font-size:11px;color:var(--c-muted);line-height:1.7}

/* 加载 / 错误 / 空状态 */
.state{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:12px;padding:34px 12px;color:var(--c-muted);font-size:13px;text-align:center}
.state button{margin:0;font-size:12px;border-radius:30px;background:rgba(255,255,255,.62);color:var(--c-primary);border:1px solid rgba(255,255,255,.85)}
.empty-state{padding:40px 12px 44px}
.empty-illustration{width:72px;height:72px;border-radius:50%;display:flex;align-items:center;justify-content:center;background:rgba(255,255,255,.6);border:1px solid rgba(255,255,255,.85);box-shadow:0 10px 30px rgba(134,187,255,.22)}
.empty-icon{font-size:34px;line-height:1;color:var(--c-primary)}
.empty-title{font-size:14px;font-weight:600;color:var(--c-ink)}

/* 列表行：玻璃卡片，未接来电左侧红条 + 淡红底强调 */
.list{display:flex;flex-direction:column;gap:12px}
.row{position:relative;display:flex;align-items:center;justify-content:space-between;gap:14px;padding:16px 18px;border-radius:20px;background:rgba(255,255,255,.62);border:1px solid rgba(255,255,255,.85);box-shadow:0 8px 22px rgba(134,187,255,.16);cursor:pointer}
.row::before{content:"";position:absolute;left:0;top:14px;bottom:14px;width:3px;border-radius:3px;background:transparent}
.row-missed{background:linear-gradient(180deg,rgba(255,238,240,.86),rgba(255,247,248,.8))}
.row-missed::before{background:#d8566b}
.row-main{flex:1;min-width:0}
.row-title{display:block;font-size:15px;font-weight:600;color:var(--c-ink);overflow-wrap:anywhere}
.row-title.missed{color:#c24055}
.row-meta{display:block;margin-top:6px;font-size:11px;color:var(--c-muted)}
.row-side{display:flex;flex-direction:column;align-items:flex-end;gap:8px;flex-shrink:0}
.tags{display:flex;flex-wrap:wrap;justify-content:flex-end;gap:6px}
.tag{font-size:10px;line-height:1.6;padding:2px 9px;border-radius:10px;background:var(--c-primary-soft);color:var(--c-primary);white-space:nowrap}
.tag-kind{background:rgba(111,103,95,.1);color:var(--c-muted)}
.tag-missed{background:#d8566b;color:#fff}
.missed{color:#c24055}
.caret{font-size:11px;color:var(--c-primary)}

/* 详情：分段玻璃行，参与者单独成块 */
.detail{display:flex;flex-direction:column;gap:10px;padding:4px 0}
.detail-row{display:flex;justify-content:space-between;align-items:center;gap:16px;padding:11px 14px;border-radius:16px;background:rgba(255,255,255,.62);border:1px solid rgba(255,255,255,.85)}
.detail-label{font-size:12px;color:var(--c-muted);flex-shrink:0}
.detail-value{font-size:13px;font-weight:500;color:var(--c-ink);text-align:right;overflow-wrap:anywhere}
.people{display:flex;flex-direction:column;gap:8px;padding:12px 14px;border-radius:16px;background:rgba(255,255,255,.62);border:1px solid rgba(255,255,255,.85)}
.person{display:flex;justify-content:space-between;gap:12px;font-size:12px}
.person-name{color:var(--c-ink)}
.person-state{color:var(--c-muted)}
.person-state.missed{color:#c24055}

/* AI 纪要：与详情行同一种玻璃卡片，正文保留换行（模型输出的是 Markdown 纯文本） */
.minutes{display:flex;flex-direction:column;gap:10px;padding:14px 16px;border-radius:16px;background:rgba(255,255,255,.62);border:1px solid rgba(255,255,255,.85)}
.minutes-head{display:flex;align-items:center;justify-content:space-between;gap:10px}
.minutes-head button{margin:0;font-size:11px;line-height:1.5;padding:0 12px;height:26px;border-radius:20px;background:var(--c-primary-soft);color:var(--c-primary);border:none}
.minutes-body{display:block;font-size:12.5px;line-height:1.85;color:var(--c-ink);white-space:pre-wrap;overflow-wrap:anywhere}
.minutes-error{font-size:11.5px;line-height:1.7;color:#c24055}
.transcript{display:flex;flex-direction:column;gap:8px;padding-top:8px;border-top:1px dashed rgba(111,103,95,.18)}
.transcript-toggle{font-size:11.5px;color:var(--c-primary);cursor:pointer}
</style>
