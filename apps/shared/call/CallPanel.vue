<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from "vue";
// 共享组件：鉴权请求由各宿主应用通过 @app 别名提供（apps/*/src/utils/request.js）。
import { request } from "@app/utils/request";
import { CallClient } from "./call-client.js";
import { MeshRtc } from "./webrtc-mesh.js";
import CallVideo from "./CallVideo.vue";
import CallAudio from "./CallAudio.vue";

const props = defineProps({
  active: { type: Boolean, default: false },
  members: { type: Array, default: () => [] },
  maxParticipants: { type: Number, default: 8 }
});

const visible = ref(false);
const stage = ref("dial");
const kind = ref("VIDEO");
const call = ref(null);
const picked = ref([]);
const busy = ref(false);
const errorText = ref("");
const rosterError = ref("");
const endedText = ref("");
const self = ref(null);
const roster = ref([]);
const seconds = ref(0);
const micOn = ref(true);
const camOn = ref(true);
const facing = ref("user");
const localStream = ref(null);

const streams = reactive({});
const peerStates = reactive({});
const micState = reactive({});
const camState = reactive({});

let client = null;
let mesh = null;
let preparing = null;
let ticker = null;
let resetTimer = null;

const selfId = computed(() => String(self.value?.userId ?? ""));
const liveParticipants = computed(() => (call.value?.participants || []).filter(item => item.state === "JOINED"));

function nameOf(id) {
  if (String(id) === selfId.value) return "我";
  const found = roster.value.find(item => String(item.id) === String(id));
  return found?.displayName || "家人";
}

const tiles = computed(() =>
  liveParticipants.value.map(item => {
    const id = String(item.userId);
    const isSelf = id === selfId.value;
    return {
      userId: id,
      name: nameOf(id),
      initial: nameOf(id).slice(0, 1),
      isSelf,
      stream: isSelf ? localStream.value : streams[id],
      mic: micState[id] ?? item.micOn,
      cam: camState[id] ?? item.camOn,
      state: peerStates[id] || ""
    };
  })
);

const timerText = computed(() => {
  const total = Math.max(0, seconds.value);
  const mm = String(Math.floor(total / 60)).padStart(2, "0");
  const ss = String(total % 60).padStart(2, "0");
  return mm + ":" + ss;
});
const incomingName = computed(() => nameOf(call.value?.callerId));
const stageTitle = computed(() => {
  if (stage.value === "outgoing") return "正在呼叫…";
  if (stage.value === "active") return timerText.value;
  if (stage.value === "ended") return endedText.value;
  return "";
});

watch(
  () => props.active,
  value => {
    if (value) boot();
    else shutdown();
  },
  { immediate: true }
);

async function boot() {
  if (!props.active || client) return;
  loadRoster();
  client = new CallClient({ onEvent: handleEvent });
  try {
    await client.connect();
  } catch {
    // 连接失败由客户端自动退避重连
  }
}

function shutdown() {
  resetMedia();
  client?.close();
  client = null;
  visible.value = false;
  call.value = null;
  stage.value = "dial";
}

async function loadRoster(force = false) {
  if (!force && roster.value.length) return;
  rosterError.value = "";
  try {
    const me = await request("/auth/session");
    self.value = me;
    if (props.members?.length) {
      roster.value = props.members.filter(item => String(item.id) !== String(me.userId));
      return;
    }
    const list = await request("/users/members");
    roster.value = (list || []).filter(item => String(item.id) !== String(me.userId));
    if (!roster.value.length) rosterError.value = "家庭里还没有其他成员";
  } catch (error) {
    // 拨号面板必须说明为什么选不到人，否则只显示「正在加载」会让人以为卡死
    rosterError.value = "成员加载失败：" + (error?.message || "网络异常");
  }
}

function openDial() {
  if (!props.active) return;
  if (!roster.value.length) loadRoster(true);
  errorText.value = "";
  picked.value = [];
  stage.value = "dial";
  visible.value = true;
  boot();
}

function closeDial() {
  if (stage.value === "dial") visible.value = false;
}

/** 从聊天面板直接呼叫某位家人：预选成员并停留在语音/视频选择这一步。 */
function openDialWith(id) {
  openDial();
  if (id != null) picked.value = [String(id)];
}

