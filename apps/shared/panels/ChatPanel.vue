<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import { request, upload, loadImageUrl, loadAudioUrl, loadFileUrl } from "@app/utils/request";
import { sumUnread } from "@shared/unread.mjs";
import { mergeReceipt, readMemberIds } from "@shared/receipts.mjs";
import { requestNotify, TEMPLATE_KEYS } from "@shared/wx-notify.mjs";

const props = defineProps({
  peerId: { type: [Number, String], default: null },
  /**
   * 兜底轮询间隔。实时通道连上时宿主传大值（推送负责及时性，轮询只防漏），
   * 没连上时回落到 4000——断线期间聊天不能变成「要手动刷新才能看到新消息」。
   */
  pollMs: { type: Number, default: 4000 },
  /** 实时通道是否连着。连着就不必为「谁已读」额外轮询，后端会推。 */
  live: { type: Boolean, default: false }
});
const emit = defineEmits(["session-ended", "unread-change", "call"]);

const conversations = ref([]), members = ref([]), session = ref(null);
const active = ref(""), activeTitle = ref(""), messages = ref([]), draft = ref("");
const loading = ref(false), listError = ref(""), detailError = ref("");
const sending = ref(false), starting = ref(false), ensuringGroup = ref(false);
const expanded = ref(false), receipts = ref([]), scrollTop = ref(0);
// 语音：audioUrls 缓存 fileId -> 本地临时路径（InnerAudioContext 不支持带鉴权头的 src，只能先下载）。
const audioUrls = ref({}), playingId = ref(""), recording = ref(false);
// 与后端 ImService.MESSAGE_PAGE 对齐：返回条数达到这个值就认为还有更早的消息。
const MESSAGE_PAGE = 100;
const hasEarlier = ref(false), loadingEarlier = ref(false), anchor = ref("");
const previews = ref({});
// 文件消息：元数据（文件名/大小）与已下载的本地路径分开缓存——
// 元数据每条消息都要显示，路径只在点开时才下载。
const fileMeta = ref({}), fileUrls = ref({}), downloadingId = ref("");
let timer = null, generation = 0;

const memberMap = computed(() => Object.fromEntries(members.value.map(item => [String(item.id), item.displayName])));
const selfId = computed(() => String(session.value?.userId ?? ""));
const unreadTotal = computed(() => sumUnread(conversations.value));
const lastMessageId = computed(() => messages.value.reduce((max, item) => Math.max(max, Number(item.id) || 0), 0));
const hasGroup = computed(() => conversations.value.some(item => item.kind === "GROUP"));
const ownLastId = computed(() => {
  const own = messages.value.filter(item => String(item.senderId) === selfId.value);
  return own.length ? Number(own[own.length - 1].id) || 0 : 0;
});
const readMembers = computed(() => readMemberIds(receipts.value, selfId.value, ownLastId.value).map(memberName));
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
/** 文件名/大小是气泡文案，进会话就要显示；拉不到就退化为「文件 · 点击下载」。 */
async function cacheFileMeta(id) {
  if (fileMeta.value[id]) return;
  try { fileMeta.value = { ...fileMeta.value, [id]: await request("/files/" + id + "/metadata") }; }
  catch { /* 元数据拉不到不挡聊天，点开仍可下载 */ }
}
function fileSize(bytes) {
  const size = Number(bytes);
  if (!size || size < 0) return "";
  if (size < 1024) return size + " B";
  if (size < 1024 * 1024) return (size / 1024).toFixed(1) + " KB";
  return (size / 1024 / 1024).toFixed(1) + " MB";
}
async function cacheAudio(id) {
  if (audioUrls.value[id]) return;
  try { audioUrls.value = { ...audioUrls.value, [id]: await loadAudioUrl(id) }; }
  catch { /* 点击播放时会再试一次并提示 */ }
}
function append(incoming) {
  const seen = new Set(messages.value.filter(item => item.clientId).map(item => item.clientId));
  const known = new Set(messages.value.map(item => String(item.id)));
  for (const item of incoming) {
    const duplicated = (item.clientId && seen.has(item.clientId)) || known.has(String(item.id));
    if (duplicated) continue;
    messages.value.push(item);
    if (item.kind === "IMAGE") cachePreview(item.body);
    if (item.kind === "AUDIO") cacheAudio(item.body);
    if (item.kind === "FILE") cacheFileMeta(item.body);
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
    // 不传 before 时后端给的是「最近 100 条」：早先是正序取前 100 条，会话一长新消息就永远不出现
    const list = await request("/im/conversations/" + active.value + "/messages", { data: { after: 0 } });
    if (current !== generation) return;
    messages.value = list;
    hasEarlier.value = list.length >= MESSAGE_PAGE;
    anchor.value = "";
    messages.value.filter(item => item.kind === "IMAGE").forEach(item => cachePreview(item.body));
    messages.value.filter(item => item.kind === "AUDIO").forEach(item => cacheAudio(item.body));
    messages.value.filter(item => item.kind === "FILE").forEach(item => cacheFileMeta(item.body));
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
    // 连着实时通道时回执由后端推回来，不用再拉一遍；断线才需要自己补
    if (active.value && !props.live) await loadReceipts();
  } catch { /* 已读回执失败不影响聊天 */ }
}

