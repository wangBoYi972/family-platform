<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import CalendarPanel from "@shared/panels/CalendarPanel.vue";
import { login, register, request } from "../../utils/request";
import TaskPanel from "@shared/panels/TaskPanel.vue";
import DynamicsPanel from "@shared/panels/DynamicsPanel.vue";
import AlbumsPanel from "@shared/panels/AlbumsPanel.vue";
import NoticesPanel from "@shared/panels/NoticesPanel.vue";
import MembersPanel from "@shared/panels/MembersPanel.vue";
import ChatPanel from "@shared/panels/ChatPanel.vue";
import ArchivePanel from "@shared/panels/ArchivePanel.vue";
import TodayOverview from "../../components/TodayOverview.vue";
import GlobalSearch from "@shared/panels/GlobalSearch.vue";
import FilesPanel from "@shared/panels/FilesPanel.vue";
import AlbumTimeline from "@shared/panels/AlbumTimeline.vue";
import AnniversaryPanel from "@shared/panels/AnniversaryPanel.vue";
import CallPanel from "@shared/call/CallPanel.vue";
import { FAMILY_NAV_TARGETS, resolveNavTarget } from "@shared/navigation.mjs";
import { FamilySocket } from "@shared/family-socket.js";

const phone = ref(""), password = ref(""), token = ref(uni.getStorageSync("family_access_token"));
const tab = ref("首页");
const circleTab = ref("动态"), homeTab = ref("成员"), calTab = ref("日程");
const searchBox = ref(null);
const navIcons = ["⌂", "▦", "✓", "◍", "▧", "○"];
const submitting = ref(false);
const unread = ref(0), chatPeer = ref(null);
const changingPassword = ref(false), currentPassword = ref(""), newPassword = ref(""), confirmation = ref("");
const authMode = ref("login");
const regForm = ref({ inviteCode: "", phone: "", nickname: "", password: "", confirmation: "" });

const tabs = ["首页", "日历", "任务", "聊天", "家庭圈", "我的家"];
const homeTabs = ["成员", "公告", "档案", "文件", "设置"];
const titles = { 首页: "家人在一起，就很好。", 日历: "给相聚，留一点时间", 任务: "小事一起做", 聊天: "随时说说话", 家庭圈: "收藏生活的闪光", 我的家: "我们的一家" };
const shortcutTabs = ["日历", "任务", "聊天", "家庭圈"];
const shortcutIcons = ["▦", "✓", "◍", "▧"];
const shortcutLabels = { 日历: "家庭日历", 任务: "一起做事", 聊天: "家人聊天", 家庭圈: "照片与动态" };
const shortcutNotes = { 日历: "行程与纪念", 任务: "分工与提醒", 聊天: "随时说句话", 家庭圈: "动态与相册" };
const circleTabs = ["动态", "相册", "时光轴"];
const circleLabels = { 动态: "家人近况", 相册: "时光相册", 时光轴: "照片时光轴" };
const calTabs = ["日程", "纪念日"];

const now = new Date();
const dateText = `${now.getMonth() + 1} 月 ${now.getDate()} 日`;
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
const callPanel = ref(null);
function openCall() { callPanel.value?.openDial(); }
function openCallWith(id) { callPanel.value?.openDialWith(id); }

/**
 * 实时刷新：家里另一台设备改了任务、公告、相册或文件，后端会向同家庭广播一次 refresh，
 * 这里只重拉当前挂载着的面板（面板本来按 v-if 渲染，没显示的那块不会白跑请求）。
 * 聊天面板不在此列——它自己轮询，重拉会把正在输入的内容和滚动位置一起冲掉。
 */
