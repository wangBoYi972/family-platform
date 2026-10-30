<script setup>
import { onMounted, onUnmounted, ref, watch } from "vue";
import { login, request } from "../../utils/request";
import { localDate } from "@shared/tasks.mjs";
import { anniversariesToday, remindDoneKey, remindText } from "@shared/anniversary-remind.mjs";
// 面板与家庭端共用同一份实现（apps/shared/panels），移动端不再维护自己的简化版列表。
import TaskPanel from "@shared/panels/TaskPanel.vue";
import CalendarPanel from "@shared/panels/CalendarPanel.vue";
import AnniversaryPanel from "@shared/panels/AnniversaryPanel.vue";
import DynamicsPanel from "@shared/panels/DynamicsPanel.vue";
import AlbumsPanel from "@shared/panels/AlbumsPanel.vue";
import AlbumTimeline from "@shared/panels/AlbumTimeline.vue";
import NoticesPanel from "@shared/panels/NoticesPanel.vue";
import FilesPanel from "@shared/panels/FilesPanel.vue";
import ArchivePanel from "@shared/panels/ArchivePanel.vue";
import MembersPanel from "@shared/panels/MembersPanel.vue";
import ChatPanel from "@shared/panels/ChatPanel.vue";
import GlobalSearch from "@shared/panels/GlobalSearch.vue";
import CallPanel from "@shared/call/CallPanel.vue";
import { MOBILE_NAV_TARGETS, resolveNavTarget } from "@shared/navigation.mjs";
import { FamilySocket } from "@shared/family-socket.js";
import { refreshPlan, hitsTopic } from "@shared/refresh-topics.mjs";
import { bindWxSession } from "@shared/wx-notify.mjs";
import { sumUnread } from "@shared/unread.mjs";

/** 后端会发哪些领域事件，这里就认识哪些主题；新事件类型上来先加进这个集合。 */
const KNOWN_TOPICS = new Set(["task", "file", "notice", "dynamic", "album", "archive", "calendar", "anniversary", "member"]);

// 手机屏幕窄，主 tab 只放五个入口，其余按语义归到子标签里，避免九个 tab 横向滚动找不着。
const TABS = ["任务", "日程", "家庭圈", "消息", "我的家"];
const SUB_TABS = {
  日程: ["家庭日历", "纪念日"],
  家庭圈: ["动态", "相册", "时光轴"],
  我的家: ["公告", "文件", "档案", "成员"]
};
const subTab = ref({ 日程: "家庭日历", 家庭圈: "动态", 我的家: "公告" });

const taskPanel = ref(null), dynamicsPanel = ref(null), noticesPanel = ref(null), callPanel = ref(null), searchBox = ref(null);
const calendarPanel = ref(null), anniversaryPanel = ref(null), albumsPanel = ref(null), timelinePanel = ref(null);
const filesPanel = ref(null), archivePanel = ref(null), membersPanel = ref(null), chatPanel = ref(null);

function openCall() { callPanel.value?.openDial(); }
const phone = ref(""), password = ref(""), token = ref(uni.getStorageSync("family_access_token"));
const tab = ref("任务"), submitting = ref(false);
const unread = ref(0), chatPeer = ref(null);
const changingPassword = ref(false), currentPassword = ref(""), newPassword = ref(""), confirmation = ref("");

/**
 * 实时刷新：后端把领域事件广播成 refresh，这里重拉当前显示的面板。
 * refresh 帧带事件类型（task.created / file.deleted…），按主题挑面板：任务变了不必连相册一起刷。
 * 聊天面板由自己的轮询负责，不参与（重拉会清掉正在输入的草稿）。
 */
const socketOpen = ref(false);
/** 每个面板关心哪些主题。 */
const refreshMap = [
  { ref: calendarPanel, topics: ["calendar"] },
  { ref: anniversaryPanel, topics: ["anniversary"] },
  { ref: taskPanel, topics: ["task"] },
  { ref: dynamicsPanel, topics: ["dynamic"] },
  { ref: albumsPanel, topics: ["album"] },
  { ref: timelinePanel, topics: ["album"] },
  { ref: filesPanel, topics: ["file"] },
  { ref: archivePanel, topics: ["archive"] },
  { ref: membersPanel, topics: ["member"] },
  { ref: noticesPanel, topics: ["notice"] }
];
const familySocket = new FamilySocket({
  // 优先 sync：分页面板会就地重拉已翻过的页，不会把用户拽回第一页。
  // 没实现的（如首页概览）回落到 load。
  onRefresh: (count, events) => {
    const plan = refreshPlan(events, KNOWN_TOPICS);
    refreshMap.forEach(({ ref, topics }) => {
      if (!hitsTopic(plan, topics)) return;
      const instance = ref.value;
      if (instance) (instance.sync || instance.load)?.();
    });
  },
  onStatus: status => { socketOpen.value = status === "open"; },
  // 新消息带内容直接下发，交给聊天面板上屏；消息页没打开时它不挂载，只能自己更新角标
  onMessage: frame => {
    if (chatPanel.value) chatPanel.value.receive(frame);
    // 面板没挂载时新消息和「另一台设备已读」都要刷角标：后者不清，角标会一直挂着
    else refreshUnread();
  }
});
/**
 * 未读角标。聊天面板挂载时由它的 unread-change 维护；
 * 没挂载时面板收不到推送，这里拿会话列表补一遍。
 */
