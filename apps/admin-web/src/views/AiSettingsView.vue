<script setup>
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { getAiSettings, saveAiSettings } from "../api/ai";

const loading = ref(false), saving = ref(false), error = ref("");
const settings = ref({ enabled: false, ready: false, maskedKey: "", source: "env", model: "", baseUrl: "" });
// 输入框独立于服务端状态：key 一旦保存就再也读不回来，所以输入框永远是空的，只提示已存尾号
const apiKeyInput = ref("");
const enabledInput = ref(false);

const keyLabel = computed(() =>
  settings.value.maskedKey ? `已保存：${settings.value.maskedKey}` : "尚未保存 key");
const sourceLabel = computed(() => settings.value.source === "admin" ? "管理端设置" : "环境变量");

async function load() {
  loading.value = true;
  error.value = "";
  try {
    settings.value = await getAiSettings();
    enabledInput.value = settings.value.enabled;
    error.value = "";  // 读取成功后确保清除错误状态
  } catch (e) {
    error.value = e.message;
  } finally {
    loading.value = false;
  }
}

async function save() {
  if (saving.value) return;
  // 开开关却没填 key、也没存过 key：再提交一次也是 503，不如当场说清楚
  if (enabledInput.value && !apiKeyInput.value.trim() && !settings.value.maskedKey) {
    ElMessage.warning("请先粘贴模型服务的 API Key，再开启助手");
    return;
  }
  const typed = apiKeyInput.value.trim();
  if (typed && !/^[\x21-\x7e]{16,512}$/.test(typed)) {
    ElMessage.warning("Key 格式看起来不对：只允许可见 ASCII 字符，16 至 512 位");
    return;
  }
  saving.value = true;
  try {
    settings.value = await saveAiSettings({ enabled: enabledInput.value, apiKey: typed });
    apiKeyInput.value = "";
    enabledInput.value = settings.value.enabled;
    error.value = "";  // 保存成功后清除之前的错误状态
    ElMessage.success(settings.value.ready ? "已保存，AI 助手可以用了" : "已保存，但助手仍是关闭或缺少 key");
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    saving.value = false;
  }
}

onMounted(load);
</script>

<template>
  <section class="content" v-loading="loading">
        <div class="page-actions"><el-button text @click="load">刷新</el-button></div>
        <div class="page-heading">
          <div>
            <p class="eyebrow">智能客服</p>
            <h1>助手配置</h1>
            <div class="mf-rule"></div>
            <p class="hint">家庭成员在聊天页的「家庭小助手」里提问，助手用提问人自己的权限查询任务、日程与档案。</p>
          </div>
        </div>

        <el-alert v-if="error" :title="'读取配置失败：' + error" type="error" :closable="false" show-icon />

        <el-card shadow="never" class="panel">
          <template #header>
            <div class="card-head">
              <span>运行状态</span>
              <el-tag :type="settings.ready ? 'success' : 'info'" effect="plain">
                {{ settings.ready ? "可以对话" : "未启用" }}
              </el-tag>
            </div>
          </template>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="开关">{{ settings.enabled ? "已开启" : "已关闭" }}</el-descriptions-item>
            <el-descriptions-item label="Key">{{ keyLabel }}</el-descriptions-item>
            <el-descriptions-item label="来源">{{ sourceLabel }}</el-descriptions-item>
            <el-descriptions-item label="模型">{{ settings.model || "—" }}</el-descriptions-item>
            <el-descriptions-item label="服务端点" :span="2">{{ settings.baseUrl || "—" }}</el-descriptions-item>
          </el-descriptions>
        </el-card>

        <el-card shadow="never" class="panel">
          <template #header><span>修改配置</span></template>
          <el-form label-position="top" @submit.prevent="save">
            <el-form-item label="模型服务 API Key">
              <el-input
                v-model="apiKeyInput"
                type="password"
                show-password
                autocomplete="off"
                maxlength="512"
                :placeholder="settings.maskedKey ? '留空则保持不变，粘贴新 key 会覆盖' : '粘贴 API Key'"
              />
              <p class="hint">保存后只回显首三尾四位，服务端不再返回完整 key。key 明文存放在服务端本地配置文件中，不进版本库。</p>
            </el-form-item>
            <el-form-item label="启用助手">
              <el-switch v-model="enabledInput" active-text="开" inactive-text="关" />
              <p class="hint">关闭后聊天页的「家庭小助手」仍在列表里，但发消息会提示未配置。</p>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="saving" native-type="submit">保存</el-button>
              <el-button text @click="apiKeyInput = ''">清空输入</el-button>
            </el-form-item>
          </el-form>
        </el-card>
  </section>
</template>

<style scoped>
.content { max-width: 860px; }
.page-actions { display: flex; justify-content: flex-end; }
.page-heading { margin-bottom: 20px; }
.eyebrow { color: var(--c-primary); font-family: var(--font-display); font-size: 13px; letter-spacing: .16em; margin: 0 0 6px; }
h1 { margin: 0 0 8px; font-size: 26px; font-family: var(--font-display); }
.mf-rule { width: 36px; height: 2px; background: var(--c-primary); margin-bottom: 12px; border-radius: 0; }
.hint { color: var(--c-muted, #8a8a8a); font-size: 12px; line-height: 1.6; margin: 6px 0 0; }
.panel { margin-bottom: 18px; border-radius: 30px; border: 1px solid rgba(255, 255, 255, .8); background: rgba(255, 255, 255, .54); box-shadow: 0 10px 30px rgba(134, 187, 255, .22); backdrop-filter: blur(20px); }
.card-head { display: flex; align-items: center; justify-content: space-between; }
</style>