const calendarPanel = ref(null), anniversaryPanel = ref(null), taskPanel = ref(null);
const dynamicsPanel = ref(null), albumsPanel = ref(null), timelinePanel = ref(null);
const membersPanel = ref(null), noticesPanel = ref(null), archivePanel = ref(null), filesPanel = ref(null);
const todayOverview = ref(null);
const livePanels = [
  calendarPanel, anniversaryPanel, taskPanel, dynamicsPanel, albumsPanel,
  timelinePanel, membersPanel, noticesPanel, archivePanel, filesPanel, todayOverview
];
const familySocket = new FamilySocket({
  // 优先 sync：分页面板会就地重拉已翻过的页，不会把用户拽回第一页。
  // 没实现的（如首页概览）回落到 load。
  onRefresh: () => livePanels.forEach(panel => {
    const instance = panel.value;
    if (instance) (instance.sync || instance.load)?.();
  })
});
onMounted(() => { if (token.value) familySocket.connect(); });
onUnmounted(() => familySocket.close());
watch(token, value => (value ? familySocket.connect() : familySocket.close()));

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
        <button class="mf-btn mf-btn--shine login-button" :loading="submitting" :disabled="submitting" @click="signIn">进入家庭空间 →</button>
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
        <button class="mf-btn mf-btn--shine login-button" :loading="submitting" :disabled="submitting" @click="signUp">注册并进入 →</button>
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
          <text class="hero-label">{{ greeting }}，今天也好好生活</text>
          <text class="hero-title">忙碌之外，<br/>别忘了彼此。</text>
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
    <ChatPanel v-else-if="tab === '聊天'" :peer-id="chatPeer" @session-ended="clearSession" @unread-change="unread = $event" @call="openCallWith"/>
    <view v-else-if="tab === '家庭圈'">
      <view class="subnav"><button v-for="item in circleTabs" :key="item" :class="{active:circleTab===item}" @click="circleTab=item">{{ circleLabels[item] }}</button></view>
      <DynamicsPanel v-if="circleTab==='动态'" ref="dynamicsPanel" :token="token"/><AlbumsPanel v-else-if="circleTab==='相册'" ref="albumsPanel" :token="token"/><AlbumTimeline v-else ref="timelinePanel" @session-ended="clearSession"/>
    </view>
    <view v-else>
      <view class="subnav"><button v-for="item in homeTabs" :key="item" :class="{active:homeTab===item}" @click="homeTab=item">{{ item }}</button></view>
      <MembersPanel v-if="homeTab==='成员'" ref="membersPanel" :token="token" @chat="chatWith"/><NoticesPanel v-else-if="homeTab==='公告'" ref="noticesPanel" :token="token"/><ArchivePanel v-else-if="homeTab==='档案'" ref="archivePanel" @session-ended="clearSession"/><FilesPanel v-else-if="homeTab==='文件'" ref="filesPanel" @session-ended="clearSession"/>
      <view v-else class="settings mf-card">
        <text class="settings-title">账号与安全</text>
        <text class="description">修改密码后，所有设备需要重新登录。</text>
        <input v-model="currentPassword" class="input" maxlength="64" password placeholder="当前密码"/>
        <input v-model="newPassword" class="input" maxlength="64" password placeholder="新密码（12 至 64 位）"/>
        <input v-model="confirmation" class="input" maxlength="64" password placeholder="再次输入新密码"/>
        <button class="mf-btn mf-btn--shine" :loading="submitting" :disabled="submitting" @click="changePassword">更新密码</button>
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
  </view>
</view>
</template>
<style scoped>
.page{position:relative;min-height:100vh;overflow-x:hidden}
.shell{position:relative;z-index:1;max-width:1060px;margin:auto;padding:0 30px 120px}

