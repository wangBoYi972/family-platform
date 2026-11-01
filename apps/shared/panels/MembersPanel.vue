<script setup>
import { computed, onMounted, ref } from "vue";
import { request, roleLabel } from "@app/utils/request";

const props = defineProps({ token: String });
const emit = defineEmits(["chat"]);
const members = ref([]), whitelist = ref([]), profile = ref(null);
const loading = ref(false), busy = ref(false), error = ref("");
const editingName = ref(false), nickname = ref("");
const inviting = ref(false), inviteForm = ref({ phone: "", role: "MEMBER" });
const roles = [{ value: "MEMBER", label: "成员" }, { value: "ADMIN", label: "管理员" }];

const isAdmin = computed(() => profile.value?.role === "ADMIN");
const inviteRoleLabel = computed(() => roles.find(item => item.value === inviteForm.value.role)?.label || "成员");
const enabledWhitelist = computed(() => whitelist.value.filter(item => item.enabled !== false));

function report(error) { uni.showToast({ title: error.message || "加载失败", icon: "none" }); }
async function load() {
  if (!props.token) return;
  loading.value = true;
  error.value = "";
  try {
    const [people, identity] = await Promise.all([request("/users/members"), request("/users/me")]);
    members.value = people;
    profile.value = identity;
    nickname.value = identity.displayName || "";
    if (identity.role === "ADMIN") await loadWhitelist();
  } catch (e) {
    error.value = e.message || "成员信息加载失败";
    report(e);
  } finally { loading.value = false; }
}
async function loadWhitelist() {
  try { whitelist.value = await request("/users/whitelist"); }
  catch (e) { report(e); }
}
async function submitWhitelist() { await loadWhitelist(); inviteForm.value = { phone: "", role: "MEMBER" }; inviting.value = false; }
async function saveNickname() {
  if (busy.value) return;
  const value = nickname.value.trim();
  if (!value) return uni.showToast({ title: "昵称不能为空", icon: "none" });
  busy.value = true;
  try {
    await request("/users/me", { method: "PATCH", data: { displayName: value } });
    uni.showToast({ title: "资料已更新", icon: "none" });
    editingName.value = false;
    await load();
  } catch (e) { report(e); }
  finally { busy.value = false; }
}
async function invite() {
  if (busy.value) return;
  if (!/^1[0-9]{10}$/.test(inviteForm.value.phone)) return uni.showToast({ title: "请输入正确的手机号", icon: "none" });
  busy.value = true;
  try {
    await request("/users/whitelist", { method: "POST", data: { phone: inviteForm.value.phone, role: inviteForm.value.role } });
    uni.showToast({ title: "已开通，家人可用该手机号登录", icon: "none" });
    await submitWhitelist();
  } catch (e) { report(e); }
  finally { busy.value = false; }
}
async function disable(entry) {
  if (busy.value) return;
  try {
    const result = await uni.showModal({ title: "停用成员", content: `停用后 ${entry.phone} 将无法登录家庭空间，确定继续吗？`, confirmText: "停用" });
    if (!result.confirm) return;
    busy.value = true;
    await request("/users/whitelist/" + entry.id, { method: "DELETE" });
    await submitWhitelist();
  } catch (e) { if (!String(e?.errMsg || "").includes("cancel")) report(e); }
  finally { busy.value = false; }
}
function chat(item) { emit("chat", item); }
onMounted(load);
// 不分页的面板：sync 与 load 等价，统一方法名好让宿主一视同仁。
defineExpose({ load, sync: load });
</script>