async function refreshUnread() {
  try { unread.value = sumUnread(await request("/im/conversations")); }
  catch { /* 角标失败不值得打扰用户，下次推送再试 */ }
}
/**
 * 当天纪念日提醒：登录进来当天有到期日程就弹一次窗，同一天不重复（storage 按日期去重）。
 * 3 天内临近的不弹——面板里有红标，弹窗连着弹只会被关掉再也不看。
 */
async function checkAnniversary() {
  try {
    const today = localDate();
    if (uni.getStorageSync(remindDoneKey(today))) return;
    const todayItems = anniversariesToday(await request("/calendar"), today);
    if (!todayItems.length) return;
    uni.setStorageSync(remindDoneKey(today), 1);
    uni.showModal({ title: "今天的纪念日", content: remindText(todayItems), showCancel: false, confirmText: "知道了" });
  } catch { /* 提醒是锦上添花，失败静默 */ }
}
onMounted(() => { if (token.value) { familySocket.connect(); refreshUnread(); bindWxSession(request).catch(() => {}); checkAnniversary(); } });
onUnmounted(() => familySocket.close());
watch(token, value => {
  if (value) { familySocket.connect(); refreshUnread(); bindWxSession(request).catch(() => {}); checkAnniversary(); }
  else { familySocket.close(); unread.value = 0; }
});

function switchTab(name) { tab.value = name; }
function switchSub(name) { subTab.value = { ...subTab.value, [tab.value]: name }; }
/** 共享面板（全局搜索、纪念日倒计时）只上报数据源 key，映射表与家庭端一起放在 apps/shared/navigation.mjs。 */
function openTarget(target) {
  const next = resolveNavTarget(MOBILE_NAV_TARGETS, target, { tab: "任务" });
  tab.value = next.tab;
  if (next.sub) subTab.value = { ...subTab.value, [next.tab]: next.sub };
}
function openCallWith(id) { callPanel.value?.openDialWith(id); }
function openSearch() { searchBox.value?.open(); }
/** 成员卡片点「聊天」：记下对方 ID 再切到消息，ChatPanel 会自己开这个会话。 */
function chatWith(member) {
  chatPeer.value = member.id;
  tab.value = "消息";
}

function report(error) {
  if (!error) return;
  if (error.status === 401) token.value = "";
  uni.showToast({ title: error.message || "请求失败", icon: "none" });
}
async function signIn() {
  if (submitting.value) return;
  if (!/^1[0-9]{10}$/.test(phone.value) || !password.value) return uni.showToast({ title: "请输入正确手机号和密码", icon: "none" });
  submitting.value = true;
  try {
    const data = await login({ phone: phone.value, password: password.value, client: "family" });
    uni.setStorageSync("family_access_token", data.token);
    token.value = data.token;
    password.value = "";
  } catch (error) { report(error); } finally { submitting.value = false; }
}
function clearSession() {
  uni.removeStorageSync("family_access_token");
  token.value = "";
  changingPassword.value = false;
  currentPassword.value = ""; newPassword.value = ""; confirmation.value = "";
}
async function logout() {
  if (submitting.value) return;
  submitting.value = true;
  try { await request("/auth/logout", { method: "POST" }); clearSession(); }
  catch (error) { report(error); } finally { submitting.value = false; }
}
async function changePassword() {
  if (submitting.value) return;
  if (!currentPassword.value || newPassword.value.length < 12 || newPassword.value.length > 64 || newPassword.value !== confirmation.value)
    return uni.showToast({ title: "请填写当前密码及一致的 12 至 64 位新密码", icon: "none" });
  submitting.value = true;
  try {
    await request("/auth/password", { method: "PUT", data: { currentPassword: currentPassword.value, newPassword: newPassword.value } });
    clearSession(); uni.showToast({ title: "密码已更新，请重新登录", icon: "none" });
  } catch (error) { report(error); } finally { submitting.value = false; }
}
</script>

