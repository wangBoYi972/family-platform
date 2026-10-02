<script setup>
import { ref, watch } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { login, request } from "../../utils/request";
import TaskPanel from "../../components/TaskPanel.vue";
const taskPanel = ref(null);
const isArchive = false;
const phone = ref(""), password = ref(""), token = ref(uni.getStorageSync("family_access_token"));
const tasks = ref([]), entries = ref([]), tab = ref(isArchive ? "档案" : "任务"), loading = ref(false), submitting = ref(false);
const changingPassword = ref(false), currentPassword = ref(""), newPassword = ref(""), confirmation = ref("");
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
  if (!isArchive && tab.value === "任务") { taskPanel.value?.load(); return; }
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
  <view v-if="!token" class="login">
    <text class="kicker">家庭成员专属</text><text class="title">家庭综合平台</text><text class="desc">安全连接家人、任务和重要资料。</text>
    <input v-model="phone" class="input" maxlength="11" placeholder="手机号" />
    <input v-model="password" class="input" maxlength="64" password placeholder="账号密码" />
    <button class="primary" :loading="submitting" :disabled="submitting" @click="signIn">进入家庭</button>
  </view>
  <view v-else>
    <view class="header"><view><text class="kicker">{{ isArchive ? "私密资料" : "家庭空间" }}</text><text class="title">{{ isArchive ? "家庭档案" : "今日家庭安排" }}</text></view><text class="logout" @click="logout">退出</text></view>
    <button size="mini" @click="changingPassword = !changingPassword">修改密码</button>
    <view v-if="changingPassword" class="password-form">
      <input v-model="currentPassword" class="input" maxlength="64" password placeholder="当前密码" />
      <input v-model="newPassword" class="input" maxlength="64" password placeholder="新密码（12 至 64 位）" />
      <input v-model="confirmation" class="input" maxlength="64" password placeholder="再次输入新密码" />
      <button class="primary" :loading="submitting" :disabled="submitting" @click="changePassword">修改密码并重新登录</button>
    </view>
    <view class="tabs"><text v-for="item in (isArchive ? ['档案'] : ['任务','动态','公告'])" :key="item" :class="['tab',tab===item?'active':'']" @click="tab=item">{{ item }}</text></view>
    <TaskPanel v-if="tab === '任务' && !isArchive" ref="taskPanel" @session-ended="clearSession" />
    <view v-else-if="loading" class="empty">加载中…</view>
    <view v-else-if="tab==='任务'||isArchive" class="list"><view v-for="item in tasks" :key="item.id" class="row"><text class="row-title">{{ item.title }}</text><text class="row-meta">{{ item.status || item.category || item.visibility }}</text></view><view v-if="!tasks.length" class="empty">暂无记录</view></view>
    <view v-else class="list"><view v-for="item in entries" :key="item.id" class="row"><text class="row-title">{{ item.title }}</text><text class="row-meta">{{ item.body || "家庭成员可见" }}</text></view><view v-if="!entries.length" class="empty">暂无记录</view></view>
  </view>
</view>
</template>
<style scoped>
.page{min-height:100vh;padding:48rpx 40rpx;box-sizing:border-box}.login{padding-top:18vh}.kicker{display:block;color:var(--family-muted);font-size:22rpx;letter-spacing:4rpx}.title{display:block;margin-top:18rpx;font-size:54rpx;font-weight:600;line-height:1.2}.desc{display:block;margin:20rpx 0 68rpx;color:var(--family-muted);font-size:28rpx}.input{height:92rpx;padding:0 26rpx;margin-bottom:20rpx;background:#fff;border:1px solid var(--family-border);border-radius:12rpx;box-sizing:border-box}.primary{margin-top:20rpx;background:var(--family-primary);color:#fff;border-radius:12rpx}.header{display:flex;justify-content:space-between;align-items:flex-start}.logout{color:var(--family-muted);font-size:26rpx}.tabs{display:flex;gap:42rpx;margin:64rpx 0 26rpx;border-bottom:1px solid var(--family-border)}.tab{padding-bottom:20rpx;color:var(--family-muted)}.tab.active{color:var(--family-primary);border-bottom:4rpx solid var(--family-primary)}.row{display:flex;flex-direction:column;gap:12rpx;padding:30rpx 0;border-bottom:1px solid var(--family-border)}.row-title{font-size:32rpx;font-weight:600}.row-meta{color:var(--family-muted);font-size:25rpx}.empty{padding:80rpx 0;text-align:center;color:var(--family-muted)}
</style>
