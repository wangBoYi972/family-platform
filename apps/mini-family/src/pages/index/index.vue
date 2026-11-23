<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import CalendarPanel from "@shared/panels/CalendarPanel.vue";
import { login, register, request } from "../../utils/request";
import { localDate } from "@shared/tasks.mjs";
import { anniversariesToday, remindDoneKey, remindText } from "@shared/anniversary-remind.mjs";
import { familyToday, healthRemindDoneKey, healthRemindText, pendingDoses, todayFollowUps } from "@shared/health-schedule.mjs";
import TaskPanel from "@shared/panels/TaskPanel.vue";
import LedgerPanel from "@shared/panels/LedgerPanel.vue";
import DynamicsPanel from "@shared/panels/DynamicsPanel.vue";
import AlbumsPanel from "@shared/panels/AlbumsPanel.vue";
import NoticesPanel from "@shared/panels/NoticesPanel.vue";
import MembersPanel from "@shared/panels/MembersPanel.vue";
import ChatPanel from "@shared/panels/ChatPanel.vue";
import ArchivePanel from "@shared/panels/ArchivePanel.vue";
import HealthPanel from "@shared/panels/HealthPanel.vue";
import TodayOverview from "../../components/TodayOverview.vue";
import GlobalSearch from "@shared/panels/GlobalSearch.vue";
import FilesPanel from "@shared/panels/FilesPanel.vue";
import AlbumTimeline from "@shared/panels/AlbumTimeline.vue";
import AnniversaryPanel from "@shared/panels/AnniversaryPanel.vue";
import IotPanel from "@shared/panels/IotPanel.vue";
import CallPanel from "@shared/call/CallPanel.vue";
import CallHistoryPanel from "@shared/panels/CallHistoryPanel.vue";
import { FAMILY_NAV_TARGETS, resolveNavTarget } from "@shared/navigation.mjs";
import { FamilySocket } from "@shared/family-socket.js";
import { refreshPlan, hitsTopic } from "@shared/refresh-topics.mjs";
import { bindWxSession } from "@shared/wx-notify.mjs";
import { sumUnread } from "@shared/unread.mjs";

/** 后端会发哪些领域事件，这里就认识哪些主题；新事件类型上来先加进这个集合。 */
const KNOWN_TOPICS = new Set(["task", "file", "notice", "dynamic", "album", "archive", "calendar", "anniversary", "member", "ledger", "iot"]);

const phone = ref(""), password = ref(""), token = ref(uni.getStorageSync("family_access_token"));
const tab = ref("首页");
const circleTab = ref("动态"), homeTab = ref("成员"), calTab = ref("日程");
const searchBox = ref(null);
const navIcons = ["⌂", "▦", "✓", "¥", "◍", "▧", "🏠", "○"];
const submitting = ref(false);
const unread = ref(0), chatPeer = ref(null);
const changingPassword = ref(false), currentPassword = ref(""), newPassword = ref(""), confirmation = ref("");
const authMode = ref("login");
const regForm = ref({ inviteCode: "", phone: "", nickname: "", password: "", confirmation: "" });

const tabs = ["首页", "日历", "任务", "账本", "聊天", "家庭圈", "家居", "我的家"];
const homeTabs = ["成员", "公告", "档案", "用药复诊", "文件", "设置"];
const titles = { 首页: "家人在一起，就很好。", 日历: "给相聚，留一点时间", 任务: "小事一起做", 账本: "一起把钱算清楚", 聊天: "随时说说话", 家庭圈: "收藏生活的闪光", 家居: "智能家居", 我的家: "我们的一家" };
const shortcutTabs = ["日历", "任务", "聊天", "家庭圈"];
const shortcutIcons = ["▦", "✓", "◍", "▧"];
const shortcutLabels = { 日历: "家庭日历", 任务: "一起做事", 聊天: "家人聊天", 家庭圈: "照片与动态" };
const shortcutNotes = { 日历: "行程与纪念", 任务: "分工与提醒", 聊天: "随时说句话", 家庭圈: "动态与相册" };
const circleTabs = ["动态", "相册", "时光轴"];
const circleLabels = { 动态: "家人近况", 相册: "时光相册", 时光轴: "照片时光轴" };
const calTabs = ["日程", "纪念日"];

