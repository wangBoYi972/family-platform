<script setup>
import { computed, onMounted, ref } from "vue";
import { request, upload, loadImageUrl } from "@app/utils/request";
import { pageQuery } from "../paged-list.mjs";
import { usePagedList } from "../use-paged-list.js";
import ListMore from "../ListMore.vue";

const emit = defineEmits(["session-ended"]);
const keyword = ref("");
// 关键字交给服务端：分页后本地过滤只覆盖已加载的文件，等于「搜不到旧文件」
function filePath() {
  const text = keyword.value.trim();
  return text ? "/files?keyword=" + encodeURIComponent(text) : "/files";
}
const {
  items: files, loading, loadingMore, exhausted, error: listError,
  refresh, loadMore
} = usePagedList((page, size) => request(pageQuery(filePath(), page, size)));
const error = computed(() => listError.value?.message || "");
const uploading = ref(false);
const visibility = ref("FAMILY");
const keywordInput = ref("");
const busy = ref(new Set());
const previews = ref({});

const images = computed(() => files.value.filter(item => String(item.mime || "").startsWith("image/")));
const totalSize = computed(() => files.value.reduce((sum, item) => sum + (Number(item.sizeBytes) || 0), 0));

function formatSize(bytes) {
  const size = Number(bytes) || 0;
  if (size < 1024) return size + " B";
  if (size < 1024 * 1024) return (size / 1024).toFixed(1) + " KB";
  return (size / 1024 / 1024).toFixed(1) + " MB";
}
function kindLabel(item) {
  const mime = String(item.mime || "");
  if (mime.startsWith("image/")) return "图片";
  if (mime.startsWith("video/")) return "视频";
  if (mime.startsWith("audio/")) return "音频";
  if (mime === "application/pdf" || mime.includes("pdf")) return "PDF";
  if (mime.includes("word") || mime.includes("doc")) return "文档";
  if (mime.includes("sheet") || mime.includes("excel")) return "表格";
  return "文件";
}
function kindIcon(item) {
  const kind = kindLabel(item);
  return { 图片: "◫", 视频: "▷", 音频: "♪", PDF: "▤", 文档: "▤", 表格: "▦" }[kind] || "⌸";
}
function search() {
  const next = keywordInput.value.trim();
  if (next === keyword.value) { load(); return; }
  keyword.value = next;
  load();
}
function clearSearch() {
  keywordInput.value = "";
  keyword.value = "";
  load();
}

async function load() {
  if (await refresh()) return;
  if (listError.value?.status === 401) emit("session-ended");
}
async function loadMoreFiles() {
  if (await loadMore()) return;
  if (listError.value?.status === 401) emit("session-ended");
}

function chooseImage() {
  if (uploading.value) return;
  uni.chooseImage({
    count: 9,
    sizeType: ["compressed"],
    success: async res => {
      uploading.value = true;
      let ok = 0;
      for (const path of res.tempFilePaths) {
        try {
          await upload("/files", path, { visibility: visibility.value });
          ok++;
        } catch (e) {
          uni.showToast({ title: e.message || "上传失败", icon: "none" });
        }
      }
      uploading.value = false;
      if (ok) uni.showToast({ title: `已上传 ${ok} 个文件`, icon: "none" });
      await load();
    }
  });
}

/** H5 / 微信小程序支持任意文件选择；其他端提示用图片入口。 */
function chooseAnyFile() {
  if (uploading.value) return;
  if (typeof uni.chooseFile !== "function")
    return uni.showToast({ title: "当前环境仅支持上传图片", icon: "none" });
  uni.chooseFile({
    count: 5,
    success: async res => {
      uploading.value = true;
      let ok = 0;
      for (const path of res.tempFilePaths || []) {
        try {
          await upload("/files", path, { visibility: visibility.value });
          ok++;
        } catch (e) {
          uni.showToast({ title: e.message || "上传失败", icon: "none" });
        }
      }
      uploading.value = false;
      if (ok) uni.showToast({ title: `已上传 ${ok} 个文件`, icon: "none" });
      await load();
    }
  });
}

async function preview(item) {
  if (!String(item.mime || "").startsWith("image/"))
    return uni.showToast({ title: `${kindLabel(item)}暂不支持预览`, icon: "none" });
  if (busy.value.has(item.id)) return;
  busy.value.add(item.id);
  try {
    if (!previews.value[item.id]) previews.value[item.id] = await loadImageUrl(item.id);
    uni.previewImage({ urls: [previews.value[item.id]] });
  } catch (e) {
    uni.showToast({ title: e.message || "预览失败", icon: "none" });
  } finally {
    busy.value.delete(item.id);
  }
}

async function remove(item) {
  if (busy.value.has(item.id)) return;
  const confirmed = await new Promise(resolve =>
    uni.showModal({ title: "删除文件", content: `确定删除「${item.name}」？删除后不可恢复。`, success: res => resolve(res.confirm), fail: () => resolve(false) })
  );
  if (!confirmed) return;
  busy.value.add(item.id);
  try {
    await request(`/files/${item.id}`, { method: "DELETE" });
    uni.showToast({ title: "已删除", icon: "none" });
    await load();
  } catch (e) {
    uni.showToast({ title: e.message || "删除失败", icon: "none" });
    if (e.status === 401) emit("session-ended");
  } finally {
    busy.value.delete(item.id);
  }
}

defineExpose({ load });
onMounted(load);
</script>

