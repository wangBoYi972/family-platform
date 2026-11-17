<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import EntryEditor from "./EntryEditor.vue";
import { request, upload, loadImageUrl } from "@app/utils/request";
import { pageQuery } from "../paged-list.mjs";
import { usePagedList } from "../use-paged-list.js";
import ListMore from "../ListMore.vue";

const props = defineProps({ token: String });
const keyword = ref("");
// 关键字进查询串：分页之后本地过滤只能搜到当前这一页，等于「搜不到」
function entryPath() {
  const text = keyword.value.trim();
  return text ? "/dynamics?keyword=" + encodeURIComponent(text) : "/dynamics";
}
const {
  items: entries, loading, loadingMore, exhausted, error: listError,
  refresh, reload, loadMore
} = usePagedList((page, size) => request(pageQuery(entryPath(), page, size)));
const publishing = ref(false);
const composing = ref(false);
const loadError = computed(() => listError.value?.message || "");
const liking = ref(new Set());
async function like(item) {
  if(liking.value.has(item.id)) return;
  liking.value.add(item.id);
  try { await request('/dynamics/'+item.id+'/like',{method:'POST'}); await load(); }
  catch(e) { uni.showToast({title:e.message||'点赞失败',icon:'none'}); }
  finally { liking.value.delete(item.id); }
}
function preview(item, index) { uni.previewImage({urls:item.images,current:item.images[index]}); }
const form = ref({ title: "", body: "" });
const images = ref([]); // { tempFilePath, name }

/** 逐条把 fileIds 换成可显示的图片地址；单张失败不阻塞列表。 */
async function hydrate(rows) {
  await Promise.all(
    rows.map(async item => {
      if (item.images) return;
      item.images = [];
      if (!item.fileIds) return;
      const ids = String(item.fileIds).split(",").filter(Boolean);
      for (const id of ids.slice(0, 9)) {
        try {
          item.images.push(await loadImageUrl(id));
        } catch (error) {
          // 单张图片失败不阻塞列表
        }
      }
    })
  );
}

async function load() {
  if (!props.token) return;
  if (await refresh()) return hydrate(entries.value);
  uni.showToast({ title: loadError.value || "加载失败", icon: "none" });
}
/** 实时刷新用：保持已翻到的位置，已解析过的图片地址直接复用，不重复换一次 URL。 */
async function sync() {
  if (!props.token) return;
  const previous = new Map(entries.value.map(item => [String(item.id), item]));
  if (!(await reload())) {
    if (listError.value) uni.showToast({ title: loadError.value || "刷新失败", icon: "none" });
    return;
  }
  entries.value.forEach(item => {
    const old = previous.get(String(item.id));
    if (old?.images?.length) item.images = old.images;
  });
  return hydrate(entries.value);
}
async function loadMoreDynamics() {
  if (await loadMore()) return hydrate(entries.value);
  if (listError.value) uni.showToast({ title: loadError.value || "加载失败", icon: "none" });
}

function chooseImages() {
  if(publishing.value) return;
  const remain = 9 - images.value.length;
  if (remain <= 0) return uni.showToast({ title: "最多 9 张图片", icon: "none" });
  uni.chooseImage({
    count: remain,
    sizeType: ["compressed"],
    success: res => {
      if(!publishing.value) images.value.push(...res.tempFilePaths.slice(0,9-images.value.length).map(p => ({ tempFilePath: p })));
    }
  });
}

function removeImage(index) {
  if(publishing.value) return;
  images.value.splice(index, 1);
}

async function publish() {
  if (publishing.value) return;
  const title = form.value.title.trim();
  const body = form.value.body.trim();
  if (!title || !body) return uni.showToast({ title: "标题和内容不能为空", icon: "none" });
  publishing.value = true;
  try {
    const fileIds = [];
    for (const image of images.value) {
      // 发布失败后重试时复用成功上传的照片。
      if(!image.id) image.id = (await upload("/files", image.tempFilePath, { visibility: "FAMILY" })).id;
      fileIds.push(image.id);
    }
    await request("/dynamics", {
      method: "POST",
      data: { title, body, visibility: "FAMILY", fileIds: fileIds.length ? fileIds.join(",") : undefined }
    });
    form.value = { title: "", body: "" };
    images.value = [];
    composing.value = false;
    uni.showToast({ title: "已发布", icon: "none" });
    await load();
  } catch (error) {
    uni.showToast({ title: error.message || "发布失败", icon: "none" });
  } finally {
    publishing.value = false;
  }
}

let keywordTimer = null;
watch(keyword, () => {
  // 停顿 300ms 再查，避免每个字符打一次接口
  clearTimeout(keywordTimer);
  keywordTimer = setTimeout(load, 300);
});
defineExpose({ load, sync });
onMounted(load);
onUnmounted(() => clearTimeout(keywordTimer));
</script>