function togglePick(id) {
  const key = String(id);
  picked.value = picked.value.includes(key)
    ? picked.value.filter(item => item !== key)
    : [...picked.value, key];
}

function pickAll() {
  picked.value = roster.value.slice(0, props.maxParticipants - 1).map(item => String(item.id));
}

async function handleEvent(message) {
  switch (message.type) {
    case "call.invited":
      return onInvited(message);
    case "call.accepted":
      return onAccepted(message);
    case "call.rejected":
      uni.showToast({ title: nameOf(message.user) + "拒绝了通话", icon: "none" });
      if (message.call) applySnapshot(message.call);
      if (call.value?.mode === "DIRECT") endLocal("对方已拒绝");
      return;
    case "call.left":
      if (message.call) applySnapshot(message.call);
      dropPeer(message.user);
      return;
    case "call.ended":
      if (message.call) applySnapshot(message.call);
      endLocal(reasonText(message.reason));
      return;
    case "call.state":
      micState[String(message.user)] = message.mic;
      camState[String(message.user)] = message.cam;
      return;
    case "webrtc.offer":
      mesh?.handleOffer(message.from, message.sdp).catch(error => warn("处理 offer 失败", error));
      return;
    case "webrtc.answer":
      mesh?.handleAnswer(message.from, message.sdp).catch(error => warn("处理 answer 失败", error));
      return;
    case "webrtc.ice":
      mesh?.handleIce(message.from, message.candidate).catch(error => warn("处理 ICE 失败", error));
      return;
    case "error":
      errorText.value = message.message || "通话异常";
      return;
    default:
      return;
  }
}

/** 通话链路的问题不该静默：只打日志，不打断通话。 */
function warn(message, error) {
  console.warn("[call] " + message + "：" + (error?.message || error));
}

function reasonText(reason) {
  if (reason === "NO_ANSWER") return "无人接听";
  if (reason === "REJECTED") return "对方已拒绝";
  if (reason === "HANGUP") return "通话已结束";
  if (reason === "LEFT") return "通话已结束";
  return "通话已结束";
}

async function onInvited(message) {
  if (stage.value === "active" || stage.value === "outgoing" || stage.value === "incoming") {
    // 正在通话中：自动拒接，避免双呼叫互相打断
    request(`/media/calls/${message.call.id}/reject`, { method: "POST" }).catch(() => {});
    return;
  }
  errorText.value = "";
  call.value = message.call;
  kind.value = message.call.kind;
  applySnapshot(message.call);
  stage.value = "incoming";
  visible.value = true;
}

async function onAccepted(message) {
  if (message.call) applySnapshot(message.call);
  if (stage.value === "outgoing") {
    stage.value = "active";
    startTimer();
  }
  if (String(message.user) === selfId.value) {
    // 我是接听方：由我向房间内已有成员发起 offer
    await startMedia(true);
  } else if (!mesh) {
    await startMedia(false);
  }
}

function applySnapshot(snapshot) {
  call.value = snapshot;
  (snapshot.participants || []).forEach(item => {
    const id = String(item.userId);
    micState[id] = item.micOn;
    camState[id] = item.camOn;
  });
}

/**
 * 本地采集与 MeshRtc 构建必须幂等。
 *
 * <p>接听时会同时触发「HTTP 应答返回」与「call.accepted 信令回执」两条路径，
 * 两者都会调用 startMedia；如果并发进入，会各自 new 一个 MeshRtc 并互相覆盖，
 * 后一个还没采集完就被拿去做 offer，导致 SDP 没有 m-line（max-bundle 报错、对方永远收不到画面）。
 */
async function prepareMedia() {
  if (mesh) return;
  if (preparing) return preparing;
  preparing = (async () => {
    const iceServers = await request("/media/ice-servers");
    const candidate = new MeshRtc({
      iceServers,
      kind: kind.value,
      selfId: selfId.value,
      send: payload => client?.send({ ...payload, callId: call.value?.id }),
      onStream: (id, stream) => {
        streams[id] = stream;
      },
      onPeerState: (id, state) => {
        peerStates[id] = state;
      }
    });
    try {
      await candidate.openLocal();
      mesh = candidate;
      localStream.value = candidate.local;
    } catch (error) {
      candidate.close();
      throw error;
    }
  })();
  try {
    await preparing;
  } finally {
    preparing = null;
  }
}