/** 撤回窗口，与后端 ImService.RECALL_WINDOW_SECONDS 对齐；窗口判断以服务端为准，这里只管藏入口。 */
const RECALL_WINDOW_MS = 120_000;

/** 撤回成功（本机发起或另一台设备的自己撤回）后就地改占位，不等重拉。 */
function markDeleted(messageId) {
  const target = messages.value.find(item => String(item.id) === String(messageId));
  if (!target || target.deleted) return;
  target.deleted = true;
  target.body = "";
}

function recallable(item) {
  if (!isOwn(item) || item.deleted || !item.createdAt) return false;
  return Date.now() - new Date(item.createdAt).getTime() < RECALL_WINDOW_MS;
}

/** 长按自己的消息撤回；别人的消息与窗口外的旧消息长按无反应。 */
async function confirmRecall(item) {
  if (!recallable(item)) return;
  const confirmed = await new Promise(resolve =>
    uni.showModal({ title: "撤回这条消息？", success: resolve, fail: () => resolve(null) }));
  if (!confirmed?.confirm) return;
  try {
    await request("/im/conversations/" + active.value + "/messages/" + item.id + "/recall", { method: "POST" });
    markDeleted(item.id);
  } catch (error) { report(error); }
}
/** 加载更早的消息：拿当前最老一条的 id 当游标，前插并保持视口停在原来那条上。 */
async function loadEarlier() {
  if (loadingEarlier.value || !messages.value.length) return;
  const oldest = Number(messages.value[0].id) || 0;
  if (!oldest) return;
  const current = generation;
  loadingEarlier.value = true;
  try {
    const list = await request("/im/conversations/" + active.value + "/messages", { data: { after: 0, before: oldest } });
    if (current !== generation) return;
    hasEarlier.value = list.length >= MESSAGE_PAGE;
    const known = new Set(messages.value.map(item => String(item.id)));
    const older = list.filter(item => !known.has(String(item.id)));
    if (!older.length) return;
    messages.value = [...older, ...messages.value];
    older.filter(item => item.kind === "IMAGE").forEach(item => cachePreview(item.body));
    older.filter(item => item.kind === "FILE").forEach(item => cacheFileMeta(item.body));
    // 前插会把视口顶走，用 scroll-into-view 把原来最老的那条钉住
    await nextTick();
    anchor.value = "msg-" + oldest;
  } catch (error) {
    report(error);
  } finally {
    if (current === generation) loadingEarlier.value = false;
  }
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
    } else if (!props.live) {
      // 只有断线时才为「谁已读」轮询：连着的时候后端会推 type=read
      loadReceipts();
    }
  } catch { /* 轮询失败静默，等待下一次 */ }
}
function startPolling() {
  stopPolling();
  timer = setInterval(poll, props.pollMs);
}
function stopPolling() {
  if (timer) clearInterval(timer);
  timer = null;
}
// 实时通道连上/断开时切轮询节奏；正在聊的会话不用重新选，换个间隔就行
watch(() => props.pollMs, () => { if (active.value) startPolling(); });

