<script setup>
import { ref, watch } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { login, request } from "../../utils/request";
const isArchive = true;
const phone = ref(""), password = ref(""), token = ref(uni.getStorageSync("family_access_token"));
const tasks = ref([]), entries = ref([]), tab = ref(isArchive ? "档案" : "任务"), loading = ref(false), submitting = ref(false);
const changingPassword = ref(false), currentPassword = ref(""), newPassword = ref(""), confirmation = ref("");
const now = new Date();
const dateText = `${now.getMonth() + 1} 月 ${now.getDate()} 日`;
const weekText = "周" + "日一二三四五六"[now.getDay()];
const hour = now.getHours();
const greeting = hour < 6 ? "夜深了" : hour < 11 ? "早上好" : hour < 14 ? "中午好" : hour < 18 ? "下午好" : "晚上好";
let loadSequence = 0;
function report(error) { if (error.status === 401) token.value = ""; uni.showToast({ title: error.message || "请求失败", icon: "none" }); }
async function signIn() {
  if (submitting.value) return;
  if (!/^1[0-9]{10}$/.test(phone.value) || !password.value) return uni.showToast({ title: "请输入正确手机号和密码", icon: "none" });
  submitting.value = true;
  try {
    const data = await login({ phone: phone.value, password: password.value, client: isArchive ? "archive" : "family" });
    uni.setStorageSync("family_access_token", data.token); token.value = data.token; password.value = ""; await load();
  } catch (error) { report(error); } finally { submitting.value = false; }
}
async function load() {
  if (!token.value) return;
  const sequence = ++loadSequence;
  loading.value = true;
  try {
    const path = isArchive ? "/archives" : tab.value === "任务" ? "/tasks" : tab.value === "公告" ? "/notices" : "/dynamics";
    const data = await request(path);
    if (sequence !== loadSequence) return;
    if (isArchive || tab.value === "任务") tasks.value = data; else entries.value = data;
  } catch (error) { if (sequence === loadSequence) report(error); }
  finally { if (sequence === loadSequence) loading.value = false; }
}
function clearSession() {
  loadSequence++; uni.removeStorageSync("family_access_token"); token.value = "";
  tasks.value = []; entries.value = []; changingPassword.value = false;
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
watch(tab, load);
onShow(load);
</script>
<template>
<view class="page">
  <!-- 登录：与家庭端、移动端同一套两栏版式 -->
  <view v-if="!token" class="login-layout">
    <view class="login-story mf-rise" style="--i:0">
      <text class="wordmark">一起 · 家庭档案</text>
      <text class="story-title">重要的事，<br/>都收在这里。</text>
      <text class="story-copy">证件、保单、体检与往来记录，<br/>只给家里人看。</text>
      <view class="story-art">
        <view class="sun"></view>
        <view class="roof"></view>
        <view class="house"><view class="window"></view><view class="door"></view></view>
        <view class="hill"></view>
      </view>
      <text class="story-bottom">档案 · 证件 · 健康 · 备忘</text>
    </view>
    <view class="login mf-rise" style="--i:2">
      <text class="eyebrow">{{ greeting }}，欢迎回来</text>
      <text class="login-title">进入家庭档案</text>
      <text class="description">使用管理员为你开通的家庭账号登录。</text>
      <text class="field-label">手机号</text>
      <input v-model="phone" class="input" type="number" maxlength="11" placeholder="请输入手机号" />
      <text class="field-label">账号密码</text>
      <input v-model="password" class="input" maxlength="64" password placeholder="请输入密码" @confirm="signIn" />
      <button class="mf-btn login-button" :loading="submitting" :disabled="submitting" @click="signIn">查看家庭档案</button>
      <text class="login-note">仅向家庭成员开放 · 每次访问留痕</text>
    </view>
  </view>

  <view v-else class="shell">
    <view class="brandbar">
      <text class="wordmark">一起<text class="brand-caption">家庭档案</text></text>
      <view class="brand-right">
        <view class="date-pill"><text class="date-main">{{ dateText }}</text><text class="date-sub">{{ weekText }}</text></view>
        <text class="pill-entry" @click="changingPassword = !changingPassword">修改密码</text>
        <text class="call-entry" @click="logout">退出</text>
      </view>
    </view>

    <view class="page-heading"><text class="page-title">家里的重要资料</text></view>

    <view class="pane">
      <view v-if="changingPassword" class="settings mf-card mf-rise" style="--i:0">
        <text class="settings-title">账号与安全</text>
        <text class="description">修改密码后，所有设备需要重新登录。</text>
        <input v-model="currentPassword" class="input" maxlength="64" password placeholder="当前密码" />
        <input v-model="newPassword" class="input" maxlength="64" password placeholder="新密码（12 至 64 位）" />
        <input v-model="confirmation" class="input" maxlength="64" password placeholder="再次输入新密码" />
        <button class="mf-btn" :loading="submitting" :disabled="submitting" @click="changePassword">更新密码</button>
      </view>

      <view v-if="loading" class="empty">加载中…</view>
      <view v-else-if="!tasks.length" class="empty">暂无档案记录</view>
      <view v-else class="archive-list">
        <view v-for="(item, index) in tasks" :key="item.id" class="archive-card mf-card mf-rise" :style="{'--i': index}">
          <view class="archive-head">
            <text class="archive-title">{{ item.title }}</text>
            <text class="mf-chip">{{ item.category || "档案" }}</text>
          </view>
          <text class="archive-meta">{{ item.visibility ? "可见范围：" + item.visibility : (item.status || "家庭成员可见") }}</text>
        </view>
      </view>
    </view>
  </view>
</view>
</template>

<style scoped>
.page{position:relative;min-height:100vh;overflow-x:hidden;background:transparent}
.shell{--pad:48px;position:relative;z-index:1;max-width:1180px;margin:auto;padding:0 var(--pad) 60px}
.brandbar{display:flex;align-items:center;justify-content:space-between;flex-wrap:nowrap;gap:16px;padding:40px 0 12px}
.wordmark{flex:0 0 auto;font-size:26px;font-weight:600;font-family:var(--font-display);color:#142B53;letter-spacing:.04em;white-space:nowrap}
.brand-caption{font-size:12px;font-weight:400;color:#4F7DC6;margin-left:12px;letter-spacing:.12em;white-space:nowrap}
.brand-right{display:flex;align-items:center;flex:0 0 auto;gap:12px}
.date-pill{display:flex;flex:0 0 auto;flex-direction:column;align-items:flex-end;line-height:1.3;padding:8px 14px;border-radius:30px;background:var(--liquid-surface);border:var(--liquid-outline);backdrop-filter:blur(20px);white-space:nowrap}
.date-main{font-size:14px;font-weight:600;color:#142B53;white-space:nowrap}
.date-sub{font-size:11px;color:#4F7DC6;white-space:nowrap}
.pill-entry{display:inline-flex;align-items:center;justify-content:center;flex:0 0 auto;font-size:12px;line-height:1;color:#142B53;background:var(--liquid-surface);border:var(--liquid-outline);border-radius:30px;padding:8px 14px;white-space:nowrap;backdrop-filter:blur(20px)}
.pill-entry:active{transform:translateY(1px)}
.call-entry{display:inline-flex;align-items:center;justify-content:center;flex:0 0 auto;font-size:12px;line-height:1;letter-spacing:.06em;color:#F4F7FF;background:#4F7DC6;border:1px solid rgba(255,255,255,.8);border-radius:30px;padding:8px 14px;white-space:nowrap}
.call-entry:active{transform:translateY(1px)}
.page-heading{padding:28px 0 4px}
.page-title{display:block;font-family:var(--font-display);font-size:52px;font-weight:500;margin-top:8px;letter-spacing:.02em;color:#142B53;line-height:1.3}
.page-title::after{content:"";display:block;width:64px;height:2px;margin-top:20px;background:#86BBFF}
.pane{margin-top:24px;animation:mf-fade 320ms var(--e-out) both}
.archive-list{display:grid;gap:16px}
.archive-card{padding:22px 24px;border-radius:30px}
.archive-head{display:flex;align-items:center;justify-content:space-between;gap:14px}
.archive-title{font-family:var(--font-display);font-size:19px;font-weight:600;color:#142B53;letter-spacing:.02em}
.archive-meta{display:block;margin-top:10px;font-size:13px;color:var(--c-muted)}
.settings{padding:26px;margin-bottom:20px}
.settings-title{display:block;font-size:17px;font-weight:650;color:var(--c-ink)}
.description{display:block;font-size:12px;line-height:1.9;color:var(--c-muted);margin-bottom:18px}
.field-label{display:block;font-size:11px;color:var(--c-ink-2);margin:0 0 7px 4px;letter-spacing:.5px}
.input{width:100%;height:46px;padding:0 14px;margin-bottom:16px;border:1px solid rgba(255,255,255,.8);border-radius:30px;background:rgba(255,255,255,.72);font-size:14px;color:#142B53;box-sizing:border-box}
.input:focus{border-color:var(--c-primary);background:#fff;outline:none}
.empty{padding:60px 0;text-align:center;color:var(--c-muted);font-size:13px}

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
.login-button{width:100%;margin-top:8px}
.login-note{display:block;margin-top:18px;text-align:center;font-size:11px;color:var(--c-muted)}

@media(max-width:860px){
  .login-layout{grid-template-columns:1fr;padding:26px 20px 50px}
  .login-story{min-height:230px;padding:26px}
  .story-title{font-size:29px;margin-top:18px}
  .story-copy,.story-bottom{display:none}
  .story-art{transform:scale(.62);transform-origin:right bottom;right:-6px;bottom:8px}
  .login{padding:26px 22px}
}
@media(max-width:700px){
  .shell{padding:0 20px 40px}
  .brandbar{padding:20px 0 14px;gap:10px}
  .wordmark{font-size:21px}
  .brand-caption{margin-left:8px;letter-spacing:1px}
  .brand-right{gap:8px}
  .page-title{font-size:34px}
}
@media(max-width:520px){
  .shell{padding:0 16px 36px}
  .brand-caption{display:none}
  .brand-right{gap:6px}
  .date-pill{padding:5px 10px}
  .date-main{font-size:11px}
  .date-sub{font-size:9px}
  .pill-entry,.call-entry{padding:8px 11px;font-size:11px}
}
</style>
