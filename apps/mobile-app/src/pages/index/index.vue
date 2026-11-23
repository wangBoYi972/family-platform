<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import { login, request } from "../../utils/request";
import { localDate } from "@shared/tasks.mjs";
import { anniversariesToday, remindDoneKey, remindText } from "@shared/anniversary-remind.mjs";
import { familyToday, healthRemindDoneKey, healthRemindText, pendingDoses, todayFollowUps } from "@shared/health-schedule.mjs";
// 面板与家庭端共用同一份实现（apps/shared/panels），移动端不再维护自己的简化版列表。
import TaskPanel from "@shared/panels/TaskPanel.vue";
import LedgerPanel from "@shared/panels/LedgerPanel.vue";
import CalendarPanel from "@shared/panels/CalendarPanel.vue";
import AnniversaryPanel from "@shared/panels/AnniversaryPanel.vue";
import DynamicsPanel from "@shared/panels/DynamicsPanel.vue";
import AlbumsPanel from "@shared/panels/AlbumsPanel.vue";
import AlbumTimeline from "@shared/panels/AlbumTimeline.vue";
import NoticesPanel from "@shared/panels/NoticesPanel.vue";
import FilesPanel from "@shared/panels/FilesPanel.vue";
import ArchivePanel from "@shared/panels/ArchivePanel.vue";
import HealthPanel from "@shared/panels/HealthPanel.vue";
import MembersPanel from "@shared/panels/MembersPanel.vue";
import ChatPanel from "@shared/panels/ChatPanel.vue";
import GlobalSearch from "@shared/panels/GlobalSearch.vue";
import CallPanel from "@shared/call/CallPanel.vue";
import CallHistoryPanel from "@shared/panels/CallHistoryPanel.vue";
import IotPanel from "@shared/panels/IotPanel.vue";
import { MOBILE_NAV_TARGETS, resolveNavTarget } from "@shared/navigation.mjs";
import { FamilySocket } from "@shared/family-socket.js";
import { refreshPlan, hitsTopic } from "@shared/refresh-topics.mjs";
import { bindWxSession } from "@shared/wx-notify.mjs";
import { sumUnread } from "@shared/unread.mjs";

/** 后端会发哪些领域事件，这里就认识哪些主题；新事件类型上来先加进这个集合。 */
const KNOWN_TOPICS = new Set(["task", "file", "notice", "dynamic", "album", "archive", "calendar", "anniversary", "member", "ledger"]);

// 手机端主 tab 沿用现有横向滚动；账本是独立入口，不塞进「我的家」子标签。
// 家居对全体家庭成员开放，和家庭端一样是主 tab。
const TABS = ["任务", "账本", "日程", "家庭圈", "消息", "家居", "我的家"];
const SUB_TABS = {
  日程: ["家庭日历", "纪念日"],
  家庭圈: ["动态", "相册", "时光轴"],
  我的家: ["公告", "文件", "档案", "用药复诊", "成员", "设置"]
};
const subTab = ref({ 日程: "家庭日历", 家庭圈: "动态", 我的家: "公告" });
// 与家庭端同一套文案与图标，换端只换承载布局，不换说话方式。
const TITLES = { 任务: "小事一起做", 账本: "一起把钱算清楚", 日程: "给相聚，留一点时间", 家庭圈: "收藏生活的闪光", 消息: "随时说说话", 家居: "智能家居", 我的家: "我们的一家" };
const NAV_ICONS = { 任务: "✓", 账本: "¥", 日程: "▦", 家庭圈: "▧", 消息: "◍", 家居: "◈", 我的家: "⌂" };
const now = new Date();
const dateText = `${now.getMonth() + 1} 月 ${now.getDate()} 日`;
const weekText = "周" + "日一二三四五六"[now.getDay()];
const hour = now.getHours();
const greeting = hour < 6 ? "夜深了" : hour < 11 ? "早上好" : hour < 14 ? "中午好" : hour < 18 ? "下午好" : "晚上好";
/** 主 tab 或子标签变化时换 key，让内容区重播一次入场动画。 */
const paneKey = computed(() => [tab.value, subTab.value[tab.value] || ""].join("|"));

const taskPanel = ref(null), ledgerPanel = ref(null), dynamicsPanel = ref(null), noticesPanel = ref(null), callPanel = ref(null), callHistoryPanel = ref(null), searchBox = ref(null);
const calendarPanel = ref(null), anniversaryPanel = ref(null), albumsPanel = ref(null), timelinePanel = ref(null);
const filesPanel = ref(null), archivePanel = ref(null), healthPanel = ref(null), membersPanel = ref(null), chatPanel = ref(null), iotPanel = ref(null);