/**
 * 收到后端推送的帧。
 *
 * - `type=message`：帧里带齐了渲染字段，直接上屏；同一会话的轮询兜底返回同一条时由 append 按 id 去重。
 * - `type=read`：谁读到了哪条。当前会话就并进回执列表；是**自己**在另一台设备读的，
 *   就重拉一次会话列表把本机未读角标清掉——别人的已读不影响我的未读数，不用跟着跑。
 * - `type=message.deleted`：谁撤回了哪条。当前会话就地改成占位；不在当前会话就交给
 *   会话列表刷新即可（列表预览不展示正文，角标数也不受影响）。
 */
function receive(frame) {
  if (!frame) return;
  if (frame.type === "read") {
    if (String(frame.conversationId) === active.value) receipts.value = mergeReceipt(receipts.value, frame);
    else if (String(frame.userId) === selfId.value) loadConversations().catch(() => {});
    return;
  }
  if (frame.type === "message.deleted") {
    if (String(frame.conversationId) === active.value) markDeleted(frame.messageId);
    else loadConversations().catch(() => {});
    return;
  }
  if (!frame.messageId) return;
  const incoming = {
    id: frame.messageId,
    conversationId: frame.conversationId,
    senderId: frame.senderId,
    clientId: frame.clientId,
    kind: frame.kind,
    body: frame.body,
    createdAt: frame.createdAt
  };
  if (String(frame.conversationId) !== active.value) {
    // 不是当前会话：只更新列表拿未读角标，别把用户从正在看的那一屏切走
    loadConversations().catch(() => {});
    return;
  }
  append([incoming]);
  toBottom();
  markRead();
  loadConversations().catch(() => {});
}
async function select(item) {
  if (item.id === active.value) return;
  generation++;
  active.value = item.id;
  activeTitle.value = conversationLabel(item);
  messages.value = [];
  previews.value = {};
  fileMeta.value = {};
  fileUrls.value = {};
  receipts.value = [];
  hasEarlier.value = false;
  anchor.value = "";
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
  // 微信端借这次点击顺带申请订阅授权（必须在点击同步链里触发，不能等 await 之后）；
  // 不 await：弹窗归弹窗，消息该发就发。对方离线时后端会消耗授权发服务通知。
  requestNotify(request, [TEMPLATE_KEYS.imMessage]).catch(() => {});
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

/**
 * 聊天传文件。后端 kind=FILE 只要求文件是家庭共享，不限 mime。
 *
 * 选择器：H5 / App 用 uni.chooseFile（H5 是 input[type=file] 真实现）；
 * 微信小程序没有系统文件选择器，uni 把 chooseFile 映射到 wx.chooseMessageFile
 * （从聊天记录里选），同一调用两端都能跑，不用条件编译。
 */
async function sendFile() {
  if (sending.value || !active.value) return;
  sending.value = true;
  try {
    const result = await uni.chooseFile({ count: 1 });
    const picked = (result.tempFiles || [])[0];
    if (!picked) return;
    const file = await upload("/files", picked.path || picked.tempFilePath, { visibility: "FAMILY" });
    const clientId = uuid();
    const created = await request("/im/conversations/" + active.value + "/messages", { method: "POST", data: { clientId, kind: "FILE", body: file.id } });
    fileMeta.value = { ...fileMeta.value, [file.id]: file };
    append([created]);
    await toBottom();
    markRead();
  } catch (error) {
    if (!String(error?.errMsg || "").includes("cancel")) report(error);
  } finally { sending.value = false; }
}

/** 点开文件气泡：先鉴权下载成本地路径，小程序/App 用 openDocument 预览，H5 直接触发另存。 */
async function openFile(item) {
  if (downloadingId.value) return;
  const id = item.body;
  downloadingId.value = id;
  try {
    const path = fileUrls.value[id] || await loadFileUrl(id);
    fileUrls.value = { ...fileUrls.value, [id]: path };
    if (typeof document !== "undefined") {
      // H5：tempFilePath 是 blob URL，<a download> 即可保存
      const anchor = document.createElement("a");
      anchor.href = path;
      anchor.download = fileMeta.value[id]?.name || "family-file";
      anchor.click();
    } else {
      uni.openDocument({
        filePath: path,
        showMenu: true,
        fail: () => uni.showToast({ title: "暂不支持打开此类型文件", icon: "none" })
      });
    }
  } catch (error) { report(error); }
  finally { downloadingId.value = ""; }
}

/** 语音消息。后端 kind=AUDIO 早就支持（校验 mime 必须是 audio/*），这里补上录音与播放。 */

/** 比这短的录音按误触处理直接丢弃；不然手一抖就发出去一条 0.1 秒的气音。 */
const MIN_VOICE_MS = 800;
const MAX_VOICE_MS = 60_000;
let recorder = null, recordStartedAt = 0, discardRecording = false, audioContext = null;

function ensureRecorder() {
  if (recorder || typeof uni.getRecorderManager !== "function") return recorder;
  recorder = uni.getRecorderManager();
  recorder.onStart(() => { recordStartedAt = Date.now(); });
  recorder.onStop(result => {
    recording.value = false;
    if (discardRecording) { discardRecording = false; return; }
    if (!result?.tempFilePath || Date.now() - recordStartedAt < MIN_VOICE_MS) {
      uni.showToast({ title: "说话时间太短", icon: "none" });
      return;
    }
    sendVoice(result.tempFilePath);
  });
  recorder.onError(() => {
    recording.value = false;
    uni.showToast({ title: "录音失败，请检查麦克风权限", icon: "none" });
  });
  return recorder;
}

function startRecord() {
  if (!active.value || sending.value || recording.value) return;
  let instance = null;
  try { instance = ensureRecorder(); }
  catch { instance = null; }
  if (!instance) {
    // H5 端 uni-app 的录音 API 是「不支持」桩实现，一调就抛；语音先在小程序/ App 里录
    uni.showToast({ title: "当前环境不支持录音，可在小程序里使用", icon: "none" });
    return;
  }
  discardRecording = false;
  recording.value = true;
  try { instance.start({ format: "mp3", duration: MAX_VOICE_MS }); }
  catch { recording.value = false; uni.showToast({ title: "录音启动失败", icon: "none" }); }
}
function finishRecord() {
  if (!recording.value) return;
  try { recorder.stop(); } catch { recording.value = false; }
}
function cancelRecord() {
  if (!recording.value) return;
  discardRecording = true;
  try { recorder.stop(); } catch { recording.value = false; }
}

async function sendVoice(filePath) {
  if (!active.value) return;
  sending.value = true;
  try {
    const file = await upload("/files", filePath, { visibility: "FAMILY" });
    const clientId = uuid();
    const created = await request("/im/conversations/" + active.value + "/messages", {
      method: "POST",
      data: { clientId, kind: "AUDIO", body: file.id }
    });
    append([created]);
    await cacheAudio(file.id);
    await toBottom();
    markRead();
  } catch (error) { report(error); }
  finally { sending.value = false; }
}

function stopVoice() {
  if (audioContext) {
    try { audioContext.destroy(); } catch { /* 已经销毁就不必再管 */ }
    audioContext = null;
  }
  playingId.value = "";
}
async function toggleVoice(item) {
  if (playingId.value === String(item.id)) return stopVoice();
  stopVoice();
  await cacheAudio(item.body);
  const src = audioUrls.value[item.body];
  if (!src) return;
  // 每次播放都新建上下文：InnerAudioContext 的事件是叠加注册的，复用会越挂越多
  const context = uni.createInnerAudioContext();
  context.src = src;
  context.onEnded(stopVoice);
  context.onError(() => { stopVoice(); uni.showToast({ title: "播放失败", icon: "none" }); });
  audioContext = context;
  playingId.value = String(item.id);
  context.play();
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
onUnmounted(() => { generation++; stopPolling(); stopVoice(); });
defineExpose({ load, receive });
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
              <text class="hint">{{ readMembers.length ? '已读：' + readMembers.join('、') : (live ? '实时推送中 · 断线自动重连' : '每 4 秒自动获取新消息') }}</text>
            </view>
            <button v-if="activePeerId" class="call-btn" size="mini" @click="emit('call', activePeerId)">☎ 通话</button>
          </view>
          <scroll-view class="messages" scroll-y :scroll-top="scrollTop" :scroll-into-view="anchor">
            <view v-if="hasEarlier" class="earlier">
              <button size="mini" :loading="loadingEarlier" :disabled="loadingEarlier" @click="loadEarlier">查看更早的消息</button>
            </view>
            <view v-if="detailError" class="state"><text>{{ detailError }}</text><button size="mini" @click="loadMessages">重试</button></view>
            <view v-else-if="loading && !messages.length" class="state">正在加载消息…</view>
            <view v-else-if="!messages.length" class="state">还没有消息<text class="hint">打个招呼吧，让家人知道你在。</text></view>
            <view v-for="item in messages" :id="'msg-' + item.id" :key="item.id" :class="['row', { own: isOwn(item) }]">
              <view class="bubble" @longpress="confirmRecall(item)">
                <text v-if="!isOwn(item)" class="sender">{{ memberName(item.senderId) }}</text>
                <view v-if="item.deleted" class="body recalled">此消息已撤回</view>
                <template v-else>
                  <image v-if="item.kind === 'IMAGE'" v-show="previews[item.body]" class="photo" :src="previews[item.body]" mode="widthFix" @click="previewImages(previews[item.body])" />
                  <text v-if="item.kind === 'IMAGE' && !previews[item.body]" class="hint">图片加载中…</text>
                  <view v-if="item.kind === 'AUDIO'" class="voice" @click="toggleVoice(item)">
                    <text class="voice-icon">{{ playingId === String(item.id) ? '⏸' : '▶' }}</text>
                    <text class="voice-label">语音消息</text>
                  </view>
                  <view v-if="item.kind === 'FILE'" class="file" @click="openFile(item)">
                    <text class="file-icon">{{ downloadingId === item.body ? '…' : '⌸' }}</text>
                    <view class="file-info">
                      <text class="file-name">{{ fileMeta[item.body]?.name || '文件' }}</text>
                      <text class="file-size">{{ fileSize(fileMeta[item.body]?.sizeBytes) || '点击下载' }}</text>
                    </view>
                  </view>
                  <text v-if="item.kind !== 'IMAGE' && item.kind !== 'AUDIO' && item.kind !== 'FILE'" class="body">{{ item.body }}</text>
                </template>
                <text class="time">{{ stamp(item.createdAt) }}</text>
              </view>
            </view>
          </scroll-view>
          <view class="composer">
            <button
              size="mini"
              :class="['mic', { recording }]"
              :disabled="!active"
              @touchstart.prevent="startRecord"
              @touchend.prevent="finishRecord"
              @touchcancel="cancelRecord"
              @mousedown="startRecord"
              @mouseup="finishRecord"
              @mouseleave="cancelRecord"
            >{{ recording ? "松开 发送" : "🎙" }}</button>
            <input v-model="draft" class="input" maxlength="4000" placeholder="说点什么给家人…" :disabled="sending" @confirm="sendText" />
            <button size="mini" :disabled="sending" @click="sendImage">图片</button>
            <button size="mini" :disabled="sending" @click="sendFile">文件</button>
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
.earlier{display:flex;justify-content:center;padding:10px 0}
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
.recalled{font-size:13px;color:var(--c-muted);font-style:italic}
.photo{width:180px;border-radius:10px;margin:2px 0}
.time{font-size:10px;color:var(--c-muted);text-align:right}
.composer{flex-direction:row;align-items:center;gap:8px}
.input{flex:1;height:44px;background:var(--c-surface-2);border:1px solid var(--c-line);border-radius:11px;padding:0 14px;font-size:13px;box-sizing:border-box}
.composer button{margin:0;font-size:12px}
.mic{font-size:15px}
.mic.recording{background:var(--c-danger);color:#fff;box-shadow:0 2px 10px rgba(220,80,60,.35)}
.voice{display:flex;align-items:center;gap:8px;min-width:104px;padding:2px 0}
.voice-icon{font-size:14px}
.voice-label{font-size:13px}
.file{display:flex;align-items:center;gap:10px;min-width:150px;padding:4px 0}
.file-icon{font-size:20px;flex-shrink:0}
.file-info{display:flex;flex-direction:column;gap:2px;min-width:0}
.file-name{font-size:13px;font-weight:550;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:170px}
.file-size{font-size:10px;color:var(--c-muted)}
.state{text-align:center;color:var(--c-muted);font-size:13px;padding:26px 10px}
.state button{margin-top:8px;font-size:11px}
@media(max-width:760px){.chat-frame{grid-template-columns:1fr}.sidebar{max-height:none}.sidebar-foot{order:2}}
</style>