<template>
  <view class="panel">
    <view v-if="profile" class="card profile">
      <view class="avatar">{{ (profile.displayName || "?").slice(0, 1) }}</view>
      <view class="profile-body">
        <view v-if="!editingName" class="profile-line"><text class="member-name">{{ profile.displayName }}</text><button size="mini" @click="editingName = true">修改昵称</button></view>
        <view v-else class="profile-line"><input v-model="nickname" class="input" maxlength="64" placeholder="家人看到的名字" :disabled="busy" /><button size="mini" class="primary" :loading="busy" @click="saveNickname">保存</button><button size="mini" :disabled="busy" @click="editingName = false">取消</button></view>
        <text class="member-role">{{ profile.householdName || '我们的家' }} · {{ roleLabel[profile.role] || profile.role }}</text>
      </view>
    </view>

    <view v-if="error" class="state"><text>{{ error }}</text><button size="mini" @click="load">重试</button></view>
    <view v-if="loading" class="state">正在加载成员…</view>

    <template v-if="!loading">
      <view class="toolbar"><text class="section">家庭成员</text><text class="count">{{ members.length }} 人</text></view>
      <view v-if="!members.length" class="state">暂无成员</view>
      <view v-else class="list">
        <view v-for="item in members" :key="item.id" class="row card">
          <view class="avatar small">{{ (item.displayName || "?").slice(0, 1) }}</view>
          <view class="member">
            <text class="member-name">{{ item.displayName }}</text>
            <text class="member-role">{{ roleLabel[item.role] || item.role }}</text>
          </view>
          <button size="mini" @click="chat(item)">聊聊</button>
        </view>
      </view>

      <view v-if="isAdmin" class="manage">
        <view class="toolbar"><text class="section">家人账号管理</text><button size="mini" @click="inviting = !inviting">{{ inviting ? "收起" : "＋ 邀请家人" }}</button></view>
        <text class="hint">管理员开通手机号后，家人即可使用该号码登录家庭空间。</text>
        <view v-if="inviting" class="card form">
          <input v-model="inviteForm.phone" class="input" type="number" maxlength="11" placeholder="家人手机号" :disabled="busy" />
          <view class="chips">
            <button v-for="item in roles" :key="item.value" size="mini" :class="{ primary: inviteForm.role === item.value }" :disabled="busy" @click="inviteForm.role = item.value">{{ item.label }}</button>
          </view>
          <text class="hint">当前选择：{{ inviteRoleLabel }}</text>
          <button class="primary block" :loading="busy" :disabled="busy" @click="invite">开通账号</button>
        </view>
        <view v-if="!enabledWhitelist.length" class="state">白名单为空</view>
        <view v-else class="list">
          <view v-for="item in enabledWhitelist" :key="item.id" class="row card">
            <view class="member"><text class="member-name">{{ item.phone }}</text><text class="member-role">{{ roleLabel[item.role] || item.role }}</text></view>
            <button size="mini" class="danger" :disabled="busy" @click="disable(item)">停用</button>
          </view>
        </view>
      </view>
    </template>
  </view>
</template>

<style scoped>
.panel{display:flex;flex-direction:column;gap:18px}
.card{background:var(--c-surface);border:1px solid var(--c-line);border-radius:14px;padding:16px}
.profile{display:flex;align-items:center;gap:14px}
.avatar{width:64px;height:64px;border-radius:50%;background:var(--family-primary);color:var(--c-surface);text-align:center;line-height:64px;font-size:26px;flex-shrink:0}
.avatar.small{width:52px;height:52px;line-height:52px;font-size:22px}
.profile-body{flex:1;min-width:0}
.profile-line{display:flex;align-items:center;gap:8px}
.input{flex:1;height:40px;box-sizing:border-box;background:var(--c-surface-2);border:1px solid var(--c-line);border-radius:10px;padding:0 12px;font-size:13px}
.toolbar{display:flex;align-items:flex-end;justify-content:space-between;gap:10px}
.section{font-size:16px;font-weight:650}
.count,.member-role,.hint{font-size:12px;color:var(--c-muted)}
.hint{display:block;margin-top:4px;line-height:1.8}
.list{display:flex;flex-direction:column;gap:10px}
.row{display:flex;align-items:center;gap:14px}
.member{flex:1;display:flex;flex-direction:column;gap:4px;min-width:0}
.member-name{font-size:15px;font-weight:600;overflow-wrap:anywhere}
.manage{display:flex;flex-direction:column;gap:12px;padding-top:14px;border-top:1px solid var(--c-surface-2)}
.form{display:flex;flex-direction:column;gap:10px}
.chips{display:flex;gap:8px}
.chips button{margin:0;font-size:12px;background:var(--c-primary-soft);color:var(--c-primary);border-radius:11px}
.chips .primary{color:white}
.primary{background:var(--c-primary);color:white}
.block{width:100%;height:40px;line-height:40px;font-size:13px}
.danger{background:var(--c-danger-soft);color:var(--c-danger)}
.state{text-align:center;color:var(--c-muted);font-size:13px;padding:16px 0}
.state button{margin-left:8px;font-size:11px}
button{margin:0}
</style>
