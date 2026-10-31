<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import { request, upload, loadImageUrl } from "@app/utils/request";

const props = defineProps({ peerId: { type: [Number, String], default: null } });
const emit = defineEmits(["session-ended", "unread-change", "call"]);

const conversations = ref([]), members = ref([]), session = ref(null);
const active = ref(""), activeTitle = ref(""), messages = ref([]), draft = ref("");
const loading = ref(false), listError = ref(""), detailError = ref("");
const sending = ref(false), starting = ref(false), ensuringGroup = ref(false);
const expanded = ref(false), receipts = ref([]), scrollTop = ref(0);
const previews = ref({});
let timer = null, generation = 0;

const memberMap = computed(() => Object.fromEntries(members.value.map(item => [String(item.id), item.displayName])));
const selfId = computed(() => String(session.value?.userId ?? ""));
const unreadTotal = computed(() => conversations.value.reduce((sum, item) => sum + (Number(item.unread) || 0), 0));
const lastMessageId = computed(() => messages.value.reduce((max, item) => Math.max(max, Number(item.id) || 0), 0));
const hasGroup = computed(() => conversations.value.some(item => item.kind === "GROUP"));
const ownLastId = computed(() => {
  const own = messages.value.filter(item => String(item.senderId) === selfId.value);
  return own.length ? Number(own[own.length - 1].id) || 0 : 0;
});
const readMembers = computed(() => ownLastId.value ? receipts.value.filter(item => Number(item.lastMessageId) >= ownLastId.value).map(item => memberName(item.userId)) : []);
/** 私聊会话里的对方 ID；群聊返回 null（群聊通话在顶部「通话」入口发起）。 */
const activePeerId = computed(() => {
  const item = conversations.value.find(entry => entry.id === active.value);
  if (!item || item.kind === "GROUP") return null;
  return String(item.userA) === selfId.value ? item.userB : item.userA;
});
watch(unreadTotal, value => emit("unread-change", value));

function uuid() {
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, char => {
    const value = (Math.random() * 16) | 0;
    return (char === "x" ? value : (value & 0x3) | 0x8).toString(16);
  });
}
function memberName(id) { return memberMap.value[String(id)] || "家人"; }
function conversationLabel(item) {
  if (item.kind === "GROUP") return item.title || "家庭群聊";
  const me = String(item.userA) === selfId.value ? item.userB : item.userA;
  return memberName(me);
}
function stamp(value) { return value ? String(value).slice(11, 16) : ""; }
function isOwn(item) { return String(item.senderId) === selfId.value; }
function report(error) {
  if (error.status === 401) emit("session-ended");
  uni.showToast({ title: error.message || "请求失败", icon: "none" });
}
async function cachePreview(id) {
  if (previews.value[id]) return;
  try { previews.value = { ...previews.value, [id]: await loadImageUrl(id) }; }
  catch { /* 单张图片加载失败不影响会话整体 */ }
}
function append(incoming) {
  const seen = new Set(messages.value.filter(item => item.clientId).map(item => item.clientId));
  const known = new Set(messages.value.map(item => String(item.id)));
  for (const item of incoming) {
    const duplicated = (item.clientId && seen.has(item.clientId)) || known.has(String(item.id));
    if (duplicated) continue;
    messages.value.push(item);
    if (item.kind === "IMAGE") cachePreview(item.body);
  }
  messages.value.sort((a, b) => Number(a.id) - Number(b.id));
}
async function toBottom() {
  await nextTick();
  scrollTop.value = 0;
  await nextTick();
  scrollTop.value = messages.value.length * 1000;
}

