<script setup>
import { ref } from "vue";
import { ElMessage } from "element-plus";
import { login, register } from "../api/auth";
import BrandMark from "../components/BrandMark.vue";

const emit = defineEmits(["authenticated"]);
const form = ref({ phone: "", password: "" });
const registerForm = ref({ phone: "", inviteCode: "", password: "", confirmation: "", displayName: "" });
const mode = ref("login");
const submitting = ref(false);

function validPassword(password) {
  return password.length >= 12 && password.length <= 64 && new TextEncoder().encode(password).length <= 72;
}
function switchMode(next) {
  if (mode.value === next) return;
  mode.value = next;
  registerForm.value = { phone: "", inviteCode: "", password: "", confirmation: "", displayName: "" };
}

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

async function submitRegister() {
  if (submitting.value) return;
  const body = registerForm.value;
  if (!/^1\d{10}$/.test(body.phone)) return ElMessage.warning("请输入正确的手机号");
  if (!body.inviteCode.trim()) return ElMessage.warning("注册必须填写管理员发放的邀请码");
  if (!validPassword(body.password)) return ElMessage.warning("密码需要 12 至 64 位，且不超过 72 字节");
  if (body.password !== body.confirmation) return ElMessage.warning("两次输入的密码不一致");
  submitting.value = true;
  try {
    await register({
      phone: body.phone,
      inviteCode: body.inviteCode.trim().toUpperCase(),
      password: body.password,
      displayName: body.displayName.trim() || undefined,
      client: "admin"
    });
    registerForm.value = { phone: "", inviteCode: "", password: "", confirmation: "", displayName: "" };
    ElMessage.success("注册成功，已自动进入");
    emit("authenticated");
  } catch (error) {
    ElMessage.error(error.message || "注册失败");
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <main class="login-shell">
    <section class="book mf-rise">
      <!-- 左：扉页 -->
      <aside class="page page--left">
        <header class="brand">
          <BrandMark :size="26" class="brand-glyph" />
          <strong>把时间，留给家人</strong>
        </header>

        <div class="copy">
          <span class="stamp">家 庭 簿 · 扉 页</span>
          <h1>家里的事，<br />记在<em class="circled">同一个本子</em>上。</h1>
          <p class="sub">日程、相册、档案——全家的零碎事，摊开就在这一页。</p>
        </div>

        <!-- 手绘线稿：一栋小房子 -->
        <svg class="sketch" viewBox="0 0 300 190" fill="none" stroke="#54473d" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M197 46 l0.8 -19 l15 0.8 l-0.9 21" />
          <path d="M50 97 C 84 68, 118 43, 149 27 C 182 45, 216 71, 251 101" />
          <path d="M64 95 C 92 71, 121 49, 148 34 C 173 49, 202 72, 232 98" />
          <path d="M75 91 C 75 115, 75.3 139, 75.7 162 C 126 163.5, 178 163.4, 227 161.8 C 227.3 139, 227.6 116, 227.8 94" />
          <path d="M136 162 C 136.3 148, 136.7 135, 137.2 124 C 146.6 119.4, 158 119.6, 166.6 124 C 167.1 135, 167.5 148, 167.8 162" />
          <circle cx="158.4" cy="140" r="1.6" fill="#54473d" stroke="none" />
          <rect x="95" y="109" width="25" height="25" rx="2.5" />
          <path d="M107.5 109.6 v23.6 M95.6 121.5 h23.8" />
          <path d="M205 15 c-5 -4, -3.5 -9, 1.6 -11.5" />
          <circle cx="266" cy="38" r="11" />
          <path d="M266 20.5 v-5.5 M266 55.5 v5.5 M283.5 38 h5.5 M248.5 38 h-5.5 M278.5 25.5 l4 -4 M253.5 50.5 l-4 4 M278.5 50.5 l4 4 M253.5 25.5 l-4 4" stroke-width="1.8" />
          <path d="M42 165 c 56 6, 118 5.5, 216 -1.5" />
          <path d="M56 164.5 c -1 -9, 4 -14.5, 12 -16.5 M55.6 164.6 c -8.5 -6.5, -15.5 -7.5, -22 -5.5" />
        </svg>

        <ul class="ledger">
          <li><i>壹</i><span class="item">日程与提醒</span><span class="dots"></span><em>先看今天</em></li>
          <li><i>贰</i><span class="item">照片与纪念</span><span class="dots"></span><em>常回来翻翻</em></li>
          <li><i>叁</i><span class="item">成员与档案</span><span class="dots"></span><em>只给自家人</em></li>
        </ul>
      </aside>

      <!-- 右：登记 -->
      <section class="page page--right">
        <div class="tabs" role="tablist">
          <button type="button" role="tab" :class="{ on: mode === 'login' }" :aria-selected="mode === 'login'" @click="switchMode('login')">登录</button>
          <button type="button" role="tab" :class="{ on: mode === 'register' }" :aria-selected="mode === 'register'" @click="switchMode('register')">注册</button>
        </div>
        <h2>{{ mode === "login" ? "登 录" : "注 册" }}</h2>
        <p class="hint">{{ mode === "login" ? "用管理员给你开的账号进来。" : "向管理员要一枚邀请码，填进来才能开账号。" }}</p>
        <el-form v-if="mode === 'login'" label-position="top" @submit.prevent="submit">
          <el-form-item label="手机号">
            <el-input v-model="form.phone" inputmode="tel" autocomplete="username" maxlength="11" placeholder="白名单手机号" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="form.password" type="password" show-password autocomplete="current-password" maxlength="64" placeholder="账号密码" />
          </el-form-item>
          <el-button native-type="submit" type="primary" :loading="submitting" class="submit-button">进入</el-button>
        </el-form>
        <el-form v-else label-position="top" @submit.prevent="submitRegister">
          <el-form-item label="邀请码" required>
            <el-input v-model="registerForm.inviteCode" maxlength="24" placeholder="如 FAM-7K2M-QX4P" />
          </el-form-item>
          <el-form-item label="手机号" required>
            <el-input v-model="registerForm.phone" inputmode="tel" autocomplete="username" maxlength="11" placeholder="11 位手机号" />
          </el-form-item>
          <el-form-item label="昵称">
            <el-input v-model="registerForm.displayName" maxlength="32" placeholder="选填，默认「家庭成员」" />
          </el-form-item>
          <el-form-item label="密码" required>
            <el-input v-model="registerForm.password" type="password" show-password autocomplete="new-password" maxlength="64" placeholder="12 至 64 位" />
          </el-form-item>
          <el-form-item label="确认密码" required>
            <el-input v-model="registerForm.confirmation" type="password" show-password autocomplete="new-password" maxlength="64" placeholder="再输一次" />
          </el-form-item>
          <el-button native-type="submit" type="primary" :loading="submitting" class="submit-button">注册并进入</el-button>
        </el-form>
        <small>{{ mode === "login" ? "仅限家庭成员 · 注册请向管理员索取邀请码" : "邀请码由管理员发放，只可用一次，错码无法注册" }}</small>
      </section>
    </section>
  </main>
</template>

<style scoped>
.login-shell {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 44px 28px;
  background-color: var(--c-bg);
  /* 点阵网格：像手账本的内页 */
  background-image: radial-gradient(rgba(58, 52, 46, 0.07) 1px, transparent 1.4px);
  background-size: 24px 24px;
}

/* ——— 整册纸面 ——— */
.book {
  display: grid;
  grid-template-columns: 1.12fr 0.88fr;
  width: min(1000px, 100%);
  background: #fffdf8;
  border: 1px solid rgba(58, 52, 46, 0.18);
  border-radius: 6px;
  box-shadow: 0 1px 0 rgba(58, 52, 46, 0.06), 0 18px 40px -18px rgba(58, 52, 46, 0.28);
}

.page--left {
  padding: 38px 40px 34px;
  display: flex;
  flex-direction: column;
}

.brand {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 15px;
  color: var(--c-ink);
  letter-spacing: 0.02em;
}
.brand-glyph { color: var(--c-primary-deep); }

/* 印章式小标签 */
.stamp {
  display: inline-block;
  align-self: flex-start;
  margin-top: 34px;
  padding: 3px 10px;
  font-family: "KaiTi", "Kaiti SC", "STKaiti", serif;
  font-size: 13px;
  letter-spacing: 0.18em;
  color: #b4543a;
  border: 1.5px solid rgba(180, 84, 58, 0.75);
  border-radius: 3px;
  transform: rotate(-1.6deg);
  opacity: 0.9;
}

.copy h1 {
  margin: 18px 0 12px;
  font-family: "KaiTi", "Kaiti SC", "STKaiti", serif;
  font-size: 37px;
  line-height: 1.5;
  font-weight: 700;
  color: var(--c-ink);
  letter-spacing: 0.01em;
}
/* 手绘波浪线强调 */
.circled {
  font-style: normal;
  padding-bottom: 8px;
  background: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 80 10' preserveAspectRatio='none'><path d='M2 6 Q 12 1 22 6 T 42 6 T 62 6 T 78 5' fill='none' stroke='%23b4543a' stroke-width='3' stroke-linecap='round'/></svg>") no-repeat bottom / 100% 9px;
}
.sub {
  margin: 0;
  color: var(--c-ink-2);
  font-size: 13.5px;
  line-height: 1.9;
}

.sketch {
  width: 250px;
  margin: 18px auto 6px;
  opacity: 0.92;
}

/* 账本式条目：数字 + 点线 + 批注 */
.ledger {
  margin: auto 0 0;
  padding: 14px 0 0;
  list-style: none;
  border-top: 1px solid rgba(58, 52, 46, 0.14);
}
.ledger li {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 7px 0;
  font-size: 13px;
  color: var(--c-ink-2);
}
.ledger i {
  font-family: "KaiTi", "Kaiti SC", "STKaiti", serif;
  font-style: normal;
  font-size: 12px;
  color: #b4543a;
  border: 1px solid rgba(180, 84, 58, 0.55);
  border-radius: 2px;
  padding: 0 4px;
  line-height: 1.5;
}
.ledger .item { letter-spacing: 0.04em; }
.ledger .dots {
  flex: 1;
  border-bottom: 2px dotted rgba(58, 52, 46, 0.28);
  transform: translateY(-3px);
}
.ledger em { font-style: normal; color: var(--c-muted); font-size: 12px; }

/* ——— 中缝缝线 ——— */
.page--right {
  position: relative;
  border-left: 1px solid rgba(58, 52, 46, 0.16);
}
.page--right::before {
  content: "";
  position: absolute;
  left: -3.5px;
  top: 0;
  bottom: 0;
  width: 6px;
  background: repeating-linear-gradient(
    180deg,
    transparent 0 14px,
    rgba(58, 52, 46, 0.28) 14px 16.5px
  );
  pointer-events: none;
}

/* ——— 右页：登记 ——— */
.page--right {
  padding: 44px 42px 34px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.page--right h2 {
  margin: 0 0 6px;
  font-family: "KaiTi", "Kaiti SC", "STKaiti", serif;
  font-size: 26px;
  font-weight: 700;
  letter-spacing: 0.34em;
  color: var(--c-ink);
}
.hint { margin: 0 0 26px; color: var(--c-muted); font-size: 13px; line-height: 1.7; }
/* 登录 / 注册切换：账本页签 */
.tabs { display: flex; gap: 4px; margin-bottom: 20px; border-bottom: 1px solid rgba(58, 52, 46, 0.14); }
.tabs button {
  appearance: none; border: 0; background: none; cursor: pointer;
  padding: 8px 14px; font-size: 14px; letter-spacing: 0.14em; color: var(--c-muted);
  border-bottom: 2px solid transparent; transition: color 200ms var(--e-out), border-color 200ms var(--e-out);
}
.tabs button:hover { color: var(--c-ink); }
.tabs button.on { color: var(--c-primary-deep); border-bottom-color: var(--c-primary-deep); }
.submit-button {
  width: 100%;
  height: 46px;
  margin-top: 8px;
  font-size: 15px;
  letter-spacing: 0.2em;
  border-radius: 4px;
}
.page--right > small {
  display: block;
  margin-top: 22px;
  text-align: center;
  color: var(--c-muted);
  font-size: 12px;
  letter-spacing: 0.06em;
}

@media (max-width: 900px) {
  .login-shell { padding: 24px 14px; }
  .book { grid-template-columns: 1fr; }
  .page--left { padding: 26px 24px 22px; }
  .stamp { margin-top: 22px; }
  .copy h1 { font-size: 29px; }
  .sketch { width: 190px; margin-top: 8px; }
  .page--right { padding: 26px 24px 30px; border-left: 0; }
  .page--right::before {
    left: 0;
    right: 0;
    top: -3.5px;
    bottom: auto;
    width: auto;
    height: 6px;
    background: repeating-linear-gradient(
      90deg,
      transparent 0 14px,
      rgba(58, 52, 46, 0.28) 14px 16.5px
    );
  }
}
</style>