const now = new Date();
const dateText = `${now.getMonth() + 1} 月 ${now.getDate()} 日`;
const dayNum = now.getDate();
const monthLabel = `${now.getMonth() + 1}月`;
const weekText = "周" + "日一二三四五六"[now.getDay()];
const hour = now.getHours();
const greeting = hour < 6 ? "夜深了" : hour < 11 ? "早上好" : hour < 14 ? "中午好" : hour < 18 ? "下午好" : "晚上好";

/** 共享面板（全局搜索、纪念日倒计时）只上报稳定的数据源 key，映射表统一在 apps/shared/navigation.mjs。 */

/** 快捷入口：传字符串只切主 tab；传对象可同时切子标签（如首页倒计时 → 日历/纪念日）。 */
function open(target) {
  if (target && typeof target === "object") {
    if (target.key) return open(resolveNavTarget(FAMILY_NAV_TARGETS, target, { tab: "首页" }));
    if (target.calTab) calTab.value = target.calTab;
    if (target.circleTab) circleTab.value = target.circleTab;
    if (target.homeTab) homeTab.value = target.homeTab;
    tab.value = target.tab;
    return;
  }
  tab.value = target;
}
/** 主 tab 或任一子标签变化时换 key，让内容区重播一次入场动画（面板本来就是按 v-if 重建的）。 */
const paneKey = computed(() => [tab.value, calTab.value, circleTab.value, homeTab.value].join("|"));

function openSearch() { searchBox.value?.open(); }
function chatWith(member) { chatPeer.value = member.id; tab.value = "聊天"; }

/** 通话：从顶部入口选人，或从聊天面板直接呼叫某位家人。 */
const callPanel = ref(null), callHistoryPanel = ref(null);
function openCall() { callPanel.value?.openDial(); }
function openCallWith(id) { callPanel.value?.openDialWith(id); }
function openCallHistory() { callHistoryPanel.value?.open(); }

/**
 * 实时刷新：家里另一台设备改了任务、公告、相册或文件，后端会向同家庭广播 refresh，
 * 这里只重拉当前挂载着的面板（面板本来按 v-if 渲染，没显示的那块不会白跑请求）。
 * refresh 帧带事件类型（task.created / file.deleted…），按主题挑面板：任务变了不必连相册一起刷。
 * 聊天面板不在此列——它自己轮询，重拉会把正在输入的内容和滚动位置一起冲掉。
 */
const calendarPanel = ref(null), anniversaryPanel = ref(null), taskPanel = ref(null), ledgerPanel = ref(null);
const dynamicsPanel = ref(null), albumsPanel = ref(null), timelinePanel = ref(null);
const membersPanel = ref(null), noticesPanel = ref(null), archivePanel = ref(null), healthPanel = ref(null), filesPanel = ref(null);
const iotPanel = ref(null);
const todayOverview = ref(null);
const chatPanel = ref(null);
const socketOpen = ref(false);
/** 每个面板关心哪些主题；todayOverview 汇总任务/日程/动态/成员，跟着相关事件一起刷。 */
const refreshMap = [
  { ref: calendarPanel, topics: ["calendar"] },
  { ref: anniversaryPanel, topics: ["anniversary"] },
  { ref: taskPanel, topics: ["task"] },
  { ref: ledgerPanel, topics: ["ledger"] },
  { ref: todayOverview, topics: ["task", "calendar", "dynamic", "member"] },
  { ref: dynamicsPanel, topics: ["dynamic"] },
  { ref: albumsPanel, topics: ["album"] },
  { ref: timelinePanel, topics: ["album"] },
  { ref: membersPanel, topics: ["member"] },
  { ref: noticesPanel, topics: ["notice"] },
  { ref: archivePanel, topics: ["archive"] },
  { ref: healthPanel, topics: ["archive"] },
  { ref: filesPanel, topics: ["file"] },
  { ref: iotPanel, topics: ["iot"] }
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
  // 新消息带内容直接下发，交给聊天面板上屏；聊天页没打开时它不挂载，只能自己更新角标
  onMessage: frame => {
    if (chatPanel.value) chatPanel.value.receive(frame);
    // 面板没挂载时新消息和「另一台设备已读」都要刷角标：后者不清，角标会一直挂着
    else refreshUnread();
  }
});
/**
 * 未读角标。聊天面板挂载时由它的 unread-change 维护；
 * 没挂载（用户在看任务、家庭圈）时面板收不到推送，这里拿会话列表补一遍。
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
 * 当天健康提醒：未服用的药与今天的复诊各按日期记一个 key，同一天不再弹；
 * 与纪念日提醒一样只弹一次，不在这里申请微信授权（授权只由用户点击触发）。
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
  } catch { /* 提醒是锦上添花，失败静默 */ }
}
onMounted(() => { if (token.value) { familySocket.connect(); refreshUnread(); bindWxSession(request).catch(() => {}); checkAnniversary(); checkHealth(); } });
onUnmounted(() => familySocket.close());
watch(token, value => {
  if (value) { familySocket.connect(); refreshUnread(); bindWxSession(request).catch(() => {}); checkAnniversary(); checkHealth(); }
  else { familySocket.close(); unread.value = 0; }
});