<template>
  <view class="files">
    <!-- 搜索时列表只剩匹配项，这时再显示「全部文件 / 照片 / 占用空间」会误导，直接换成结果条数 -->
    <view v-if="!keyword" class="metrics">
      <view class="metric"><text class="figure">{{ files.length }}</text><text class="caption">全部文件</text></view>
      <view class="metric"><text class="figure">{{ images.length }}</text><text class="caption">照片</text></view>
      <view class="metric"><text class="figure">{{ formatSize(totalSize) }}</text><text class="caption">占用空间</text></view>
    </view>
    <text v-if="keyword" class="scope-note">搜索「{{ keyword }}」的结果，<text class="do" @click="clearSearch">清除</text></text>
    <text v-else-if="!exhausted && files.length" class="scope-note">统计基于已加载的 {{ files.length }} 个文件，加载更多后同步更新</text>

    <view class="actions">
      <view class="field">
        <input v-model="keywordInput" class="input" placeholder="搜索文件名" @confirm="search" />
        <text class="do" @click="search">搜索</text>
      </view>
      <view class="upload">
        <view class="switch">
          <text v-for="item in ['FAMILY', 'PRIVATE']" :key="item" class="switch-item" :class="{ active: visibility === item }" @click="visibility = item">
            {{ item === 'FAMILY' ? '全家可见' : '仅自己' }}
          </text>
        </view>
        <button size="mini" :loading="uploading" :disabled="uploading" @click="chooseImage">＋ 上传图片</button>
        <button size="mini" :disabled="uploading" @click="chooseAnyFile">＋ 其他文件</button>
      </view>
    </view>

    <view v-if="error" class="state"><text>{{ error }}</text><button size="mini" @click="load">重新加载</button></view>
    <view v-else-if="loading" class="state">正在读取家里的文件…</view>
    <view v-else-if="!files.length" class="state">{{ keyword ? '没有匹配的文件，换个名字试试' : '还没有文件，上传第一张照片吧' }}</view>
    <view v-else class="list">
      <view v-for="item in files" :key="item.id" class="row">
        <text class="kind">{{ kindIcon(item) }}</text>
        <view class="body">
          <text class="name">{{ item.name }}</text>
          <text class="meta">{{ kindLabel(item) }} · {{ formatSize(item.sizeBytes) }} · {{ String(item.createdAt || "").slice(0, 10) }}</text>
        </view>
        <text class="tag" :class="{ private: item.visibility !== 'FAMILY' }">{{ item.visibility === 'FAMILY' ? '全家' : '私有' }}</text>
        <text class="op" @click="preview(item)">预览</text>
        <text class="op danger" @click="remove(item)">删除</text>
      </view>
    </view>
    <ListMore :count="files.length" :loading="loadingMore" :exhausted="exhausted" :has-error="!!listError" @more="loadMoreFiles" />
  </view>
</template>

<style scoped>
.files{display:flex;flex-direction:column;gap:16px}
.metrics{display:grid;grid-template-columns:repeat(3,1fr);gap:12px}
.metric{background:var(--c-surface);border:1px solid var(--c-line);border-radius:14px;padding:14px;text-align:center}
.figure{display:block;font-size:22px;font-weight:700;color:var(--c-primary)}
.caption{font-size:11px;color:var(--c-muted)}
.scope-note{font-size:11px;color:var(--c-muted);line-height:1.6}
.actions{display:flex;flex-wrap:wrap;gap:12px;align-items:center;justify-content:space-between}
.field{display:flex;align-items:center;gap:10px;flex:1;min-width:220px}
.input{flex:1;height:42px;padding:0 16px;background:var(--c-surface-2);border:1px solid var(--c-line);border-radius:12px;font-size:13px;color:var(--c-ink)}
.do{font-size:13px;color:var(--c-primary);flex-shrink:0}
.upload{display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.switch{display:flex;background:var(--c-surface-2);border-radius:12px;padding:3px;margin-right:4px}
.switch-item{font-size:12px;color:var(--c-ink-2);padding:6px 12px;border-radius:10px}
.switch-item.active{background:var(--c-surface);color:var(--c-primary);font-weight:600;box-shadow:0 1px 4px rgba(23,42,70,.08)}
.upload button{font-size:12px;margin:0}
.list{display:flex;flex-direction:column;gap:8px}
.row{display:flex;align-items:center;gap:12px;background:var(--c-surface);border:1px solid var(--c-line);border-radius:14px;padding:12px 14px}
.kind{width:38px;height:38px;line-height:38px;text-align:center;border-radius:11px;background:var(--c-primary-soft);color:var(--c-primary);font-size:16px;flex-shrink:0}
.body{flex:1;min-width:0}
.name{display:block;font-size:13px;font-weight:600;color:var(--c-ink);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.meta{display:block;font-size:11px;color:var(--c-muted);margin-top:3px}
.tag{font-size:10px;color:var(--c-sage);background:var(--c-sage-soft);border-radius:8px;padding:3px 8px;flex-shrink:0}
.tag.private{color:var(--c-sand);background:var(--c-sand-soft)}
.op{font-size:12px;color:var(--c-primary);flex-shrink:0}
.op.danger{color:var(--c-danger)}
.state{text-align:center;color:var(--c-muted);font-size:13px;padding:30px 10px}
.state button{margin-top:8px;font-size:11px}
@media(max-width:700px){.metrics{grid-template-columns:repeat(3,1fr);gap:8px}.figure{font-size:18px}.row{flex-wrap:wrap}}
</style>