async function startMedia(initiator) {
  await prepareMedia();
  if (!initiator || !mesh) return;
  const others = liveParticipants.value.filter(item => String(item.userId) !== selfId.value);
  for (const person of others) {
    try {
      await mesh.offerTo(person.userId);
    } catch (error) {
      // 单个对端失败不影响其他人，但必须留痕，否则表现为「能接通却没有画面」
      warn("发起 SDP 协商失败", error);
    }
  }
}

async function dial(kindWanted) {
  if (busy.value || !picked.value.length) return;
  busy.value = true;
  errorText.value = "";
  kind.value = kindWanted;
  try {
    const ids = picked.value.map(Number);
    await prepareMedia();
    const created = await request("/media/calls", {
      method: "POST",
      header: { "Content-Type": "application/json" },
      data: {
        mode: ids.length > 1 ? "GROUP" : "DIRECT",
        kind: kindWanted,
        calleeId: ids[0],
        participantIds: ids
      }
    });
    call.value = created;
    visible.value = true;
    stage.value = "outgoing";
    seconds.value = 0;
  } catch (error) {
    errorText.value = mediaError(error);
    resetMedia();
  } finally {
    busy.value = false;
  }
}

function mediaError(error) {
  const text = String(error?.message || "");
  if (/Permission|NotAllowed|denied/i.test(text)) return "未获得麦克风/摄像头权限，请在浏览器或系统设置中允许后重试";
  return text || "通话发起失败";
}

async function accept() {
  if (busy.value || !call.value) return;
  busy.value = true;
  try {
    const answered = await request(`/media/calls/${call.value.id}/answer`, { method: "POST" });
    applySnapshot(answered);
    stage.value = "active";
    seconds.value = 0;
    startTimer();
    await startMedia(true);
  } catch (error) {
    errorText.value = mediaError(error);
    await hangup();
  } finally {
    busy.value = false;
  }
}

async function reject() {
  const current = call.value;
  if (!current) return reset();
  try {
    await request(`/media/calls/${current.id}/reject`, { method: "POST" });
  } catch {
    // 已结束
  }
  reset();
}

async function hangup() {
  const current = call.value;
  if (current) {
    const isCaller = String(current.callerId) === selfId.value;
    const action = stage.value === "incoming" ? "reject" : isCaller ? "end" : "leave";
    try {
      await request(`/media/calls/${current.id}/${action}`, { method: "POST" });
    } catch {
      // 网络异常时以本地清理为准，服务端有超时回收
    }
  }
  reset();
}

function endLocal(text) {
  endedText.value = text || "通话已结束";
  stage.value = "ended";
  stopTimer();
  resetMedia();
  clearTimeout(resetTimer);
  resetTimer = setTimeout(reset, 1500);
}

function reset() {
  stopTimer();
  clearTimeout(resetTimer);
  resetMedia();
  call.value = null;
  stage.value = "dial";
  visible.value = false;
  seconds.value = 0;
  picked.value = [];
}

function resetMedia() {
  mesh?.close();
  mesh = null;
  localStream.value = null;
  Object.keys(streams).forEach(key => delete streams[key]);
  Object.keys(peerStates).forEach(key => delete peerStates[key]);
  micOn.value = true;
  camOn.value = true;
  facing.value = "user";
}

function dropPeer(userId) {
  mesh?.dropPeer(userId);
  delete streams[String(userId)];
  delete peerStates[String(userId)];
}

function startTimer() {
  stopTimer();
  ticker = setInterval(() => (seconds.value += 1), 1000);
}

function stopTimer() {
  if (ticker) clearInterval(ticker);
  ticker = null;
}

async function toggleMic() {
  if (!mesh) return;
  micOn.value = !micOn.value;
  mesh.setMic(micOn.value);
  if (call.value) {
    request(`/media/calls/${call.value.id}/state`, {
      method: "POST",
      header: { "Content-Type": "application/json" },
      data: { mic: micOn.value, cam: camOn.value }
    }).catch(() => {});
  }
}

async function toggleCam() {
  if (!mesh) return;
  camOn.value = !camOn.value;
  mesh.setCam(camOn.value);
  if (call.value) {
    request(`/media/calls/${call.value.id}/state`, {
      method: "POST",
      header: { "Content-Type": "application/json" },
      data: { mic: micOn.value, cam: camOn.value }
    }).catch(() => {});
  }
}