function report(error) {
  if (error.status === 401) token.value = "";
  uni.showToast({ title: error.message || "请求失败", icon: "none" });
}

async function signIn() {
  if (submitting.value) return;
  if (!/^1[0-9]{10}$/.test(phone.value) || !password.value)
    return uni.showToast({ title: "请输入正确手机号和密码", icon: "none" });
  submitting.value = true;
  try {
    const data = await login({ phone: phone.value, password: password.value, client: "family" });
    uni.setStorageSync("family_access_token", data.token);
    token.value = data.token;
    password.value = "";
  } catch (error) {
    report(error);
  } finally {
    submitting.value = false;
  }
}

/** 登录 / 注册切换：清空另一侧的临时输入，避免密码串场。 */
function switchAuth(next) {
  if (authMode.value === next) return;
  authMode.value = next;
  regForm.value = { inviteCode: "", phone: "", nickname: "", password: "", confirmation: "" };
  password.value = "";
}

async function signUp() {
  if (submitting.value) return;
  const body = regForm.value;
  if (!body.inviteCode.trim()) return uni.showToast({ title: "请填写管理员发放的邀请码", icon: "none" });
  if (!/^1[0-9]{10}$/.test(body.phone)) return uni.showToast({ title: "请输入正确的手机号", icon: "none" });
  if (body.password.length < 12 || body.password.length > 64) return uni.showToast({ title: "密码需要 12 至 64 位", icon: "none" });
  if (body.password !== body.confirmation) return uni.showToast({ title: "两次输入的密码不一致", icon: "none" });
  submitting.value = true;
  try {
    const data = await register({
      phone: body.phone,
      inviteCode: body.inviteCode.trim().toUpperCase(),
      password: body.password,
      displayName: body.nickname.trim() || undefined,
      client: "family"
    });
    uni.setStorageSync("family_access_token", data.token);
    token.value = data.token;
    regForm.value = { inviteCode: "", phone: "", nickname: "", password: "", confirmation: "" };
    uni.showToast({ title: "注册成功，已加入家庭", icon: "none" });
  } catch (error) {
    report(error);
  } finally {
    submitting.value = false;
  }
}

function clearSession() {
  uni.removeStorageSync("family_access_token");
  // 助手本机缓存带上一账号对话；只清 token 会让下一账号空库补传时误导入
  uni.removeStorageSync("family_ai_history");
  token.value = "";
  changingPassword.value = false;
  currentPassword.value = "";
  newPassword.value = "";
  confirmation.value = "";
}

async function logout() {
  if (submitting.value) return;
  submitting.value = true;
  try {
    await request("/auth/logout", { method: "POST" });
    clearSession();
  } catch (error) {
    report(error);
  } finally {
    submitting.value = false;
  }
}

