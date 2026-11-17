<script setup>
import { onMounted, onUnmounted, ref, computed, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { listMembers } from "../api/member";
import { listArchives, listEntries, createEntry, updateEntry, deleteEntry, archiveDetail } from "../api/entry";
import { dayCaption } from "@shared/calendar-marks.mjs";
import MonthBoard from "../components/MonthBoard.vue";
const props = defineProps({ kind: { type: String, required: true } });
const emit = defineEmits(["navigate"]);
const loading = ref(false), rows = ref([]), dialogVisible = ref(false), saving = ref(false);
const editingId = ref(null), deleting = ref(new Set()), loadError = ref("");
const detail = ref(null), detailVisible = ref(false), detailLoading = ref(false);
const keyword = ref("");
const newForm = () => ({ title: "", body: "", eventDate: "", yearly: false, pinned: false, category: "DOCUMENT", visibility: props.kind === "archives" ? "PRIVATE" : "FAMILY", fileIds: null });
const form = ref(newForm());
const visibilityLabel = { PRIVATE: "仅自己", FAMILY: "家庭成员" };
const categoryLabel = { IDENTITY: "身份", HEALTH: "健康", DOCUMENT: "证件" };
// 成员接口没有服务端关键字，只能本地筛；其余列表的关键字与分页都进查询串。
const filteredRows = computed(() => (isMembers.value
  ? rows.value.filter(row => (row.displayName || row.title || "").includes(keyword.value.trim()))
  : rows.value));
const PAGE_SIZE = 12;
const page = ref(1), exhausted = ref(false);
let sequence = 0, detailSequence = 0, keywordTimer = null;
const config = computed(() => ({ members: { eyebrow: "权限", title: "家庭成员", description: "管理家庭成员和访问边界。" }, notices: { eyebrow: "通知", title: "家庭公告", description: "发布家庭成员可见的事项。" }, calendar: { eyebrow: "日历", title: "家庭日历", description: "维护纪念日和家庭日程。" }, archives: { eyebrow: "档案", title: "加密档案", description: "敏感资料按家庭权限读取。" } }[props.kind]));
const isMembers = computed(() => props.kind === "members"), isArchives = computed(() => props.kind === "archives");
async function load({ reset = true, at = null } = {}) {
  const current = ++sequence;
  const target = at ?? (reset ? 1 : page.value);
  loading.value = true;
  loadError.value = "";
  try {
    let data;
    if (isMembers.value) {
      data = await listMembers();
    } else {
      const options = { page: target, size: PAGE_SIZE, keyword: keyword.value.trim() };
      data = isArchives.value ? await listArchives(options) : await listEntries(props.kind, options);
    }
    if (current !== sequence) return;
    rows.value = data;
    page.value = target;
    // 接口不返回总数，本页不满就是最后一页。
    exhausted.value = !isMembers.value && data.length < PAGE_SIZE;
  } catch (error) {
    if (current === sequence) loadError.value = error.message || "加载失败，请重试";
  } finally { if (current === sequence) loading.value = false; }
}
function go(target) {
  if (loading.value || target < 1) return;
  load({ reset: false, at: target });
}
/** 删完最后一页就退一页，避免停在一张空白页上。 */
async function reloadCurrent() {
  await load({ reset: false });
  if (!loading.value && !rows.value.length && page.value > 1) await load({ reset: false, at: page.value - 1 });
}
watch(keyword, () => {
  if (isMembers.value) return;
  // 输入停顿 300ms 再查，避免每个字符打一次接口
  clearTimeout(keywordTimer);
  keywordTimer = setTimeout(() => load({ reset: true }), 300);
});
function openCreate() {
  editingId.value = null;
  form.value = newForm();
  dialogVisible.value = true;
}
function openEdit(row) {
  editingId.value = row.id;
  form.value = { ...newForm(), ...row, body: row.body || "" };
  dialogVisible.value = true;
}
async function showDetail(row) {
  const current = ++detailSequence;
  detailVisible.value = true;
  detail.value = null;
  detailLoading.value = true;
  try {
    // 档案正文必须经详情接口读取，以保留服务端访问审计。
    const data = isArchives.value ? await archiveDetail(row.id) : row;
    if (current === detailSequence) detail.value = data;
  } catch (error) {
    if (current === detailSequence) { detailVisible.value = false; ElMessage.error(error.message || "详情加载失败"); }
  } finally { if (current === detailSequence) detailLoading.value = false; }
}
async function submit() {
  if (saving.value) return;
  if (!form.value.title.trim()) return ElMessage.warning("请填写标题");
  if (props.kind === "calendar" && !form.value.eventDate) return ElMessage.warning("请选择日期");
  if (isArchives.value && !form.value.body.trim()) return ElMessage.warning("请填写档案内容");
  saving.value = true;
  const body = isArchives.value
    ? { title: form.value.title.trim(), body: form.value.body, category: form.value.category, visibility: form.value.visibility }
    : { title: form.value.title.trim(), body: form.value.body, eventDate: form.value.eventDate || null, yearly: form.value.yearly, pinned: form.value.pinned, visibility: form.value.visibility, fileIds: form.value.fileIds };
  try {
    if (editingId.value !== null) await updateEntry(props.kind, editingId.value, body);
    else await createEntry(props.kind, body);
    const created = editingId.value === null;
    dialogVisible.value = false;
    ElMessage.success("已保存");
    if (created) await load({ reset: true }); else await reloadCurrent();
  } catch (error) { ElMessage.error(error.message || "保存失败，填写内容已保留"); }
  finally { saving.value = false; }
}
async function remove(row) {
  if (deleting.value.has(row.id)) return;
  deleting.value.add(row.id);
  try {
    await ElMessageBox.confirm("确定删除“" + row.title + "”吗？删除后无法恢复。", "删除确认", { type: "warning", confirmButtonText: "确认删除", cancelButtonText: "取消" });
    await deleteEntry(props.kind, row.id);
    ElMessage.success("已删除");
    await reloadCurrent();
  } catch (error) { if (error !== "cancel" && error !== "close") ElMessage.error(error.message || "删除失败"); }
  finally { deleting.value.delete(row.id); }
}
function dateLine(iso) {
  if (!iso) return "未设置日期";
  return `${iso} · ${dayCaption(iso)}`;
}
onMounted(() => load());
onUnmounted(() => { sequence++; detailSequence++; clearTimeout(keywordTimer); });
</script>
<template>
<section class="content-page">
  <div class="page-heading"><div><p class="eyebrow">{{ config.eyebrow }}</p><h1>{{ config.title }}</h1><p class="subtitle">{{ config.description }}</p></div><div class="page-actions"><el-button text :disabled="saving" @click="emit('navigate','任务')">返回工作台</el-button><el-button :disabled="loading" @click="load()">刷新</el-button><el-button v-if="!isMembers" type="primary" @click="openCreate">新建</el-button></div></div>
  <MonthBoard v-if="props.kind === 'calendar'" />
  <el-input v-model="keyword" class="search" placeholder="按名称搜索" aria-label="按名称搜索" clearable />
  <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />
  <div class="content-list" v-loading="loading">
    <div v-for="row in filteredRows" :key="row.id || row.phone" class="content-row">
      <div class="row-main">
        <strong>{{ row.displayName || row.title }}</strong>
        <span v-if="props.kind === 'calendar'" class="event-date">{{ dateLine(row.eventDate) }} · {{ row.yearly ? '每年纪念日' : '单次日程' }}</span>
        <span>{{ row.body || categoryLabel[row.category] || (row.role === 'ADMIN' ? '管理员' : '') || visibilityLabel[row.visibility] }}</span>
      </div>
      <el-tag v-if="row.pinned" type="warning" effect="plain">置顶</el-tag>
      <el-tag v-if="row.role" effect="plain">{{ row.role === "ADMIN" ? "管理员" : "成员" }}</el-tag>
      <el-tag v-else-if="row.visibility" effect="plain">{{ isArchives ? visibilityLabel[row.visibility] : (row.visibility === 'PRIVATE' ? '仅管理员' : '家庭成员') }}</el-tag>
      <div v-if="!isMembers" class="row-actions">
        <el-button text @click="showDetail(row)">查看</el-button>
        <template v-if="!isArchives"><el-button text :disabled="deleting.has(row.id)" @click="openEdit(row)">编辑</el-button><el-button text type="danger" :loading="deleting.has(row.id)" @click="remove(row)">删除</el-button></template>
      </div>
    </div>
    <el-empty v-if="!loading && !loadError && !filteredRows.length" :description="keyword.trim() || rows.length ? '没有符合条件的记录' : '暂无记录，点击新建开始添加'" />
  </div>
  <div v-if="!isMembers" class="pager">
    <el-button text :disabled="page <= 1 || loading" @click="go(page - 1)">上一页</el-button>
    <span class="pager-note">第 {{ page }} 页</span>
    <el-button text :disabled="exhausted || loading" @click="go(page + 1)">下一页</el-button>
  </div>
  <el-dialog v-model="dialogVisible" :title="(editingId === null ? '新建' : '编辑') + config.title" width="min(520px, 94vw)" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
    <el-form label-position="top" :disabled="saving">
      <el-form-item label="标题" required><el-input v-model="form.title" maxlength="120" /></el-form-item>
      <el-form-item v-if="props.kind === 'calendar'" label="日期" required><el-date-picker v-model="form.eventDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
      <el-form-item v-if="props.kind === 'calendar'" label="纪念日"><el-switch v-model="form.yearly" active-text="每年纪念" inactive-text="单次日程" /><p class="form-note">每年纪念标记用于生日、周年纪念；当前不会发送系统推送。</p></el-form-item>
      <el-form-item v-if="props.kind === 'notices'" label="公告置顶"><el-switch v-model="form.pinned" active-text="置顶展示" /></el-form-item>
      <el-form-item v-if="isArchives" label="分类"><el-select v-model="form.category"><el-option label="身份" value="IDENTITY"/><el-option label="健康" value="HEALTH"/><el-option label="证件" value="DOCUMENT"/></el-select></el-form-item>
      <el-form-item v-if="isArchives" label="可见范围"><el-select v-model="form.visibility"><el-option label="仅自己" value="PRIVATE"/><el-option label="家庭成员" value="FAMILY"/></el-select></el-form-item>
      <el-form-item label="内容" :required="isArchives"><el-input v-model="form.body" type="textarea" :rows="6" :maxlength="isArchives ? 20000 : 10000" show-word-limit /></el-form-item>
    </el-form>
    <template #footer><el-button :disabled="saving" @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" :disabled="saving" @click="submit">保存</el-button></template>
  </el-dialog>
  <el-dialog v-model="detailVisible" title="记录详情" width="min(620px, 94vw)" @closed="detail = null; detailSequence++">
    <div v-loading="detailLoading" class="detail"><template v-if="detail"><h2>{{ detail.title }}</h2><p v-if="detail.eventDate">日期：{{ dateLine(detail.eventDate) }}{{ detail.yearly ? ' · 每年纪念日' : '' }}</p><p class="detail-body">{{ detail.body || '暂无正文' }}</p></template></div>
  </el-dialog>
</section>
</template>
<style scoped>.content-page{max-width:980px}.page-heading{display:flex;justify-content:space-between;gap:20px}.page-actions,.row-actions{display:flex;gap:8px;align-items:center;flex-shrink:0}.content-list{margin-top:26px;display:flex;flex-direction:column;gap:12px}.content-row{display:flex;align-items:center;gap:16px;padding:16px 18px;border-radius:4px;background:#fffdf8;border:1px solid rgba(58,52,46,.14)}.content-row:hover{border-color:rgba(58,52,46,.32)}.row-main{display:grid;gap:6px;flex:1;min-width:0}.row-main strong{font-weight:600;overflow-wrap:anywhere}.row-main span{color:var(--family-muted);font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.row-main .event-date{color:var(--family-primary);font-weight:500;white-space:normal}.search{margin:24px 0 16px;max-width:400px}.pager{display:flex;align-items:center;justify-content:center;gap:16px;margin-top:24px}.pager-note{color:var(--family-muted);font-size:13px}.form-note{width:100%;color:var(--family-muted);font-size:12px}.detail{min-height:80px}.detail-body{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.7}@media(max-width:960px){.content-page{padding:40px 24px}.page-heading{flex-direction:column}.page-actions{flex-wrap:wrap}.content-row{flex-wrap:wrap}.row-main{flex-basis:65%}.row-actions{width:100%}}</style>