async function loadBase() {
  const [identity, list] = await Promise.all([request("/auth/session"), request("/users/members")]);
  session.value = identity;
  members.value = list.filter(item => String(item.id) !== String(identity.userId));
}
async function loadConversations() {
  conversations.value = await request("/im/conversations");
}
async function loadMessages() {
  const current = generation;
  detailError.value = "";
  try {
    const list = await request("/im/conversations/" + active.value + "/messages", { data: { after: 0 } });
    if (current !== generation) return;
    messages.value = list;
    messages.value.filter(item => item.kind === "IMAGE").forEach(item => cachePreview(item.body));
    await toBottom();
  } catch (error) {
    if (current === generation) detailError.value = error.message || "消息加载失败";
    report(error);
  }
}
async function loadReceipts() {
  try { receipts.value = await request("/im/conversations/" + active.value + "/read"); }
  catch { receipts.value = []; }
}
async function markRead() {
  const id = lastMessageId.value;
  if (!id || !active.value) return;
  try {
    await request("/im/conversations/" + active.value + "/read", { method: "POST", data: { messageId: id } });
    if (active.value) await loadReceipts();
  } catch { /* 已读回执失败不影响聊天 */ }
}
async function poll() {
  if (!active.value) return;
  try {
    const incoming = await request("/im/conversations/" + active.value + "/messages", { data: { after: lastMessageId.value } });
    if (incoming.length) {
      append(incoming);
      await toBottom();
      markRead();
      loadConversations().catch(() => {});
    } else {
      loadReceipts();
    }
  } catch { /* 轮询失败静默，等待下一次 */ }
}
function startPolling() {
  stopPolling();
  timer = setInterval(poll, 4000);
}
function stopPolling() {
  if (timer) clearInterval(timer);
  timer = null;
}
async function select(item) {
  if (item.id === active.value) return;
  generation++;
  active.value = item.id;
  activeTitle.value = conversationLabel(item);
  messages.value = [];
  previews.value = {};
  receipts.value = [];
  loading.value = true;
  startPolling();
  try { await loadMessages(); await markRead(); await loadReceipts(); }
  finally { loading.value = false; }
  loadConversations().catch(() => {});
}
async function openDirect(peerId) {
  const me = Number(session.value?.userId), peer = Number(peerId);
  if (!me || !peer) return;
  const id = "dm-" + session.value.householdId + "-" + Math.min(me, peer) + "-" + Math.max(me, peer);
  const existing = conversations.value.find(item => item.id === id);
  if (existing) return select(existing);
  starting.value = true;
  try {
    const created = await request("/im/conversations", { method: "POST", data: { peerId: peer } });
    await loadConversations();
    await select(conversations.value.find(item => item.id === created.id) || created);
  } catch (error) { report(error); }
  finally { starting.value = false; }
}
async function ensureGroup() {
  if (ensuringGroup.value) return;
  ensuringGroup.value = true;
  try {
    await request("/im/conversations", { method: "POST", data: {} });
    await loadConversations();
  } catch (error) { report(error); }
  finally { ensuringGroup.value = false; }
}
async function sendText() {
  const text = draft.value.trim();
  if (!text || sending.value || !active.value) return;
  sending.value = true;
  const clientId = uuid();
  try {
    const created = await request("/im/conversations/" + active.value + "/messages", { method: "POST", data: { clientId, kind: "TEXT", body: text } });
    draft.value = "";
    append([created]);
    await toBottom();
    markRead();
  } catch (error) { report(error); }
  finally { sending.value = false; }
}
async function sendImage() {
  if (sending.value || !active.value) return;
  sending.value = true;
  try {
    const result = await uni.chooseImage({ count: 1, sizeType: ["compressed"] });
    const path = result.tempFilePaths?.[0];
    if (!path) return;
    const file = await upload("/files", path, { visibility: "FAMILY" });
    const clientId = uuid();
    const created = await request("/im/conversations/" + active.value + "/messages", { method: "POST", data: { clientId, kind: "IMAGE", body: file.id } });
    append([created]);
    await cachePreview(file.id);
    await toBottom();
    markRead();
  } catch (error) {
    if (!String(error?.errMsg || "").includes("cancel")) report(error);
  } finally { sending.value = false; }
}
function previewImages(current) {
  const urls = messages.value.filter(item => item.kind === "IMAGE" && previews.value[item.body]).map(item => previews.value[item.body]);
  uni.previewImage({ urls, current });
}
async function load() {
  generation++;
  loading.value = true;
  listError.value = "";
  try { await Promise.all([loadBase(), loadConversations()]); }
  catch (error) {
    listError.value = error.message || "会话加载失败";
    report(error);
  } finally { loading.value = false; }
}
onMounted(async () => {
  await load();
  if (props.peerId && session.value) openDirect(props.peerId);
  else if (conversations.value.length) select(conversations.value[0]);
});
onUnmounted(() => { generation++; stopPolling(); });
defineExpose({ load });
</script>