function openCall() { callPanel.value?.openDial(); }
function openCallHistory() { callHistoryPanel.value?.open(); }
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
  { ref: ledgerPanel, topics: ["ledger"] },
  { ref: dynamicsPanel, topics: ["dynamic"] },
  { ref: albumsPanel, topics: ["album"] },
  { ref: timelinePanel, topics: ["album"] },
  { ref: filesPanel, topics: ["file"] },
  { ref: archivePanel, topics: ["archive"] },
  { ref: healthPanel, topics: ["archive"] },
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
/**
 * 当天健康提醒：未服用的药和今天的复诊各按日期去重一次；
 * 不在这里弹微信授权，授权只由用户点击触发。
 */
async function checkHealth() {
  try {
    const today = familyToday();
    const medKey = healthRemindDoneKey("medication", today);
    const visitKey = healthRemindDoneKey("follow_up", today);
    if (uni.getStorageSync(medKey) && uni.getStorageSync(visitKey)) return;
    const [doses, visits] = await Promise.all([
      request("/archives/medications/today").catch(() => []),
      request("/archives/follow-ups/upcoming?days=30").catch(() => [])
    ]);
    const medicines = uni.getStorageSync(medKey) ? [] : pendingDoses(doses);
    const followUps = uni.getStorageSync(visitKey) ? [] : todayFollowUps(visits, today);
    if (!medicines.length && !followUps.length) return;
    if (medicines.length) uni.setStorageSync(medKey, 1);
    if (followUps.length) uni.setStorageSync(visitKey, 1);
    uni.showModal({ title: "今日健康提醒", content: healthRemindText({ medicines, visits: followUps }), showCancel: false, confirmText: "知道了" });
  } catch { /* 提醒失败静默 */ }
}
onMounted(() => { if (token.value) { familySocket.connect(); refreshUnread(); bindWxSession(request).catch(() => {}); checkAnniversary(); checkHealth(); } });
onUnmounted(() => familySocket.close());
watch(token, value => {
  if (value) { familySocket.connect(); refreshUnread(); bindWxSession(request).catch(() => {}); checkAnniversary(); checkHealth(); }
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
  // 助手本机缓存带上一账号对话；只清 token 会让下一账号空库补传时误导入
  uni.removeStorageSync("family_ai_history");
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
  <!-- 登录：与家庭端同一套两栏版式，窄屏自动收成单栏 -->
  <view v-if="!token" class="login-layout">
    <view class="login-story mf-rise" style="--i:0">
      <text class="wordmark">一起 · 家庭空间</text>
      <text class="story-title">把时间，<br/>留给家人。</text>
      <text class="story-copy">一起安排生活里的小事，<br/>收藏每一个值得记住的日常。</text>
      <view class="story-art">
        <view class="sun"></view>
        <view class="roof"></view>
        <view class="house"><view class="window"></view><view class="door"></view></view>
        <view class="hill"></view>
      </view>
      <text class="story-bottom">日程 · 任务 · 账本 · 相册</text>
    </view>
    <view class="login mf-rise" style="--i:2">
      <text class="eyebrow">{{ greeting }}，欢迎回家</text>
      <text class="login-title">进入我们的家</text>
      <text class="description">使用管理员为你开通的家庭账号登录。</text>
      <text class="field-label">手机号</text>
      <input v-model="phone" class="input" type="number" maxlength="11" placeholder="请输入手机号" />
      <text class="field-label">账号密码</text>
      <input v-model="password" class="input" maxlength="64" password placeholder="请输入密码" @confirm="signIn" />
      <button class="mf-btn login-button" :loading="submitting" :disabled="submitting" @click="signIn">进入家庭空间</button>
      <text class="login-note">仅向家庭成员开放 · 让陪伴更近一点</text>
    </view>
  </view>

  <view v-else class="shell">
    <view class="brandbar">
      <text class="wordmark">一起<text class="brand-caption">家庭空间</text></text>
      <view class="brand-right">
        <view class="date-pill"><text class="date-main">{{ dateText }}</text><text class="date-sub">{{ weekText }}</text></view>
        <text class="call-entry" @click="openCall">☎ 通话</text>
        <text class="history-entry" @click="openCallHistory">通话记录</text>
        <text class="search-entry" @click="openSearch">⌕ 搜索</text>
      </view>
    </view>

    <view class="page-heading"><text class="page-title">{{ TITLES[tab] }}</text></view>

    <view :key="paneKey" class="pane">
      <TaskPanel v-if="tab === '任务'" ref="taskPanel" @session-ended="clearSession" />
      <LedgerPanel v-else-if="tab === '账本'" ref="ledgerPanel" @session-ended="clearSession" />

      <view v-else-if="tab === '日程'">
        <view class="subnav"><button v-for="item in SUB_TABS.日程" :key="item" :class="{active: subTab.日程 === item}" @click="switchSub(item)">{{ item }}</button></view>
        <CalendarPanel v-if="subTab.日程 === '家庭日历'" ref="calendarPanel" @session-ended="clearSession" />
        <AnniversaryPanel v-else ref="anniversaryPanel" @session-ended="clearSession" @go="openTarget" />
      </view>

      <view v-else-if="tab === '家庭圈'">
        <view class="subnav"><button v-for="item in SUB_TABS.家庭圈" :key="item" :class="{active: subTab.家庭圈 === item}" @click="switchSub(item)">{{ item }}</button></view>
        <DynamicsPanel v-if="subTab.家庭圈 === '动态'" ref="dynamicsPanel" :token="token" />
        <AlbumsPanel v-else-if="subTab.家庭圈 === '相册'" ref="albumsPanel" :token="token" />
        <AlbumTimeline v-else ref="timelinePanel" @session-ended="clearSession" />
      </view>

      <ChatPanel v-else-if="tab === '消息'" ref="chatPanel" :peer-id="chatPeer" :poll-ms="socketOpen ? 20000 : 4000" :live="socketOpen" @session-ended="clearSession" @unread-change="unread = $event" @call="openCallWith" @call-history="openCallHistory" />

      <IotPanel v-else-if="tab === '家居'" ref="iotPanel" @session-ended="clearSession" />

      <view v-else>
        <view class="subnav"><button v-for="item in SUB_TABS.我的家" :key="item" :class="{active: subTab.我的家 === item}" @click="switchSub(item)">{{ item }}</button></view>
        <NoticesPanel v-if="subTab.我的家 === '公告'" ref="noticesPanel" :token="token" />
        <FilesPanel v-else-if="subTab.我的家 === '文件'" ref="filesPanel" @session-ended="clearSession" />
        <ArchivePanel v-else-if="subTab.我的家 === '档案'" ref="archivePanel" @session-ended="clearSession" />
        <HealthPanel v-else-if="subTab.我的家 === '用药复诊'" ref="healthPanel" @session-ended="clearSession" />
        <MembersPanel v-else-if="subTab.我的家 === '成员'" ref="membersPanel" :token="token" @chat="chatWith" />
        <view v-else class="settings mf-card">
          <text class="settings-title">账号与安全</text>
          <text class="description">修改密码后，所有设备需要重新登录。</text>
          <input v-model="currentPassword" class="input" maxlength="64" password placeholder="当前密码" />
          <input v-model="newPassword" class="input" maxlength="64" password placeholder="新密码（12 至 64 位）" />
          <input v-model="confirmation" class="input" maxlength="64" password placeholder="再次输入新密码" />
          <button class="mf-btn" :loading="submitting" :disabled="submitting" @click="changePassword">更新密码</button>
          <button class="logout" :disabled="submitting" @click="logout">退出家庭空间</button>
        </view>
      </view>
    </view>

    <view class="navigation">
      <button v-for="item in TABS" :key="item" :class="{active: tab === item}" @click="switchTab(item)">
        <text class="nav-icon">{{ NAV_ICONS[item] }}</text>
        <text>{{ item }}</text>
        <text v-if="item === '消息' && unread" class="nav-badge">{{ unread > 99 ? '99+' : unread }}</text>
      </button>
    </view>
    <GlobalSearch ref="searchBox" @navigate="openTarget" @session-ended="clearSession" />
    <CallPanel ref="callPanel" :active="!!token" />
    <CallHistoryPanel ref="callHistoryPanel" @session-ended="clearSession" />
  </view>
</view>
</template>

<style scoped>
.page{position:relative;min-height:100vh;overflow-x:hidden;background:transparent}
.shell{--pad:48px;position:relative;z-index:1;max-width:1180px;margin:auto;padding:0 var(--pad) 140px}
.brandbar{display:flex;align-items:center;justify-content:space-between;flex-wrap:nowrap;gap:16px;padding:40px 0 12px}
.wordmark{flex:0 0 auto;font-size:26px;font-weight:600;font-family:var(--font-display);color:#142B53;letter-spacing:.04em;white-space:nowrap}
.brand-caption{font-size:12px;font-weight:400;color:#4F7DC6;margin-left:12px;letter-spacing:.12em;white-space:nowrap}
.brand-right{display:flex;align-items:center;flex:0 0 auto;gap:12px}
.date-pill{display:flex;flex:0 0 auto;flex-direction:column;align-items:flex-end;line-height:1.3;padding:8px 14px;border-radius:30px;background:var(--liquid-surface);border:var(--liquid-outline);backdrop-filter:blur(20px);white-space:nowrap}
.date-main{font-size:14px;font-weight:600;color:#142B53;white-space:nowrap}
.date-sub{font-size:11px;color:#4F7DC6;white-space:nowrap}
.search-entry,.history-entry{display:inline-flex;align-items:center;justify-content:center;flex:0 0 auto;font-size:12px;line-height:1;color:#142B53;background:var(--liquid-surface);border:var(--liquid-outline);border-radius:30px;padding:8px 14px;white-space:nowrap;backdrop-filter:blur(20px)}
.history-entry:active{transform:translateY(1px)}
.search-entry:active{transform:translateY(1px)}
.call-entry{display:inline-flex;align-items:center;justify-content:center;flex:0 0 auto;font-size:12px;line-height:1;letter-spacing:.06em;color:#F4F7FF;background:#4F7DC6;border:1px solid rgba(255,255,255,.8);border-radius:30px;padding:8px 14px;white-space:nowrap}
.call-entry:active{transform:translateY(1px)}
.page-heading{padding:28px 0 4px;position:relative}
.page-title{display:block;font-family:var(--font-display);font-size:52px;font-weight:500;margin-top:8px;letter-spacing:.02em;color:#142B53;line-height:1.3}
.page-title::after{content:"";display:block;width:64px;height:2px;margin-top:20px;background:#86BBFF}
.pane{animation:mf-fade 320ms var(--e-out) both}
.subnav{display:flex;gap:8px;margin:6px 0 16px;width:fit-content;max-width:100%;overflow-x:auto;background:transparent}
.subnav button{padding:8px 16px;margin:0;border:1px solid rgba(255,255,255,.8);border-radius:30px;background:rgba(255,255,255,.54);font-size:13px;color:#4F7DC6;line-height:1.5;letter-spacing:.04em;backdrop-filter:blur(20px);white-space:nowrap;flex:0 0 auto}
.subnav button::after{border:0}
.subnav button.active{color:#F4F7FF;background:#4F7DC6;border-color:rgba(255,255,255,.8);box-shadow:0 8px 20px rgba(134,187,255,.45);font-weight:600}
.subnav button:active{transform:translateY(1px)}
.navigation{position:fixed;left:16px;right:16px;bottom:12px;z-index:30;display:flex;gap:2px;overflow-x:auto;padding:8px 12px calc(8px + env(safe-area-inset-bottom));background:var(--liquid-surface);border:var(--liquid-outline);border-radius:30px;backdrop-filter:blur(20px);-webkit-backdrop-filter:blur(20px);box-shadow:0 10px 30px rgba(134,187,255,.28)}
.navigation button{position:relative;display:flex;flex:0 0 auto;flex-direction:column;align-items:center;gap:4px;min-width:96px;padding:8px 0;margin:0;border:0;background:none;color:#4F7DC6;font-size:12px;line-height:1.5;letter-spacing:.06em}
.navigation button::after{border:0}
.navigation button.active{color:#142B53;font-weight:600;background:transparent}
.navigation button:active{transform:none}
.nav-icon{display:block;font-size:16px;line-height:1.3}
.navigation button.active .nav-icon{color:#4F7DC6}
.navigation button.active::before{content:'';position:absolute;top:0;left:50%;width:14px;height:2px;margin-left:-7px;background:#86BBFF;border-radius:2px}
.nav-badge{position:absolute;top:-4px;right:calc(50% - 24px);min-width:16px;height:16px;line-height:16px;border-radius:8px;background:var(--c-danger);color:#fff;font-size:9px;text-align:center;padding:0 4px;box-shadow:0 2px 6px rgba(185,106,91,.5);animation:mf-pop 420ms var(--e-spring) both}

/* ——— 登录页 ——— */
.login-layout{position:relative;z-index:1;display:grid;grid-template-columns:1.05fr .95fr;gap:26px;align-items:center;max-width:980px;margin:auto;padding:40px 30px 60px;min-height:100vh}
.login-story{position:relative;overflow:hidden;min-height:420px;padding:38px 40px;border-radius:30px;background:var(--liquid-surface);border:var(--liquid-outline);box-shadow:0 10px 30px rgba(134,187,255,.28);backdrop-filter:blur(20px);display:flex;flex-direction:column}
.login-story .wordmark{font-size:16px;font-weight:500;font-family:var(--font-display);color:var(--c-ink);letter-spacing:.14em}
.story-title{font-family:var(--font-display);font-size:48px;font-weight:500;line-height:1.4;margin-top:28px;color:var(--c-ink);letter-spacing:.02em}
.story-copy{font-size:13px;line-height:2;margin-top:16px;color:#6d86b0}
.story-bottom{margin-top:auto;font-size:11px;letter-spacing:3px;color:#8aa6c8}
.story-art{position:absolute;right:10px;bottom:22px;width:250px;height:190px}
.sun{position:absolute;width:46px;height:46px;border-radius:50%;background:transparent;border:1.5px solid #4F7DC6;right:26px;top:6px}
.roof{position:absolute;bottom:100px;left:53px;width:0;height:0;border-left:63px solid transparent;border-right:63px solid transparent;border-bottom:44px solid #4F7DC6}
.house{position:absolute;background:rgba(255,255,255,.7);border:1.5px solid #4F7DC6;bottom:34px;left:60px;width:104px;height:94px;border-radius:2px}
.window{position:absolute;width:26px;height:26px;left:16px;top:22px;background:transparent;border:1.5px solid #4F7DC6;border-radius:2px}
.door{position:absolute;width:24px;height:38px;right:16px;bottom:0;background:transparent;border:1.5px solid #4F7DC6;border-bottom:0;border-radius:2px 2px 0 0}
.hill{position:absolute;bottom:-30px;left:8px;right:8px;height:28px;border-radius:0;border-top:1.5px solid #4F7DC6;background:transparent}
.login{padding:32px 30px;border-radius:30px;background:rgba(255,255,255,.54);border:1px solid rgba(255,255,255,.8);backdrop-filter:blur(20px)}
.login .eyebrow{color:var(--c-primary);font-weight:640}
.login-title{display:block;font-family:var(--font-display);font-size:26px;font-weight:500;margin:10px 0 8px;color:var(--c-ink);letter-spacing:.12em}
.description{display:block;font-size:12px;line-height:1.9;color:var(--c-muted);margin-bottom:22px}
.field-label{display:block;font-size:11px;color:var(--c-ink-2);margin:0 0 7px 4px;letter-spacing:.5px}
.input{width:100%;height:46px;padding:0 14px;margin-bottom:16px;border:1px solid rgba(255,255,255,.8);border-radius:30px;background:rgba(255,255,255,.72);font-size:14px;color:#142B53;box-sizing:border-box}
.input:focus{border-color:var(--c-primary);background:#fff;outline:none}
.login-button{width:100%;margin-top:8px}
.login-note{display:block;margin-top:18px;text-align:center;font-size:11px;color:var(--c-muted)}

/* ——— 设置 ——— */
.settings{padding:26px}
.settings-title{display:block;font-size:17px;font-weight:650;color:var(--c-ink)}
.settings .description{margin-bottom:18px}
.logout{width:100%;height:44px;margin-top:12px;border:1px solid rgba(255,255,255,.8);border-radius:30px;background:rgba(244,190,217,.7);color:#142B53;font-size:13px;letter-spacing:.08em;line-height:44px}
.logout::after{border:0}
.logout:active{transform:translateY(1px)}

@media(max-width:860px){
  .login-layout{grid-template-columns:1fr;padding:26px 20px 50px}
  .login-story{min-height:230px;padding:26px}
  .story-title{font-size:29px;margin-top:18px}
  .story-copy,.story-bottom{display:none}
  .story-art{transform:scale(.62);transform-origin:right bottom;right:-6px;bottom:8px}
  .login{padding:26px 22px}
}
@media(max-width:700px){
  .shell{padding:0 20px 118px}
  .brandbar{padding:20px 0 14px;gap:10px}
  .wordmark{font-size:21px}
  .brand-caption{margin-left:8px;letter-spacing:1px}
  .brand-right{gap:8px}
  .page-title{font-size:34px}
  .navigation{border-radius:0}
}
@media(max-width:520px){
  .shell{padding:0 16px 112px}
  .brand-caption{display:none}
  .brand-right{gap:6px}
  .date-pill{padding:5px 10px}
  .date-main{font-size:11px}
  .date-sub{font-size:9px}
  .call-entry,.search-entry,.history-entry{padding:8px 11px;font-size:11px}
}
</style>
