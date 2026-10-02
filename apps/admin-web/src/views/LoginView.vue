<script setup>
import { ref } from "vue";
import { ElMessage } from "element-plus";
import { login } from "../api/auth";

const emit = defineEmits(["authenticated"]);
const form = ref({ phone: "", password: "" });
const submitting = ref(false);

async function submit() {
  if (submitting.value) return;
  if (!/^1\d{10}$/.test(form.value.phone) || !form.value.password) {
    ElMessage.warning("请输入正确的手机号和密码");
    return;
  }
  submitting.value = true;
  try {
    await login(form.value);
    form.value.password = "";
    emit("authenticated");
  } catch (error) {
    ElMessage.error(error.message || "登录失败");
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <main class="login-shell">
    <section class="login-panel">
      <div class="brand"><span class="brand-mark">家</span><strong>家庭综合平台</strong></div>
      <div class="intro"><p class="eyebrow">家庭成员专属</p><h1>回到家里的每一件事</h1><p>仅限已加入家庭白名单的成员登录使用。</p></div>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="手机号"><el-input v-model="form.phone" inputmode="tel" autocomplete="username" maxlength="11" placeholder="请输入白名单手机号" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="form.password" type="password" show-password autocomplete="current-password" maxlength="64" placeholder="请输入账号密码" /></el-form-item>
        <el-button native-type="submit" type="primary" :loading="submitting" class="submit-button">登录</el-button>
      </el-form>
      <small>内部家庭平台，不提供公开注册。</small>
    </section>
  </main>
</template>

<style scoped>
.login-shell { display: grid; min-height: 100vh; place-items: center; padding: 32px; background: var(--family-surface); }
.login-panel { width: min(100%, 400px); padding: 40px; background: #fff; border: 1px solid var(--family-border); border-radius: 8px; }
.brand { display: flex; align-items: center; gap: 10px; font-size: 15px; }.brand-mark { display: grid; width: 28px; height: 28px; place-items: center; color: #fff; background: var(--family-primary); border-radius: 6px; font-size: 14px; }.intro { margin: 48px 0 32px; }.eyebrow { margin: 0 0 10px; color: var(--family-muted); font-size: 12px; letter-spacing: .08em; text-transform: uppercase; }.intro h1 { margin: 0; font-size: 28px; line-height: 1.25; font-weight: 600; letter-spacing: -.02em; }.intro p:last-child { margin: 12px 0 0; color: var(--family-muted); line-height: 1.7; }.submit-button { width: 100%; margin-top: 8px; }.login-panel > small { display: block; margin-top: 24px; color: var(--family-muted); font-size: 12px; }
</style>