<template>
  <view class="panel">
    <view class="toolbar">
      <text class="section">家庭动态</text>
      <button size="mini" :disabled="publishing" @click="composing = !composing">{{ composing ? "收起" : "＋ 发布动态" }}</button>
    </view>

    <view v-if="composing" class="form card">
      <input v-model="form.title" :disabled="publishing" class="input" maxlength="120" placeholder="动态标题（必填）" />
      <textarea v-model="form.body" :disabled="publishing" class="textarea" maxlength="10000" placeholder="分享这一刻…（必填）" :auto-height="true" />
      <view class="image-picker">
        <view v-for="(image, index) in images" :key="index" class="thumb-wrap">
          <image :src="image.tempFilePath" class="thumb" mode="aspectFill" />
          <text class="thumb-remove" @click="removeImage(index)">×</text>
        </view>
        <view v-if="images.length < 9" class="thumb-add" @click="chooseImages">＋</view>
      </view>
      <button class="primary" :loading="publishing" :disabled="publishing" @click="publish">发布动态</button>
    </view>

    <input v-model="keyword" class="input" placeholder="搜索动态标题或内容" />
    <view v-if="loadError" class="empty"><text>{{loadError}}</text><button size="mini" @click="load">重新加载</button></view>
    <view v-else-if="loading" class="empty">加载中…</view>
    <view v-else-if="!entries.length" class="empty">{{keyword.trim() ? '没有匹配的动态，试试其他关键词' : '还没有动态，发布第一条吧'}}</view>
    <view v-else class="list">
      <view v-for="item in entries" :key="item.id" class="row card">
        <view class="row-head">
          <text class="row-title">{{ item.title }}</text>
          <EntryEditor kind="dynamics" :entry="item" @changed="load" />
        </view>
        <text v-if="item.body" class="row-body">{{ item.body }}</text>
        <view v-if="item.images && item.images.length" class="images">
          <image v-for="(url, index) in item.images" :key="index" :src="url" class="img" mode="aspectFill" @click="preview(item,index)" />
        </view>
        <view class="row-foot">
          <view class="like" @click="like(item)">
            <text class="like-icon">♥</text>
            <text class="like-text">{{ item.likes ? item.likes + " 人喜欢" : "喜欢" }}</text>
          </view>
        </view>
      </view>
    </view>
    <ListMore :count="entries.length" :loading="loadingMore" :exhausted="exhausted" :has-error="!!listError" @more="loadMoreDynamics" />
  </view>
</template>

<style scoped>
.panel{display:flex;flex-direction:column;gap:24rpx}
.toolbar{display:flex;justify-content:space-between;align-items:center}
.section{font-size:36rpx;font-weight:600}
.link{color:var(--family-primary);font-size:28rpx}
.card{background:var(--c-surface);border-radius:16rpx;padding:26rpx;box-shadow:0 2rpx 12rpx rgba(29,42,38,.04)}
.form{display:flex;flex-direction:column;gap:20rpx}
.input{height:84rpx;padding:0 24rpx;background:var(--c-surface-2);border:1px solid var(--family-border);border-radius:12rpx;box-sizing:border-box}
.textarea{width:100%;min-height:160rpx;padding:20rpx 24rpx;background:var(--c-surface-2);border:1px solid var(--family-border);border-radius:12rpx;box-sizing:border-box}
.primary{margin-top:8rpx;background:var(--family-primary);color:var(--c-surface);border-radius:12rpx}
.image-picker{display:flex;flex-wrap:wrap;gap:16rpx}
.thumb-wrap{position:relative}
.thumb{width:150rpx;height:150rpx;border-radius:12rpx;background:var(--c-surface-2)}
.thumb-remove{position:absolute;top:-12rpx;right:-12rpx;width:40rpx;height:40rpx;line-height:36rpx;text-align:center;background:var(--c-danger);color:var(--c-surface);border-radius:50%;font-size:28rpx}
.thumb-add{width:150rpx;height:150rpx;line-height:150rpx;text-align:center;background:var(--c-surface-2);color:var(--family-muted);border:1px dashed var(--family-border);border-radius:12rpx;font-size:56rpx}
.row{display:flex;flex-direction:column;gap:16rpx;background:var(--c-surface);border:1px solid var(--c-line);border-radius:20rpx;padding:28rpx;box-shadow:0 2rpx 12rpx rgba(58,52,46,.05)}
.row-head{display:flex;justify-content:space-between;align-items:center;gap:16rpx}
.row-head :deep(.editor){margin-top:0}
.row-title{font-size:32rpx;font-weight:650;flex:1;min-width:0}
.row-body{color:var(--family-text);font-size:27rpx;line-height:1.7;white-space:pre-wrap}
.images{display:flex;flex-wrap:wrap;gap:12rpx}
.img{width:200rpx;height:200rpx;border-radius:14rpx;background:var(--c-surface-2)}
.row-foot{display:flex;align-items:center;justify-content:space-between;gap:16rpx;border-top:1px solid var(--c-surface-2);padding-top:16rpx;margin-top:4rpx}
.like{display:inline-flex;align-items:center;gap:8rpx;padding:10rpx 26rpx;border-radius:8rpx;background:var(--c-primary-soft);color:var(--c-primary);font-size:24rpx;transition:transform .12s var(--e-spring, ease)}
.like:active{transform:scale(.92)}
.like-icon{font-size:24rpx;line-height:1}
.row-meta{color:var(--family-muted);font-size:24rpx}
.empty{padding:80rpx 0;text-align:center;color:var(--family-muted)}
</style>
