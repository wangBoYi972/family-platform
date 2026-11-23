<script setup>
import { onBeforeUnmount, onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { request, clearSession } from "../api/request";
import { changePassword } from "../api/auth";

const emit = defineEmits(["navigate"]);
const rows = ref([]), loading = ref(false), saving = ref(false), creating = ref(false), resetTarget = ref(null);
const newAccount = ref({ phone: "", role: "MEMBER", password: "" });
// 关键字搜索：下推服务端在 SQL 里匹配手机号/角色；300ms 防抖避免每敲一个字就发请求
const keyword = ref("");
let searchTimer = null;
function onKeywordInput() {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(load, 300);
}
onBeforeUnmount(() => clearTimeout(searchTimer));
const inviteRows = ref([]);
const inviteIssuing = ref(false);
const inviteCreating = ref(false);
const issuedCode = ref("");
const newInvite = ref({ role: "MEMBER", maxUses: 1, expiresInHours: 24 });
const passwordForm = ref({ currentPassword: "", newPassword: "", confirmation: "" });
const resetPassword = ref("");
function validPassword(password) {
  return password.length >= 12 && password.length <= 64 && new TextEncoder().encode(password).length <= 72;
}
async function load() {
  loading.value = true;
  try { rows.value = await request("/users/whitelist" + (keyword.value.trim() ? "?keyword=" + encodeURIComponent(keyword.value.trim()) : "")); }
  catch (error) { ElMessage.error(error.message); }
  finally { loading.value = false; }
}
async function createAccount() {
  if (saving.value) return;
  if (!/^1\d{10}$/.test(newAccount.value.phone) || !validPassword(newAccount.value.password)) return ElMessage.warning("请填写正确手机号及 12 至 64 位密码，中文密码不超过 72 字节");
  saving.value = true;
  try {
    await request("/users/accounts", { method: "POST", data: newAccount.value });
    creating.value = false; newAccount.value = { phone: "", role: "MEMBER", password: "" };
    ElMessage.success("账号已创建，请通过安全渠道将初始密码告知本人"); await load();
  } catch (error) { ElMessage.error(error.message); }
  finally { saving.value = false; }
}
async function loadInvites() {
  try { inviteRows.value = await request("/users/invite-codes"); }
  catch (error) { ElMessage.error(error.message); }
}
async function issueInvite() {
  if (inviteIssuing.value) return;
  inviteIssuing.value = true;
  try {
    const issued = await request("/users/invite-codes", { method: "POST", data: newInvite.value });
    inviteCreating.value = false;
    issuedCode.value = issued.code;
    newInvite.value = { role: "MEMBER", maxUses: 1, expiresInHours: 24 };
    await loadInvites();
  } catch (error) { ElMessage.error(error.message); }
  finally { inviteIssuing.value = false; }
}
async function copyIssued() {
  try { await navigator.clipboard.writeText(issuedCode.value); ElMessage.success("邀请码已复制"); }
  catch { ElMessage.warning("复制失败，请手动记下"); }
}
function inviteState(row) {
  if (!row.enabled) return "已作废";
  if (row.expired) return "已过期";
  return row.usedCount >= row.maxUses ? "已用完" : "可用";
}
async function revokeInvite(row) {
  try {
    await ElMessageBox.confirm("作废后该邀请码立即失效，已注册账号不受影响。", "作废邀请码", { confirmButtonText: "确认作废", cancelButtonText: "取消", type: "warning" });
    await request("/users/invite-codes/" + row.id, { method: "DELETE" });
    await loadInvites();
  } catch (error) { if (error instanceof Error) ElMessage.error(error.message); }
}
async function reset() {
  if (saving.value || !resetTarget.value) return;
  if (!validPassword(resetPassword.value)) return ElMessage.warning("密码需要 12 至 64 位，且不超过 72 字节");
  saving.value = true;
  try {
    await request("/users/whitelist/" + resetTarget.value.id + "/password", { method: "PUT", data: { password: resetPassword.value } });
    resetTarget.value = null; resetPassword.value = "";
    ElMessage.success("密码已设置，原有登录已全部失效");
    await request("/auth/session");
  } catch (error) { ElMessage.error(error.message); }
  finally { saving.value = false; }
}
async function disable(row) {
  try {
    await ElMessageBox.confirm("确认停用 " + row.phone + "？该成员将无法继续访问家庭数据。", "停用账号", { confirmButtonText: "确认停用", cancelButtonText: "取消", type: "warning" });
    await request("/users/whitelist/" + row.id, { method: "DELETE" }); await load();
  } catch (error) { if (error instanceof Error) ElMessage.error(error.message); }
}
async function change() {
  if (saving.value) return;
  const form = passwordForm.value;
  if (!form.currentPassword || !validPassword(form.newPassword) || form.newPassword !== form.confirmation) return ElMessage.warning("请填写当前密码，并确认两次新密码一致且符合长度要求");
  saving.value = true;
  try {
    await changePassword({ currentPassword: form.currentPassword, newPassword: form.newPassword });
    passwordForm.value = { currentPassword: "", newPassword: "", confirmation: "" };
    clearSession(); ElMessage.success("密码已更新，请使用新密码重新登录");
  } catch (error) { ElMessage.error(error.message); }
  finally { saving.value = false; }
}
onMounted(async () => { await load(); await loadInvites(); });
</script>

<template>
  <main class="account-page">
    <header><div><h1>账号与家庭成员</h1><p>管理真实账号、访问权限和登录密码。</p></div><el-button @click="emit('navigate', '任务')">返回工作台</el-button></header>
    <section v-loading="loading">
      <div class="section-heading"><h2>成员账号</h2><el-input v-model="keyword" class="member-search" placeholder="搜索手机号或角色" clearable maxlength="30" @input="onKeywordInput" @clear="load" @keyup.enter="load" /><el-button type="primary" @click="creating = true">新增账号</el-button></div>
      <el-table :data="rows" empty-text="暂无成员账号">
        <el-table-column prop="phone" label="手机号" min-width="150" />
        <el-table-column label="角色" width="100"><template #default="{ row }">{{ row.role === 'ADMIN' ? '管理员' : '成员' }}</template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="{ row }">{{ row.enabled ? '已启用' : '已停用' }}</template></el-table-column>
        <el-table-column label="操作" min-width="180"><template #default="{ row }"><el-button :disabled="!row.enabled" text @click="resetTarget = row; resetPassword = ''">设置密码</el-button><el-button :disabled="!row.enabled" text type="danger" @click="disable(row)">停用</el-button></template></el-table-column>
      </el-table>
    </section>
    <section>
      <div class="section-heading"><h2>注册邀请码</h2><el-button type="primary" @click="inviteCreating = true">发放邀请码</el-button></div>
      <p>注册对所有人开放，但没有邀请码就无法创建账号。邀请码只显示这一次，请直接发给本人。</p>
      <el-table :data="inviteRows" empty-text="暂未发放邀请码">
        <el-table-column label="邀请码" min-width="130"><template #default="{ row }">FAM-****-{{ row.hint }}</template></el-table-column>
        <el-table-column label="角色" width="90"><template #default="{ row }">{{ row.role === 'ADMIN' ? '管理员' : '成员' }}</template></el-table-column>
        <el-table-column label="可用次数" width="100"><template #default="{ row }">{{ row.usedCount }} / {{ row.maxUses }}</template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="{ row }">{{ inviteState(row) }}</template></el-table-column>
        <el-table-column prop="expiresAt" label="有效期至" min-width="140"><template #default="{ row }">{{ row.expiresAt || '长期有效' }}</template></el-table-column>
        <el-table-column prop="usedPhone" label="使用者" min-width="130"><template #default="{ row }">{{ row.usedPhone || '—' }}</template></el-table-column>
        <el-table-column label="操作" min-width="110"><template #default="{ row }"><el-button :disabled="!row.enabled" text type="danger" @click="revokeInvite(row)">作废</el-button></template></el-table-column>
      </el-table>
    </section>
    <section class="password-section">
      <h2>修改本人密码</h2><p>修改后所有设备需要重新登录。密码为 12 至 64 个字符，UTF-8 编码不超过 72 字节。</p>
      <el-form label-position="top" @submit.prevent="change">
        <el-form-item label="当前密码"><el-input v-model="passwordForm.currentPassword" type="password" autocomplete="current-password" maxlength="64" show-password /></el-form-item>
        <el-form-item label="新密码"><el-input v-model="passwordForm.newPassword" type="password" autocomplete="new-password" maxlength="64" show-password /></el-form-item>
        <el-form-item label="确认新密码"><el-input v-model="passwordForm.confirmation" type="password" autocomplete="new-password" maxlength="64" show-password /></el-form-item>
        <el-button native-type="submit" type="primary" :loading="saving">修改密码并退出</el-button>
      </el-form>
    </section>
    <el-dialog v-model="creating" title="新增成员账号" width="min(440px, 92vw)" @closed="newAccount = { phone: '', role: 'MEMBER', password: '' }">
      <el-form label-position="top"><el-form-item label="手机号"><el-input v-model="newAccount.phone" maxlength="11" /></el-form-item><el-form-item label="角色"><el-select v-model="newAccount.role"><el-option label="成员" value="MEMBER" /><el-option label="管理员" value="ADMIN" /></el-select></el-form-item><el-form-item label="初始密码（12 至 64 位）"><el-input v-model="newAccount.password" type="password" autocomplete="new-password" maxlength="64" show-password /></el-form-item></el-form>
      <template #footer><el-button @click="creating = false">取消</el-button><el-button type="primary" :loading="saving" @click="createAccount">创建账号</el-button></template>
    </el-dialog>
    <el-dialog v-model="inviteCreating" title="发放注册邀请码" width="min(440px, 92vw)" @closed="newInvite = { role: 'MEMBER', maxUses: 1, expiresInHours: 24 }">
      <el-form label-position="top">
        <el-form-item label="注册后角色"><el-select v-model="newInvite.role"><el-option label="成员" value="MEMBER" /><el-option label="管理员" value="ADMIN" /></el-select></el-form-item>
        <el-form-item label="可注册人数"><el-input-number v-model="newInvite.maxUses" :min="1" :max="10" /></el-form-item>
        <el-form-item label="有效期（小时）"><el-input-number v-model="newInvite.expiresInHours" :min="1" :max="720" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="inviteCreating = false">取消</el-button><el-button type="primary" :loading="inviteIssuing" @click="issueInvite">生成邀请码</el-button></template>
    </el-dialog>
    <el-dialog :model-value="Boolean(issuedCode)" title="邀请码已生成" width="min(440px, 92vw)" @update:model-value="value => { if (!value) issuedCode = ''; }">
      <p>这串邀请码只显示这一次，关闭后无法再查看，请立即复制并发送给本人。</p>
      <div class="issued-code"><code>{{ issuedCode }}</code><el-button text @click="copyIssued">复制</el-button></div>
      <template #footer><el-button type="primary" @click="issuedCode = ''">我已记下</el-button></template>
    </el-dialog>
    <el-dialog :model-value="Boolean(resetTarget)" title="设置成员密码" width="min(440px, 92vw)" @update:model-value="value => { if (!value) { resetTarget = null; resetPassword = ''; } }">
      <p>将撤销该成员所有设备上的原有登录。</p><el-input v-model="resetPassword" type="password" autocomplete="new-password" maxlength="64" show-password placeholder="12 至 64 位新密码" />
      <template #footer><el-button @click="resetTarget = null; resetPassword = ''">取消</el-button><el-button type="primary" :loading="saving" @click="reset">确认设置</el-button></template>
    </el-dialog>
  </main>
</template>

<style scoped>
.account-page{max-width:980px}.account-page header,.section-heading{display:flex;align-items:center;justify-content:space-between;gap:16px}.account-page h1{font-size:28px;font-weight:700;letter-spacing:-.02em}.account-page h2{font-size:20px;font-weight:650}.account-page p{color:var(--c-muted);line-height:1.7}.account-page h1,.account-page h2{font-family:var(--font-display)}.account-page section{margin-top:28px;padding:22px 24px;border-radius:30px;background:rgba(255,255,255,.54);border:1px solid rgba(255,255,255,.8);backdrop-filter:blur(20px)}.password-section form{max-width:420px}
.member-search{max-width:220px}
.issued-code{display:flex;align-items:center;gap:10px;margin-top:6px;padding:12px 14px;border-radius:10px;background:var(--c-bg-2,rgba(79,125,198,.06));border:1px dashed var(--c-glass-line)}
.issued-code code{font-size:18px;letter-spacing:.08em;font-weight:650}
</style>
