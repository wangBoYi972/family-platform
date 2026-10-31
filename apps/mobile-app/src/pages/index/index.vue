<script setup>
import { ref } from "vue";
import { login, request } from "../../utils/request";
// 面板与家庭端共用同一份实现（apps/shared/panels），移动端不再维护自己的简化版列表。
import TaskPanel from "@shared/panels/TaskPanel.vue";
import DynamicsPanel from "@shared/panels/DynamicsPanel.vue";
import NoticesPanel from "@shared/panels/NoticesPanel.vue";
import AlbumsPanel from "@shared/panels/AlbumsPanel.vue";
import FilesPanel from "@shared/panels/FilesPanel.vue";
import ArchivePanel from "@shared/panels/ArchivePanel.vue";
import CallPanel from "@shared/call/CallPanel.vue";

const TABS = ["任务", "动态", "公告", "相册", "文件", "档案"];
const taskPanel = ref(null), dynamicsPanel = ref(null), noticesPanel = ref(null), albumsPanel = ref(null), callPanel = ref(null);
function openCall() { callPanel.value?.openDial(); }
const phone = ref(""), password = ref(""), token = ref(uni.getStorageSync("family_access_token"));
const tab = ref("任务"), submitting = ref(false);
const changingPassword = ref(false), currentPassword = ref(""), newPassword = ref(""), confirmation = ref("");

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
    <view class="header"><view><text class="kicker">家庭空间</text><text class="title">今日家庭安排</text></view><text class="logout" @click="logout">退出</text></view>
    <button size="mini" @click="changingPassword = !changingPassword">修改密码</button>
    <button size="mini" @click="openCall">☎ 通话</button>
    <view v-if="changingPassword" class="password-form">
      <input v-model="currentPassword" class="input" maxlength="64" password placeholder="当前密码" />
      <input v-model="newPassword" class="input" maxlength="64" password placeholder="新密码（12 至 64 位）" />
      <input v-model="confirmation" class="input" maxlength="64" password placeholder="再次输入新密码" />
      <button class="primary" :loading="submitting" :disabled="submitting" @click="changePassword">修改密码并重新登录</button>
    </view>
    <view class="tabs"><text v-for="item in TABS" :key="item" :class="['tab', tab === item ? 'active' : '']" @click="tab = item">{{ item }}</text></view>
    <TaskPanel v-if="tab === '任务'" ref="taskPanel" @session-ended="clearSession" />
    <DynamicsPanel v-else-if="tab === '动态'" ref="dynamicsPanel" :token="token" />
    <NoticesPanel v-else-if="tab === '公告'" ref="noticesPanel" :token="token" />
    <AlbumsPanel v-else-if="tab === '相册'" ref="albumsPanel" :token="token" />
    <FilesPanel v-else-if="tab === '文件'" @session-ended="clearSession" />
    <ArchivePanel v-else @session-ended="clearSession" />
    <CallPanel ref="callPanel" :active="!!token" />
  </view>
</view>
</template>

<style scoped>
.page{min-height:100vh;padding:48rpx 40rpx;box-sizing:border-box}.login{padding-top:18vh}.kicker{display:block;color:var(--family-muted);font-size:22rpx;letter-spacing:4rpx}.title{display:block;margin-top:18rpx;font-size:54rpx;font-weight:600;line-height:1.2}.desc{display:block;margin:20rpx 0 68rpx;color:var(--family-muted);font-size:28rpx}.input{height:92rpx;padding:0 26rpx;margin-bottom:20rpx;background:#fff;border:1px solid var(--family-border);border-radius:12rpx;box-sizing:border-box}.primary{margin-top:20rpx;background:var(--family-primary);color:#fff;border-radius:12rpx}.header{display:flex;justify-content:space-between;align-items:flex-start}.logout{color:var(--family-muted);font-size:26rpx}.tabs{display:flex;gap:32rpx;margin:64rpx 0 26rpx;border-bottom:1px solid var(--family-border);overflow-x:auto;white-space:nowrap}.tab{flex-shrink:0;padding-bottom:20rpx;color:var(--family-muted);font-size:28rpx}.tab.active{color:var(--family-primary);border-bottom:4rpx solid var(--family-primary)}.empty{padding:80rpx 0;text-align:center;color:var(--family-muted)}
</style>