<template>
<view class="page">
  <view v-if="!token" class="login">
    <text class="kicker">家庭成员专属</text><text class="title">家庭综合平台</text><text class="desc">安全连接家人、任务和重要资料。</text>
    <input v-model="phone" class="input" maxlength="11" placeholder="手机号" />
    <input v-model="password" class="input" maxlength="64" password placeholder="账号密码" />
    <button class="primary" :loading="submitting" :disabled="submitting" @click="signIn">进入家庭</button>
  </view>
  <view v-else>
    <view class="header">
      <view><text class="kicker">家庭空间</text><text class="title">今日家庭安排</text></view>
      <text class="logout" @click="logout">退出</text>
    </view>
    <view class="actions">
      <button size="mini" @click="changingPassword = !changingPassword">修改密码</button>
      <button size="mini" @click="openSearch">搜索</button>
      <button size="mini" @click="openCall">☎ 通话</button>
    </view>
    <view v-if="changingPassword" class="password-form">
      <input v-model="currentPassword" class="input" maxlength="64" password placeholder="当前密码" />
      <input v-model="newPassword" class="input" maxlength="64" password placeholder="新密码（12 至 64 位）" />
      <input v-model="confirmation" class="input" maxlength="64" password placeholder="再次输入新密码" />
      <button class="primary" :loading="submitting" :disabled="submitting" @click="changePassword">修改密码并重新登录</button>
    </view>

    <view class="tabs">
      <text v-for="item in TABS" :key="item" :class="['tab', tab === item ? 'active' : '']" @click="switchTab(item)">
        {{ item }}<text v-if="item === '消息' && unread" class="badge">{{ unread > 99 ? '99+' : unread }}</text>
      </text>
    </view>

    <TaskPanel v-if="tab === '任务'" ref="taskPanel" @session-ended="clearSession" />

    <view v-else-if="tab === '日程'">
      <view class="subnav"><text v-for="item in SUB_TABS.日程" :key="item" :class="['subtab', subTab.日程 === item ? 'active' : '']" @click="switchSub(item)">{{ item }}</text></view>
      <CalendarPanel v-if="subTab.日程 === '家庭日历'" ref="calendarPanel" @session-ended="clearSession" />
      <AnniversaryPanel v-else ref="anniversaryPanel" @session-ended="clearSession" @go="openTarget" />
    </view>

    <view v-else-if="tab === '家庭圈'">
      <view class="subnav"><text v-for="item in SUB_TABS.家庭圈" :key="item" :class="['subtab', subTab.家庭圈 === item ? 'active' : '']" @click="switchSub(item)">{{ item }}</text></view>
      <DynamicsPanel v-if="subTab.家庭圈 === '动态'" ref="dynamicsPanel" :token="token" />
      <AlbumsPanel v-else-if="subTab.家庭圈 === '相册'" ref="albumsPanel" :token="token" />
      <AlbumTimeline v-else ref="timelinePanel" @session-ended="clearSession" />
    </view>

    <ChatPanel v-else-if="tab === '消息'" ref="chatPanel" :peer-id="chatPeer" :poll-ms="socketOpen ? 20000 : 4000" :live="socketOpen" @session-ended="clearSession" @unread-change="unread = $event" @call="openCallWith" />

    <view v-else>
      <view class="subnav"><text v-for="item in SUB_TABS.我的家" :key="item" :class="['subtab', subTab.我的家 === item ? 'active' : '']" @click="switchSub(item)">{{ item }}</text></view>
      <NoticesPanel v-if="subTab.我的家 === '公告'" ref="noticesPanel" :token="token" />
      <FilesPanel v-else-if="subTab.我的家 === '文件'" ref="filesPanel" @session-ended="clearSession" />
      <ArchivePanel v-else-if="subTab.我的家 === '档案'" ref="archivePanel" @session-ended="clearSession" />
      <MembersPanel v-else ref="membersPanel" :token="token" @chat="chatWith" />
    </view>

    <GlobalSearch ref="searchBox" @navigate="openTarget" @session-ended="clearSession" />
    <CallPanel ref="callPanel" :active="!!token" />
  </view>
</view>
</template>

<style scoped>
.page{min-height:100vh;padding:48rpx 40rpx;box-sizing:border-box}.login{padding-top:18vh}.kicker{display:block;color:var(--c-muted);font-size:22rpx;letter-spacing:4rpx}.title{display:block;margin-top:18rpx;font-size:54rpx;font-weight:600;line-height:1.2}.desc{display:block;margin:20rpx 0 68rpx;color:var(--c-muted);font-size:28rpx}.input{height:92rpx;padding:0 26rpx;margin-bottom:20rpx;background:#fff;border:1px solid var(--c-line);border-radius:12rpx;box-sizing:border-box}.primary{margin-top:20rpx;background:var(--c-primary);color:#fff;border-radius:12rpx}.header{display:flex;justify-content:space-between;align-items:flex-start}.logout{color:var(--c-muted);font-size:26rpx}.actions{display:flex;gap:20rpx;margin-top:24rpx;flex-wrap:wrap}.tabs{display:flex;gap:32rpx;margin:56rpx 0 20rpx;border-bottom:1px solid var(--c-line);overflow-x:auto;white-space:nowrap}.tab{position:relative;flex-shrink:0;padding-bottom:20rpx;color:var(--c-muted);font-size:28rpx}.tab.active{color:var(--c-primary);border-bottom:4rpx solid var(--c-primary)}.badge{display:inline-block;min-width:28rpx;margin-left:8rpx;padding:0 8rpx;border-radius:999rpx;background:var(--c-danger);color:#fff;font-size:20rpx;line-height:28rpx;text-align:center}.subnav{display:flex;gap:18rpx;margin:20rpx 0 24rpx;overflow-x:auto;white-space:nowrap}.subtab{flex-shrink:0;padding:10rpx 24rpx;border-radius:999rpx;background:var(--c-surface-2);color:var(--c-ink-2);font-size:24rpx}.subtab.active{background:var(--c-primary);color:#fff}.empty{padding:80rpx 0;text-align:center;color:var(--c-muted)}
</style>