async function flipCamera() {
  if (kind.value !== "VIDEO" || !mesh || typeof navigator === "undefined") return;
  facing.value = facing.value === "user" ? "environment" : "user";
  try {
    const fresh = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: facing.value },
      audio: false
    });
    const track = fresh.getVideoTracks()[0];
    const updated = await mesh.replaceVideoTrack(track);
    localStream.value = updated;
  } catch {
    facing.value = facing.value === "user" ? "environment" : "user";
    uni.showToast({ title: "当前设备无法切换摄像头", icon: "none" });
  }
}

onBeforeUnmount(() => {
  stopTimer();
  clearTimeout(resetTimer);
  resetMedia();
  client?.close();
});

defineExpose({ openDial, openDialWith });
</script>

<template>
  <view v-if="visible" class="call-layer">
    <view class="call-card">
      <!-- 拨号面板 -->
      <template v-if="stage === 'dial'">
        <view class="head">
          <text class="title">发起通话</text>
          <text class="sub">最多 {{ maxParticipants }} 人同时在线 · 媒体点对点直连，服务器只做信令</text>
        </view>
        <view class="pick-head">
          <text class="pick-label">选择家人（已选 {{ picked.length }} 人）</text>
          <text class="pick-all" @click="pickAll">选全家</text>
        </view>
        <scroll-view scroll-y class="member-list">
          <view
            v-for="item in roster"
            :key="item.id"
            :class="['member', { on: picked.includes(String(item.id)) }]"
            @click="togglePick(item.id)"
          >
            <view class="avatar">{{ (item.displayName || '家').slice(0, 1) }}</view>
            <view class="member-body">
              <text class="member-name">{{ item.displayName || '家人' }}</text>
              <text class="member-role">{{ item.role === 'ADMIN' ? '管理员' : '家庭成员' }}</text>
            </view>
            <view class="check">{{ picked.includes(String(item.id)) ? '✓' : '' }}</view>
          </view>
          <view v-if="!roster.length" class="empty">{{ rosterError || '正在加载家庭成员…' }}</view>
        </scroll-view>
        <text v-if="errorText" class="error">{{ errorText }}</text>
        <view class="actions">
          <button class="ghost" :disabled="busy" @click="closeDial">取消</button>
          <button class="ghost" :disabled="!picked.length || busy" @click="dial('AUDIO')">语音</button>
          <button class="primary" :disabled="!picked.length || busy" @click="dial('VIDEO')">视频通话</button>
        </view>
      </template>

      <!-- 来电 -->
      <template v-else-if="stage === 'incoming'">
        <view class="incoming">
          <view class="pulse-ring"><view class="avatar xl">{{ incomingName.slice(0, 1) }}</view></view>
          <text class="incoming-name">{{ incomingName }}</text>
          <text class="incoming-sub">{{ kind === 'VIDEO' ? '邀请你视频通话' : '邀请你语音通话' }}</text>
          <text v-if="errorText" class="error">{{ errorText }}</text>
          <view class="incoming-actions">
            <button class="danger-round" :disabled="busy" @click="reject">拒绝</button>
            <button class="accept-round" :loading="busy" :disabled="busy" @click="accept">接听</button>
          </view>
        </view>
      </template>

      <!-- 呼叫中 / 通话中 / 已结束 -->
      <template v-else>
        <view class="head live">
          <view class="live-left">
            <text class="title">{{ kind === 'VIDEO' ? '视频通话' : '语音通话' }}</text>
            <text class="sub">{{ stageTitle }}</text>
          </view>
          <text class="count">{{ tiles.length }} 人在线</text>
        </view>

        <view :class="['frame', { duo: tiles.length === 2, single: tiles.length === 1 }]">
          <view v-for="tile in tiles" :key="tile.userId" :class="['tile', { 'is-self': tile.isSelf }]">
            <CallVideo
              v-if="kind === 'VIDEO' && tile.stream && tile.cam"
              :stream="tile.stream"
              :muted="tile.isSelf"
              :mirror="tile.isSelf"
            />
            <view v-else class="audio-tile">
              <view class="avatar lg">{{ tile.initial }}</view>
              <text class="audio-name">{{ tile.name }}</text>
              <text v-if="!tile.cam && kind === 'VIDEO'" class="camera-off">摄像头已关闭</text>
              <!-- 没有视频画面时（语音通话 / 对方关摄像头）由这里承载远端声音 -->
              <CallAudio v-if="!tile.isSelf && tile.stream" :stream="tile.stream" />
            </view>
            <view class="tile-bar">
              <text class="who">{{ tile.isSelf ? '我' : tile.name }}</text>
              <text v-if="!tile.mic" class="muted-badge">静音</text>
            </view>
          </view>
          <view v-if="!tiles.length" class="waiting">
            <text class="waiting-text">{{ stage === 'outgoing' ? '正在等待家人接听…' : '正在连接…' }}</text>
          </view>
        </view>

        <text v-if="stage === 'outgoing'" class="tip">响铃最长 45 秒，无人接听会自动结束</text>

        <view class="controls">
          <view :class="['ctrl', { off: !micOn }]" @click="toggleMic">
            <text class="ctrl-icon">{{ micOn ? '🎙' : '🔇' }}</text>
            <text class="ctrl-label">{{ micOn ? '麦克风' : '已静音' }}</text>
          </view>
          <view v-if="kind === 'VIDEO'" :class="['ctrl', { off: !camOn }]" @click="toggleCam">
            <text class="ctrl-icon">{{ camOn ? '📷' : '🚫' }}</text>
            <text class="ctrl-label">{{ camOn ? '摄像头' : '已关闭' }}</text>
          </view>
          <view v-if="kind === 'VIDEO'" class="ctrl" @click="flipCamera">
            <text class="ctrl-icon">🔄</text>
            <text class="ctrl-label">切换</text>
          </view>
          <view class="ctrl hangup" @click="hangup">
            <text class="ctrl-icon">✕</text>
            <text class="ctrl-label">挂断</text>
          </view>
        </view>
      </template>
    </view>
  </view>