async function changePassword() {
  if (submitting.value) return;
  if (!currentPassword.value || newPassword.value.length < 12 || newPassword.value.length > 64 || newPassword.value !== confirmation.value)
    return uni.showToast({ title: "请填写当前密码及一致的 12 至 64 位新密码", icon: "none" });
  submitting.value = true;
  try {
    await request("/auth/password", { method: "PUT", data: { currentPassword: currentPassword.value, newPassword: newPassword.value } });
    clearSession();
    uni.showToast({ title: "密码已更新，请重新登录", icon: "none" });
  } catch (error) {
    report(error);
  } finally {
    submitting.value = false;
  }
}
</script>
<template>
<view class="page">
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
      <text class="story-bottom">日程 · 任务 · 动态 · 相册</text>
    </view>
    <view class="login mf-rise" style="--i:2">
      <text class="eyebrow">{{ greeting }}，欢迎回家</text>
      <view class="auth-tabs">
        <text :class="['auth-tab', { on: authMode === 'login' }]" @click="switchAuth('login')">登录</text>
        <text :class="['auth-tab', { on: authMode === 'register' }]" @click="switchAuth('register')">注册</text>
      </view>
      <template v-if="authMode === 'login'">
        <text class="login-title">进入我们的家</text>
        <text class="description">使用管理员为你开通的家庭账号登录。</text>
        <text class="field-label">手机号</text>
        <input v-model="phone" class="input" type="number" maxlength="11" placeholder="请输入手机号"/>
        <text class="field-label">账号密码</text>
        <input v-model="password" class="input" maxlength="64" password placeholder="请输入密码" @confirm="signIn"/>
        <button class="mf-btn login-button" :loading="submitting" :disabled="submitting" @click="signIn">进入家庭空间</button>
        <text class="login-note">仅向家庭成员开放 · 让陪伴更近一点</text>
      </template>
      <template v-else>
        <text class="login-title">加入我们的家</text>
        <text class="description">注册需要管理员发放的邀请码，没有邀请码无法创建账号。</text>
        <text class="field-label">邀请码</text>
        <input v-model="regForm.inviteCode" class="input" maxlength="24" placeholder="如 FAM-7K2M-QX4P"/>
        <text class="field-label">手机号</text>
        <input v-model="regForm.phone" class="input" type="number" maxlength="11" placeholder="请输入手机号"/>
        <text class="field-label">昵称</text>
        <input v-model="regForm.nickname" class="input" maxlength="32" placeholder="选填"/>
        <text class="field-label">密码</text>
        <input v-model="regForm.password" class="input" maxlength="64" password placeholder="12 至 64 位"/>
        <text class="field-label">确认密码</text>
        <input v-model="regForm.confirmation" class="input" maxlength="64" password placeholder="再输入一次" @confirm="signUp"/>
        <button class="mf-btn login-button" :loading="submitting" :disabled="submitting" @click="signUp">注册并进入</button>
        <text class="login-note">邀请码由管理员发放 · 只可用一次</text>
      </template>
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

    <view class="page-heading">
      <text class="page-title">{{ titles[tab] }}</text>
    </view>

    <view :key="paneKey" class="pane">
    <view v-if="tab === '首页'" class="home">
      <!-- 问候主卡 -->
      <view class="bento">
        <view class="hero mf-card mf-rise" style="--i:0">
          <view class="hero-seal">
            <text class="hero-day">{{ dayNum }}</text>
            <text class="hero-month">{{ monthLabel }}</text>
          </view>
          <view class="hero-copy">
            <text class="hero-label">{{ greeting }}，今天也好好生活</text>
            <text class="hero-title">忙碌之外，<br/>别忘了彼此。</text>
          </view>
        </view>
      </view>

      <!-- 快捷入口 -->
      <view class="shortcuts">
        <button v-for="(item,index) in shortcutTabs" :key="item" class="shortcut mf-press mf-rise" :class="'shortcut--' + index" :style="{'--i': index + 1}" @click="tab=item">
          <text class="shortcut-icon">{{ shortcutIcons[index] }}</text>
          <text class="shortcut-label">{{ shortcutLabels[item] }}</text>
          <text class="shortcut-note">{{ shortcutNotes[item] }}</text>
        </button>
      </view>

      <view class="overview-slot mf-rise" style="--i:5"><TodayOverview ref="todayOverview" @go="open" @session-ended="clearSession"/></view>
    </view>

    <view v-else-if="tab === '日历'">
      <view class="subnav"><button v-for="item in calTabs" :key="item" :class="{active:calTab===item}" @click="calTab=item">{{ item === '日程' ? '家庭日历' : '纪念日清单' }}</button></view>
      <CalendarPanel v-if="calTab==='日程'" ref="calendarPanel" @session-ended="clearSession"/><AnniversaryPanel v-else ref="anniversaryPanel" @session-ended="clearSession" @go="open"/>
    </view>
    <TaskPanel v-else-if="tab === '任务'" ref="taskPanel" @session-ended="clearSession"/>
    <LedgerPanel v-else-if="tab === '账本'" ref="ledgerPanel" @session-ended="clearSession"/>
    <ChatPanel v-else-if="tab === '聊天'" ref="chatPanel" :peer-id="chatPeer" :poll-ms="socketOpen ? 20000 : 4000" :live="socketOpen" @session-ended="clearSession" @unread-change="unread = $event" @call="openCallWith" @call-history="openCallHistory"/>
    <IotPanel v-else-if="tab === '家居'" ref="iotPanel" @session-ended="clearSession"/>
    <view v-else-if="tab === '家庭圈'">
      <view class="subnav"><button v-for="item in circleTabs" :key="item" :class="{active:circleTab===item}" @click="circleTab=item">{{ circleLabels[item] }}</button></view>
      <DynamicsPanel v-if="circleTab==='动态'" ref="dynamicsPanel" :token="token"/><AlbumsPanel v-else-if="circleTab==='相册'" ref="albumsPanel" :token="token"/><AlbumTimeline v-else ref="timelinePanel" @session-ended="clearSession"/>
    </view>
    <view v-else>
      <view class="subnav"><button v-for="item in homeTabs" :key="item" :class="{active:homeTab===item}" @click="homeTab=item">{{ item }}</button></view>
      <MembersPanel v-if="homeTab==='成员'" ref="membersPanel" :token="token" @chat="chatWith"/><NoticesPanel v-else-if="homeTab==='公告'" ref="noticesPanel" :token="token"/><ArchivePanel v-else-if="homeTab==='档案'" ref="archivePanel" @session-ended="clearSession"/><HealthPanel v-else-if="homeTab==='用药复诊'" ref="healthPanel" @session-ended="clearSession"/><FilesPanel v-else-if="homeTab==='文件'" ref="filesPanel" @session-ended="clearSession"/>
      <view v-else class="settings mf-card">
        <text class="settings-title">账号与安全</text>
        <text class="description">修改密码后，所有设备需要重新登录。</text>
        <input v-model="currentPassword" class="input" maxlength="64" password placeholder="当前密码"/>
        <input v-model="newPassword" class="input" maxlength="64" password placeholder="新密码（12 至 64 位）"/>
        <input v-model="confirmation" class="input" maxlength="64" password placeholder="再次输入新密码"/>
        <button class="mf-btn" :loading="submitting" :disabled="submitting" @click="changePassword">更新密码</button>
        <button class="logout" :disabled="submitting" @click="logout">退出家庭空间</button>
      </view>
    </view>

    </view>

    <view class="navigation">
      <button v-for="(item,index) in tabs" :key="item" :class="{active:tab===item}" @click="tab=item">
        <text class="nav-icon">{{ navIcons[index] }}</text>
        <text>{{ item }}</text>
        <text v-if="item === '聊天' && unread" class="nav-badge">{{ unread > 99 ? '99+' : unread }}</text>
      </button>
    </view>
    <GlobalSearch ref="searchBox" @navigate="open" @session-ended="clearSession"/>
    <CallPanel ref="callPanel" :active="!!token"/>
    <CallHistoryPanel ref="callHistoryPanel" @session-ended="clearSession"/>
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
.bento{margin-top:28px}
.hero{display:flex;flex-direction:row;align-items:stretch;padding:0;background:var(--liquid-surface);border:var(--liquid-outline);border-radius:30px;min-height:240px;overflow:hidden;backdrop-filter:blur(20px);-webkit-backdrop-filter:blur(20px);box-shadow:0 10px 30px rgba(134,187,255,.28)}
.hero-seal{display:flex;flex-direction:column;align-items:center;justify-content:center;flex:0 0 168px;background:rgba(255,255,255,.28);color:#142B53;border-right:1px solid rgba(255,255,255,.8)}
.hero-day{font-family:var(--font-display);font-size:76px;line-height:1;font-weight:500}
.hero-month{margin-top:12px;font-size:14px;letter-spacing:.2em;color:#4F7DC6}
.hero-copy{display:flex;flex-direction:column;justify-content:center;padding:40px 44px}
.hero-label{font-family:var(--font-display);font-size:16px;color:#4F7DC6;letter-spacing:.22em}
.hero-title{font-family:var(--font-display);font-size:42px;display:block;line-height:1.45;font-weight:500;margin-top:16px;color:#142B53}

/* ——— 插画（登录页）——— */
.story-art{position:relative;align-self:stretch;flex-shrink:0;width:250px;height:190px}
.sun{position:absolute;width:46px;height:46px;border-radius:50%;background:transparent;border:1.5px solid #4F7DC6;right:26px;top:6px}
.roof{position:absolute;bottom:96px;left:44px;width:0;height:0;border-left:63px solid transparent;border-right:63px solid transparent;border-bottom:44px solid #4F7DC6}
.house{position:absolute;background:rgba(255,255,255,.7);border:1.5px solid #4F7DC6;bottom:30px;left:51px;width:104px;height:94px;border-radius:2px}
.window{position:absolute;width:26px;height:26px;left:16px;top:22px;background:transparent;border:1.5px solid #4F7DC6;border-radius:2px}
.door{position:absolute;width:24px;height:38px;right:16px;bottom:0;background:transparent;border:1.5px solid #4F7DC6;border-bottom:0;border-radius:2px 2px 0 0}
.hill{position:absolute;bottom:-18px;left:8px;right:8px;height:28px;border-radius:0;border-top:1.5px solid #4F7DC6;background:transparent}

/* ——— 快捷入口（参考「一起」版式：柔和色块 + 图标 + 名称 + 副题）——— */
.shortcuts{display:grid;grid-template-columns:repeat(4,1fr);gap:16px;margin:28px 0 8px}
.shortcut{display:flex;flex-direction:column;align-items:flex-start;justify-content:space-between;gap:8px;min-height:156px;border-radius:30px;padding:22px 20px 20px;margin:0;line-height:1.4;border:var(--liquid-outline);background:var(--liquid-surface);backdrop-filter:blur(20px);-webkit-backdrop-filter:blur(20px);box-shadow:0 10px 30px rgba(134,187,255,.22);text-align:left}
.shortcut::after{border:0}
.shortcut-icon{font-family:var(--font-display);font-size:20px;line-height:1;color:#4F7DC6;letter-spacing:.16em}
.shortcut-label{font-family:var(--font-display);font-size:20px;font-weight:500;color:#142B53;margin-top:18px}
.shortcut-note{font-size:13px;color:var(--c-ink-2)}

/* ——— 首页底部 ——— */
.overview-slot{margin-top:20px}

/* ——— 内容区：切换时整页淡入，卡片再依次上浮 ——— */
.pane{animation:mf-fade 320ms var(--e-out) both}

/* ——— 子标签 ——— */
.subnav{display:flex;gap:8px;margin:6px 0 16px;border-bottom:0;width:fit-content;background:transparent}
.subnav button{padding:8px 16px;margin:0;border:1px solid rgba(255,255,255,.8);border-radius:30px;background:rgba(255,255,255,.54);font-size:13px;color:#4F7DC6;line-height:1.5;letter-spacing:.04em;backdrop-filter:blur(20px)}
.subnav button::after{border:0}
.subnav button.active{color:#F4F7FF;background:#4F7DC6;border-color:rgba(255,255,255,.8);box-shadow:0 8px 20px rgba(134,187,255,.45);font-weight:600}
.subnav button:active{transform:translateY(1px)}

/* ——— 底部导航 ——— */
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
.story-art .house{bottom:34px;left:60px}
.story-art .roof{bottom:100px;left:53px}
.story-art .hill{bottom:-30px}
.login{padding:32px 30px;border-radius:30px;background:rgba(255,255,255,.54);border:1px solid rgba(255,255,255,.8);backdrop-filter:blur(20px)}
.login .eyebrow{color:var(--c-primary);font-weight:640}
.auth-tabs{display:flex;gap:18px;margin:10px 0 4px;border-bottom:1px solid var(--c-glass-line)}
.auth-tab{padding-bottom:8px;font-size:15px;letter-spacing:2px;color:var(--c-muted);border-bottom:2px solid transparent}
.auth-tab.on{color:var(--c-primary);font-weight:650;border-bottom-color:var(--c-primary)}
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

/* ——— 响应式 ——— */
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
  .hero{padding:0;min-height:180px}
  .hero-seal{flex-basis:96px}
  .hero-day{font-size:44px}
  .hero-title{font-size:26px}
  .hero-copy{padding:24px 20px}
  .shortcuts{grid-template-columns:repeat(2,1fr);gap:12px}
  .shortcut{padding:20px 18px 18px}
  .subnav{width:100%;overflow-x:auto}
  .navigation{border-radius:0}
}
/* 手机竖屏：品牌副标题让位，按钮只保留单行文字，避免每个胶囊各自折行 */
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