<template>
  <view class="chat">
    <view class="chat-frame">
      <view class="sidebar">
        <view class="sidebar-head"><text class="title">家人聊天</text><button size="mini" :disabled="loading" @click="load">刷新</button></view>
        <view v-if="listError" class="state"><text>{{ listError }}</text><button size="mini" @click="load">重试</button></view>
        <view v-else-if="loading && !conversations.length" class="state">正在加载会话…</view>
        <view v-else-if="!conversations.length" class="state">还没有会话<text class="hint">发起家庭群聊，或点选家庭成员开始聊天。</text></view>
        <view v-else class="conv-list">
          <view v-for="item in conversations" :key="item.id" :class="['conv', { active: item.id === active }]" @click="select(item)">
            <view class="conv-avatar">{{ item.kind === 'GROUP' ? '家' : conversationLabel(item).slice(0, 1) }}</view>
            <view class="conv-body">
              <view class="conv-line"><text class="conv-name">{{ conversationLabel(item) }}</text><text v-if="item.unread" class="badge">{{ item.unread }}</text></view>
              <text class="conv-meta">{{ item.kind === 'GROUP' ? '全家都在 · ' + (members.length + 1) + ' 人' : '私聊 · 只有你们两人可见' }}</text>
            </view>
          </view>
        </view>
        <view class="sidebar-foot">
          <text class="hint">点选家人单独聊聊，或建立家庭群聊。</text>
          <view v-if="members.length">
            <text class="hint">向家人发起私聊：</text>
            <view class="chips"><button v-for="item in members" :key="item.id" size="mini" :disabled="starting" @click="openDirect(item.id)">{{ item.displayName }}</button></view>
          </view>
          <button v-if="!hasGroup" class="primary block" :loading="ensuringGroup" :disabled="ensuringGroup" @click="ensureGroup">创建家庭群聊</button>
        </view>
      </view>

      <view class="room">
        <view v-if="!active" class="state room-empty">
          <text class="title">选一个会话开始聊天</text>
          <text class="hint">消息、图片与已读状态都会在家人之间同步。</text>
        </view>
        <view v-else class="room-body">
          <view class="room-head">
            <view>
              <text class="title">{{ activeTitle }}</text>
              <text class="hint">{{ readMembers.length ? '已读：' + readMembers.join('、') : '每 4 秒自动获取新消息' }}</text>
            </view>
            <button v-if="activePeerId" class="call-btn" size="mini" @click="emit('call', activePeerId)">☎ 通话</button>
          </view>
          <scroll-view class="messages" scroll-y :scroll-top="scrollTop">
            <view v-if="detailError" class="state"><text>{{ detailError }}</text><button size="mini" @click="loadMessages">重试</button></view>
            <view v-else-if="loading && !messages.length" class="state">正在加载消息…</view>
            <view v-else-if="!messages.length" class="state">还没有消息<text class="hint">打个招呼吧，让家人知道你在。</text></view>
            <view v-for="item in messages" :key="item.id" :class="['row', { own: isOwn(item) }]">
              <view class="bubble">
                <text v-if="!isOwn(item)" class="sender">{{ memberName(item.senderId) }}</text>
                <image v-if="item.kind === 'IMAGE'" v-show="previews[item.body]" class="photo" :src="previews[item.body]" mode="widthFix" @click="previewImages(previews[item.body])" />
                <text v-if="item.kind === 'IMAGE' && !previews[item.body]" class="hint">图片加载中…</text>
                <text v-if="item.kind !== 'IMAGE'" class="body">{{ item.body }}</text>
                <text class="time">{{ stamp(item.createdAt) }}</text>
              </view>
            </view>
          </scroll-view>
          <view class="composer">
            <input v-model="draft" class="input" maxlength="4000" placeholder="说点什么给家人…" :disabled="sending" @confirm="sendText" />
            <button size="mini" :disabled="sending" @click="sendImage">图片</button>
            <button class="primary" size="mini" :loading="sending" :disabled="sending" @click="sendText">发送</button>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.chat{display:flex;flex-direction:column}