/* ——— 顶栏 ——— */
/* 头部：窄屏下品牌与右侧胶囊都必须单行，否则「家庭空间 / 10月30日 / 通话 / 搜索」会各自折行挤成一团 */
.brandbar{display:flex;align-items:center;justify-content:space-between;flex-wrap:nowrap;gap:12px;padding:26px 0 18px}
.wordmark{flex:0 0 auto;font-size:24px;font-weight:750;color:var(--c-primary-deep);letter-spacing:1px;white-space:nowrap}
.brand-caption{font-size:11px;font-weight:400;color:var(--c-muted);margin-left:12px;letter-spacing:2px;white-space:nowrap}
.brand-right{display:flex;align-items:center;flex:0 0 auto;gap:12px}
.date-pill{display:flex;flex:0 0 auto;flex-direction:column;align-items:flex-end;line-height:1.3;padding:6px 14px;border-radius:var(--r-pill);background:var(--c-glass);-webkit-backdrop-filter:blur(14px);backdrop-filter:blur(14px);border:1px solid var(--c-glass-line);box-shadow:var(--sh-1);white-space:nowrap}
.date-main{font-size:12px;font-weight:600;color:var(--c-ink);white-space:nowrap}
.date-sub{font-size:10px;color:var(--c-muted);white-space:nowrap}
.search-entry{display:inline-flex;align-items:center;justify-content:center;flex:0 0 auto;font-size:12px;line-height:1;color:var(--c-primary-deep);background:var(--c-glass);-webkit-backdrop-filter:blur(14px);backdrop-filter:blur(14px);border:1px solid var(--c-glass-line);border-radius:var(--r-pill);padding:9px 15px;box-shadow:var(--sh-1);white-space:nowrap;transition:transform var(--t-fast) var(--e-spring),box-shadow var(--t) var(--e-out)}
.search-entry:active{transform:scale(.94)}
.call-entry{display:inline-flex;align-items:center;justify-content:center;flex:0 0 auto;font-size:12px;line-height:1;color:#fff;background:linear-gradient(135deg,var(--c-primary),var(--c-primary-deep));border-radius:var(--r-pill);padding:9px 15px;box-shadow:var(--sh-1);white-space:nowrap;transition:transform var(--t-fast) var(--e-spring)}
.call-entry:active{transform:scale(.94)}
/* ——— 页头 ——— */
.page-heading{padding:18px 0 8px;position:relative}
.page-title{display:block;font-size:28px;font-weight:750;margin-top:11px;letter-spacing:-.7px;color:var(--c-ink);line-height:1.35}

/* ——— 问候主卡 ——— */
.bento{margin-top:20px}
.hero{display:flex;flex-direction:column;justify-content:center;padding:34px 36px;background:linear-gradient(122deg,#f6e6da 0%,#efdccb 46%,#e6d4d8 100%);border-radius:var(--r-xl);min-height:190px}
.hero-label{font-size:11px;color:#a1795f;letter-spacing:1.4px}
.hero-title{font-size:29px;display:block;line-height:1.6;font-weight:680;margin-top:13px;color:#4a3628;letter-spacing:-.4px}

/* ——— 插画（登录页）——— */
.story-art{position:relative;align-self:stretch;flex-shrink:0;width:250px;height:190px}
.sun{position:absolute;width:58px;height:58px;border-radius:50%;background:radial-gradient(circle at 34% 32%,#f0d59a,#d9a860);right:26px;top:6px;box-shadow:0 0 30px rgba(217,168,96,.5);animation:mf-float 6s var(--e-io) infinite}
.roof{position:absolute;bottom:96px;left:44px;width:0;height:0;border-left:63px solid transparent;border-right:63px solid transparent;border-bottom:44px solid #9db4c4;filter:drop-shadow(0 -2px 4px rgba(58,52,46,.08))}
.house{position:absolute;background:linear-gradient(160deg,#fffdfa,#f3ece4);bottom:30px;left:51px;width:104px;height:94px;border-radius:8px;transform:rotate(-5deg);box-shadow:0 12px 26px -12px rgba(58,52,46,.5)}
.window{position:absolute;width:30px;height:34px;left:18px;top:24px;background:linear-gradient(160deg,#dbe9f3,#b9cfdd);border-radius:16px 16px 2px 2px;animation:mf-breathe 4.5s var(--e-io) infinite}
.door{position:absolute;width:26px;height:40px;right:18px;bottom:0;background:linear-gradient(180deg,#c98f6c,#a86e4e);border-radius:6px 6px 0 0}
.hill{position:absolute;bottom:-26px;left:0;right:-30px;height:60px;border-radius:50% 50% 0 0;background:linear-gradient(180deg,#c9d6cf,#aebfb4)}

/* ——— 快捷入口（参考「一起」版式：柔和色块 + 图标 + 名称 + 副题）——— */
.shortcuts{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin:18px 0 4px}
.shortcut{display:flex;flex-direction:column;align-items:flex-start;gap:5px;border-radius:18px;padding:20px 20px 18px;margin:0;line-height:1.4;border:0;box-shadow:0 1px 0 rgba(58,52,46,.04);text-align:left;transition:transform var(--t-fast) var(--e-spring)}
.shortcut::after{border:0}
.shortcut--0{background:#f8ece1}
.shortcut--1{background:#eceff1}
.shortcut--2{background:#eaeeea}
.shortcut--3{background:#f3eaea}
.shortcut-icon{font-size:18px;line-height:1;color:var(--c-ink);opacity:.82}
.shortcut-label{font-size:14px;font-weight:660;color:var(--c-ink);margin-top:8px}
.shortcut-note{font-size:11px;color:var(--c-ink-2);opacity:.75}

/* ——— 首页底部 ——— */
.overview-slot{margin-top:20px}

/* ——— 内容区：切换时整页淡入，卡片再依次上浮 ——— */
.pane{animation:mf-fade 320ms var(--e-out) both}

/* ——— 子标签 ——— */
.subnav{display:flex;gap:8px;padding:4px;margin:6px 0 16px;border-radius:var(--r-pill);background:var(--c-glass);-webkit-backdrop-filter:blur(14px);backdrop-filter:blur(14px);border:1px solid var(--c-glass-line);box-shadow:var(--sh-1);width:fit-content}
.subnav button{padding:8px 18px;margin:0;border:0;border-radius:var(--r-pill);background:none;font-size:12px;color:var(--c-muted);line-height:1.5;transition:color var(--t) var(--e-out),background var(--t) var(--e-out),transform var(--t-fast) var(--e-spring)}
.subnav button::after{border:0}
.subnav button.active{color:#fff;background:linear-gradient(135deg,#cd8465,var(--c-primary));box-shadow:0 6px 16px -6px rgba(166,96,63,.7)}
.subnav button:active{transform:scale(.94)}

/* ——— 底部导航 ——— */
.navigation{position:fixed;left:0;right:0;bottom:0;z-index:30;display:grid;grid-template-columns:repeat(6,1fr);gap:2px;padding:9px 12px calc(10px + env(safe-area-inset-bottom));background:var(--c-glass);-webkit-backdrop-filter:blur(26px) saturate(1.7);backdrop-filter:blur(26px) saturate(1.7);border-top:1px solid var(--c-glass-line);box-shadow:0 -10px 34px -20px rgba(58,52,46,.55)}
.navigation button{position:relative;display:flex;flex-direction:column;align-items:center;gap:3px;padding:5px 0;margin:0;border:0;background:none;color:var(--c-muted);font-size:10px;line-height:1.5;transition:color var(--t) var(--e-out),transform var(--t-fast) var(--e-spring)}
.navigation button::after{border:0}
.navigation button.active{color:var(--c-primary-deep);font-weight:640}
.navigation button:active{transform:scale(.9)}
.nav-icon{display:block;font-size:16px;line-height:1.3;transition:transform var(--t) var(--e-spring),color var(--t) var(--e-out)}
.navigation button.active .nav-icon{transform:translateY(-3px) scale(1.18);color:var(--c-primary)}
.navigation button.active::before{content:'';position:absolute;top:-1px;left:50%;width:16px;height:3px;margin-left:-8px;border-radius:2px;background:linear-gradient(90deg,var(--c-primary),var(--c-sand));animation:mf-pop 400ms var(--e-spring) both}
.nav-badge{position:absolute;top:-4px;right:calc(50% - 24px);min-width:16px;height:16px;line-height:16px;border-radius:8px;background:var(--c-danger);color:#fff;font-size:9px;text-align:center;padding:0 4px;box-shadow:0 2px 6px rgba(185,106,91,.5);animation:mf-pop 420ms var(--e-spring) both}

/* ——— 登录页 ——— */
.login-layout{position:relative;z-index:1;display:grid;grid-template-columns:1.05fr .95fr;gap:26px;align-items:center;max-width:980px;margin:auto;padding:40px 30px 60px;min-height:100vh}
.login-story{position:relative;overflow:hidden;min-height:420px;padding:38px 40px;border-radius:var(--r-xl);background:linear-gradient(135deg,#f3e3d4 0%,#e8d6c8 45%,#dfd0d4 100%);box-shadow:var(--sh-3);display:flex;flex-direction:column}
.login-story .wordmark{font-size:15px;font-weight:700;color:#a1795f;letter-spacing:2px}
.story-title{font-size:38px;font-weight:700;line-height:1.45;margin-top:22px;color:#43322a;letter-spacing:-1px}
.story-copy{font-size:13px;line-height:2;margin-top:16px;color:#8a7263}
.story-bottom{margin-top:auto;font-size:11px;letter-spacing:3px;color:#a08a79}
.story-art{position:absolute;right:10px;bottom:22px;width:250px;height:190px}
.story-art .house{bottom:34px;left:60px}
.story-art .roof{bottom:100px;left:53px}
.story-art .hill{bottom:-30px}
.login{padding:32px 30px;border-radius:var(--r-xl);background:var(--c-glass);-webkit-backdrop-filter:blur(24px) saturate(1.6);backdrop-filter:blur(24px) saturate(1.6);border:1px solid var(--c-glass-line);box-shadow:var(--sh-3),var(--sh-edge)}
.login .eyebrow{color:var(--c-primary);font-weight:640}
.auth-tabs{display:flex;gap:18px;margin:10px 0 4px;border-bottom:1px solid var(--c-glass-line)}
.auth-tab{padding-bottom:8px;font-size:15px;letter-spacing:2px;color:var(--c-muted);border-bottom:2px solid transparent}
.auth-tab.on{color:var(--c-primary);font-weight:650;border-bottom-color:var(--c-primary)}
.login-title{display:block;font-size:26px;font-weight:700;margin:10px 0 8px;color:var(--c-ink);letter-spacing:-.5px}
.description{display:block;font-size:12px;line-height:1.9;color:var(--c-muted);margin-bottom:22px}
.field-label{display:block;font-size:11px;color:var(--c-ink-2);margin:0 0 7px 4px;letter-spacing:.5px}
.input{width:100%;height:48px;padding:0 16px;margin-bottom:16px;border:1px solid var(--c-line);border-radius:var(--r-md);background:rgba(255,255,255,.75);font-size:14px;color:var(--c-ink);box-sizing:border-box;transition:border-color var(--t) var(--e-out),box-shadow var(--t) var(--e-out),background var(--t) var(--e-out)}
.input:focus{border-color:var(--c-primary);background:#fff;box-shadow:0 0 0 4px var(--c-primary-tint)}
.login-button{width:100%;margin-top:8px}
.login-note{display:block;margin-top:18px;text-align:center;font-size:11px;color:var(--c-muted)}

/* ——— 设置 ——— */
.settings{padding:26px}
.settings-title{display:block;font-size:17px;font-weight:650;color:var(--c-ink)}
.settings .description{margin-bottom:18px}
.logout{width:100%;height:44px;margin-top:12px;border:0;border-radius:var(--r-pill);background:var(--c-danger-soft);color:var(--c-danger);font-size:13px;line-height:44px;transition:transform var(--t-fast) var(--e-spring)}
.logout::after{border:0}
.logout:active{transform:scale(.97)}

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
  .page-title{font-size:24px}
  .hero{padding:28px 26px;min-height:160px}
  .hero-title{font-size:24px}
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
  .call-entry,.search-entry{padding:8px 11px;font-size:11px}
}
</style>