</template>

<style scoped>
.call-layer {
  position: fixed;
  inset: 0;
  z-index: 900;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(40, 33, 29, 0.55);
  padding: 20px;
  box-sizing: border-box;
}
.call-card {
  width: min(560px, 100%);
  max-height: 92vh;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 20px;
  border-radius: 24px;
  background: rgba(255, 252, 248, 0.96);
  border: 1px solid var(--c-line);
  box-shadow: 0 24px 60px rgba(45, 36, 30, 0.28);
  box-sizing: border-box;
}
@supports (backdrop-filter: blur(20px)) {
  .call-card {
    backdrop-filter: blur(20px);
  }
}
.head {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.head.live {
  flex-direction: row;
  align-items: flex-start;
  justify-content: space-between;
}
.live-left {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.title {
  font-size: 17px;
  font-weight: 650;
  color: var(--c-ink);
}
.sub {
  font-size: 11px;
  color: var(--c-muted);
  line-height: 1.6;
}
.count {
  font-size: 11px;
  color: var(--c-primary);
  background: var(--c-primary-soft);
  border-radius: 999px;
  padding: 4px 10px;
}
.pick-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.pick-label,
.pick-all {
  font-size: 12px;
  color: var(--c-ink-2);
}
.pick-all {
  color: var(--c-primary);
}
.member-list {
  max-height: 300px;
  display: flex;
  flex-direction: column;
}
.member {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 14px;
  margin-bottom: 8px;
  background: var(--c-surface-2);
  border: 1px solid transparent;
}
.member.on {
  background: var(--c-primary-soft);
  border-color: rgba(196, 121, 90, 0.4);
}
.avatar {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 15px;
  background: linear-gradient(135deg, var(--c-primary), var(--c-primary-deep));
  flex-shrink: 0;
}
.avatar.lg {
  width: 64px;
  height: 64px;
  font-size: 24px;
}
.avatar.xl {
  width: 92px;
  height: 92px;
  font-size: 34px;
}
.member-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.member-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--c-ink);
}
.member-role {
  font-size: 11px;
  color: var(--c-muted);
}
.check {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: 1px solid var(--c-line);
  color: #fff;
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.member.on .check {
  background: var(--c-primary);
  border-color: var(--c-primary);
}
.actions {
  display: flex;
  gap: 10px;
}
.actions button {
  flex: 1;
  margin: 0;
  font-size: 13px;
  border-radius: 13px;
  height: 42px;
  line-height: 42px;
}
.ghost {
  background: var(--c-surface-2);
  color: var(--c-ink);
  border: 1px solid var(--c-line);
}
.primary {
  background: linear-gradient(135deg, var(--c-primary), var(--c-primary-deep));
  color: #fff;
}
.incoming {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 26px 0 12px;
}
.pulse-ring {
  padding: 14px;
  border-radius: 50%;
  background: var(--c-primary-tint);
  animation: pulse 1.8s ease-in-out infinite;
}
@keyframes pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(196, 121, 90, 0.35); }
  50% { box-shadow: 0 0 0 18px rgba(196, 121, 90, 0); }
}
.incoming-name {
  font-size: 20px;
  font-weight: 650;
  color: var(--c-ink);
}
.incoming-sub {
  font-size: 12px;
  color: var(--c-muted);
}
.incoming-actions {
  display: flex;
  gap: 40px;
  margin-top: 18px;
}
.incoming-actions button {
  width: 84px;
  height: 84px;
  border-radius: 50%;
  margin: 0;
  font-size: 14px;
  color: #fff;
  line-height: 84px;
  padding: 0;
}
.danger-round {
  background: linear-gradient(135deg, #c9705f, #a8543f);
}
.accept-round {
  background: linear-gradient(135deg, #86a894, #5f8772);
}
.frame {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  min-height: 260px;
  max-height: 52vh;
  position: relative;
}
.frame.duo {
  display: block;
  min-height: 320px;
}
.frame.single {
  display: block;
  min-height: 300px;
}
.frame.single .tile {
  height: 100%;
}
.frame.duo .tile:not(.is-self) {
  height: 100%;
}
.frame.duo .tile.is-self {
  position: absolute;
  right: 10px;
  bottom: 10px;
  width: 30%;
  height: 34%;
  border: 2px solid rgba(255, 255, 255, 0.85);
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.25);
}
.tile {
  position: relative;
  border-radius: 16px;
  overflow: hidden;
  background: #2b2421;
  min-height: 130px;
}
.audio-tile {
  height: 100%;
  min-height: 130px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: linear-gradient(160deg, #4b3c35, #2b2421);
}
.audio-name {
  color: rgba(255, 255, 255, 0.86);
  font-size: 12px;
}
.camera-off {
  color: rgba(255, 255, 255, 0.55);
  font-size: 10px;
}
.tile-bar {
  position: absolute;
  left: 8px;
  bottom: 8px;
  right: 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.who {
  font-size: 11px;
  color: #fff;
  background: rgba(0, 0, 0, 0.4);
  border-radius: 8px;
  padding: 2px 8px;
}
.muted-badge {
  font-size: 10px;
  color: #fff;
  background: rgba(185, 106, 91, 0.85);
  border-radius: 8px;
  padding: 2px 6px;
}
.waiting {
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 16px;
  background: var(--c-surface-2);
  min-height: 240px;
}
.waiting-text {
  font-size: 13px;
  color: var(--c-muted);
}
.tip {
  font-size: 11px;
  color: var(--c-muted);
  text-align: center;
}
.controls {
  display: flex;
  justify-content: center;
  gap: 18px;
  padding-top: 6px;
}
.ctrl {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}
.ctrl-icon {
  width: 48px;
  height: 48px;
  line-height: 48px;
  text-align: center;
  border-radius: 50%;
  background: var(--c-surface-2);
  border: 1px solid var(--c-line);
  font-size: 18px;
}
.ctrl.off .ctrl-icon {
  background: var(--c-danger-soft);
  border-color: rgba(185, 106, 91, 0.35);
}
.ctrl.hangup .ctrl-icon {
  background: linear-gradient(135deg, #c9705f, #a8543f);
  color: #fff;
  border-color: transparent;
}
.ctrl-label {
  font-size: 10px;
  color: var(--c-muted);
}
.error {
  font-size: 11px;
  color: var(--c-danger);
  text-align: center;
}
.empty {
  text-align: center;
  color: var(--c-muted);
  font-size: 12px;
  padding: 24px 0;
}
</style>