.chat-frame{display:grid;grid-template-columns:280px 1fr;gap:18px}
.sidebar,.room,.messages,.composer{display:flex;flex-direction:column}
.sidebar,.room{background:rgba(255,252,248,.82);border:1px solid var(--c-line);border-radius:22px;padding:18px;box-shadow:0 6px 24px rgba(58,52,46,.06)}
@supports (backdrop-filter: blur(18px)){.sidebar,.room{background:rgba(255,252,248,.62);backdrop-filter:blur(18px)}}
.sidebar{gap:14px;max-height:560px;overflow:auto}
.sidebar-head,.room-head{display:flex;align-items:flex-start;justify-content:space-between;gap:10px}
.sidebar-head{padding-bottom:12px;border-bottom:1px solid var(--c-surface-2)}
.title{font-size:16px;font-weight:650}
.hint{display:block;font-size:11px;color:var(--c-muted);margin-top:6px;line-height:1.8}
.sidebar-head button{margin:0;font-size:11px}
.call-btn{margin:0;font-size:11px;background:linear-gradient(135deg,var(--c-primary),var(--c-primary-deep,#a6603f));color:#fff;border-radius:11px;box-shadow:0 2px 8px rgba(166,96,63,.22)}
.conv-list{display:flex;flex-direction:column;gap:8px}
.conv{display:flex;align-items:center;gap:12px;padding:12px;border-radius:14px;background:var(--c-surface-2)}
.conv.active{background:var(--c-primary-soft);border:1px solid rgba(196,121,90,.35);box-shadow:0 2px 10px rgba(196,121,90,.12)}
.conv-avatar{width:40px;height:40px;border-radius:50%;background:linear-gradient(135deg,var(--c-primary),var(--c-primary-deep,#a6603f));color:#fff;text-align:center;line-height:40px;font-size:15px;flex-shrink:0;box-shadow:0 2px 8px rgba(196,121,90,.35)}
.conv-body{flex:1;min-width:0}
.conv-line{display:flex;align-items:center;justify-content:space-between;gap:8px}
.conv-name{font-size:14px;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.badge{background:var(--c-danger);color:white;font-size:10px;border-radius:999px;padding:2px 7px;flex-shrink:0}
.conv-meta{font-size:11px;color:var(--c-muted)}
.sidebar-foot{display:flex;flex-direction:column;gap:10px;border-top:1px solid var(--c-surface-2);padding-top:14px}
.chips{display:flex;flex-wrap:wrap;gap:6px;margin-top:4px}
.chips button{margin:0;font-size:11px;background:var(--c-primary-soft);color:var(--c-primary);border-radius:11px}
.primary,.primary.block{background:linear-gradient(135deg,var(--c-primary),var(--c-primary-deep,#a6603f));color:#fff;font-size:12px;margin:0;border-radius:12px;box-shadow:0 2px 8px rgba(166,96,63,.25)}
.block{width:100%;height:38px;line-height:38px}
.room{min-height:420px}
.room-body{flex:1;display:flex;flex-direction:column;gap:12px;min-height:0}
.room-empty{align-items:center;justify-content:center;padding:60px 0}
.messages{flex:1;min-height:280px;max-height:420px;background:var(--c-surface-2);border-radius:14px;padding:14px;gap:12px}
.row{display:flex}
.row.own{justify-content:flex-end}
.bubble{max-width:78%;background:#fff;border:1px solid var(--c-line);border-radius:16px 16px 16px 6px;padding:10px 14px;display:flex;flex-direction:column;gap:4px;box-shadow:0 1px 6px rgba(58,52,46,.05)}
.row.own .bubble{background:linear-gradient(135deg,var(--c-primary),var(--c-primary-deep,#a6603f));border-color:transparent;border-radius:16px 16px 6px 16px;color:#fff;box-shadow:0 3px 10px rgba(166,96,63,.28)}
.row.own .bubble .sender,.row.own .bubble .time{color:rgba(255,255,255,.75)}
.sender{font-size:11px;color:var(--c-ink-2)}
.body{font-size:14px;line-height:1.7;overflow-wrap:anywhere}
.photo{width:180px;border-radius:10px;margin:2px 0}
.time{font-size:10px;color:var(--c-muted);text-align:right}
.composer{flex-direction:row;align-items:center;gap:8px}
.input{flex:1;height:44px;background:var(--c-surface-2);border:1px solid var(--c-line);border-radius:11px;padding:0 14px;font-size:13px;box-sizing:border-box}
.composer button{margin:0;font-size:12px}
.state{text-align:center;color:var(--c-muted);font-size:13px;padding:26px 10px}
.state button{margin-top:8px;font-size:11px}
@media(max-width:760px){.chat-frame{grid-template-columns:1fr}.sidebar{max-height:none}.sidebar-foot{order:2}}
</style>
