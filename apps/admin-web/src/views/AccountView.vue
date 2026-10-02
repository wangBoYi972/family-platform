<script setup>
import { onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { request, clearSession } from "../api/request";
import { changePassword } from "../api/auth";

const emit = defineEmits(["navigate"]);
const rows = ref([]), loading = ref(false), saving = ref(false), creating = ref(false), resetTarget = ref(null);
const newAccount = ref({ phone: "", role: "MEMBER", password: "" });
const resetPassword = ref("");
const passwordForm = ref({ currentPassword: "", newPassword: "", confirmation: "" });
function validPassword(password) {
  return password.length >= 12 && password.length <= 64 && new TextEncoder().encode(password).length <= 72;
}
async function load() {
  loading.value = true;
  try { rows.value = await request("/users/whitelist"); }
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
onMounted(load);
</script>

<template>
  <main class="account-page">
    <header><div><h1>账号与家庭成员</h1><p>管理真实账号、访问权限和登录密码。</p></div><el-button @click="emit('navigate', '任务')">返回工作台</el-button></header>
    <section v-loading="loading">
      <div class="section-heading"><h2>成员账号</h2><el-button type="primary" @click="creating = true">新增账号</el-button></div>
      <el-table :data="rows" empty-text="暂无成员账号">
        <el-table-column prop="phone" label="手机号" min-width="150" />
        <el-table-column label="角色" width="100"><template #default="{ row }">{{ row.role === 'ADMIN' ? '管理员' : '成员' }}</template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="{ row }">{{ row.enabled ? '已启用' : '已停用' }}</template></el-table-column>
        <el-table-column label="操作" min-width="180"><template #default="{ row }"><el-button :disabled="!row.enabled" text @click="resetTarget = row; resetPassword = ''">设置密码</el-button><el-button :disabled="!row.enabled" text type="danger" @click="disable(row)">停用</el-button></template></el-table-column>
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
    <el-dialog :model-value="Boolean(resetTarget)" title="设置成员密码" width="min(440px, 92vw)" @update:model-value="value => { if (!value) { resetTarget = null; resetPassword = ''; } }">
      <p>将撤销该成员所有设备上的原有登录。</p><el-input v-model="resetPassword" type="password" autocomplete="new-password" maxlength="64" show-password placeholder="12 至 64 位新密码" />
      <template #footer><el-button @click="resetTarget = null; resetPassword = ''">取消</el-button><el-button type="primary" :loading="saving" @click="reset">确认设置</el-button></template>
    </el-dialog>
  </main>
</template>

<style scoped>
.account-page{max-width:1100px;margin:0 auto;padding:40px 24px}.account-page header,.section-heading{display:flex;align-items:center;justify-content:space-between;gap:16px}.account-page h1{font-size:28px}.account-page h2{font-size:20px}.account-page p{color:var(--family-muted);line-height:1.7}.account-page section{margin-top:36px}.password-section{border-top:1px solid var(--family-border);padding-top:20px}.password-section form{max-width:420px}
</style>
