<script setup>
import EntryEditor from "@shared/panels/EntryEditor.vue";
import { computed, ref, onMounted } from "vue";
import { request, loadImageUrl } from "../utils/request";
import { pageQuery } from "../../../shared/paged-list.mjs";
import { usePagedList } from "../../../shared/use-paged-list.js";
import ListMore from "../../../shared/ListMore.vue";

const props = defineProps({ token: String });
const {
  items: albums, loading, loadingMore, exhausted, error: listError,
  refresh, loadMore
} = usePagedList((page, size) => request(pageQuery("/albums", page, size)));
const keyword = ref("");
const loadError = computed(() => listError.value?.message || "");
const filteredAlbums = computed(() => albums.value.filter(item => (item.title+' '+(item.body||'')).toLocaleLowerCase().includes(keyword.value.trim().toLocaleLowerCase())));
function photoCount(item) { return String(item.fileIds||'').split(',').filter(Boolean).length; }

/** 逐本把 fileIds 换成可显示地址；已取过的跳过，单张失败不阻塞相册。 */
async function hydrate(rows) {
  await Promise.all(
    rows.map(async item => {
      if (item.images) return;
      item.images = [];
      if (!item.fileIds) return;
      const ids = String(item.fileIds).split(",").filter(Boolean);
      for (const id of ids.slice(0, 30)) {
        try {
          item.images.push(await loadImageUrl(id));
        } catch (error) {
          // 单张图片失败不阻塞相册
        }
      }
    })
  );
}

async function load() {
  if (!props.token) return;
  if (await refresh()) return hydrate(albums.value);
  uni.showToast({ title: loadError.value || "加载失败", icon: "none" });
}
async function loadMoreAlbums() {
  if (await loadMore()) return hydrate(albums.value);
  if (listError.value) uni.showToast({ title: loadError.value || "加载失败", icon: "none" });
}

function preview(images, current) {
  uni.previewImage({ urls: images, current: images[current] });
}

defineExpose({ load });
onMounted(load);
</script>

<template>
  <view class="panel">
    <view class="toolbar">
      <text class="section">家庭相册</text>
    </view>
    <EntryEditor kind="albums" @changed="load" />
    <input v-model="keyword" class="search" placeholder="搜索相册名称或描述" />
    <view v-if="loadError" class="empty"><text>{{loadError}}</text><button size="mini" @click="load">重新加载</button></view>
    <view v-else-if="loading" class="empty">加载中…</view>
    <view v-else-if="!filteredAlbums.length" class="empty">{{albums.length?'没有匹配的相册':'相册还是空的，创建一本家庭相册吧'}}</view>
    <view v-else class="list">
      <view v-for="item in filteredAlbums" :key="item.id" class="album card">
        <view class="album-head">
          <text class="album-title">{{ item.title }}</text>
          <EntryEditor kind="albums" :entry="item" @changed="load" />
        </view>
        <text class="photo-count">{{photoCount(item)}} 张照片</text>
        <text v-if="item.images.length < photoCount(item)" class="photo-count">部分照片未加载，<text @click="load">点击重试</text></text>
        <text v-if="item.body" class="album-body">{{ item.body }}</text>
        <view v-if="item.images && item.images.length" class="grid">
          <image v-for="(url, index) in item.images" :key="index" :src="url" class="cell" mode="aspectFill" @click="preview(item.images, index)" />
        </view>
      </view>
    </view>
    <ListMore :count="albums.length" :loading="loadingMore" :exhausted="exhausted" :has-error="!!listError" @more="loadMoreAlbums" />
  </view>
</template>

<style scoped>
.panel{display:flex;flex-direction:column;gap:24rpx}
.search{height:45px;padding:0 15px;border:1px solid var(--family-border);background:white;border-radius:10px;font-size:13px}.photo-count{font-size:11px;color:var(--family-muted)}
.toolbar{display:flex;justify-content:space-between;align-items:center}
.section{font-size:36rpx;font-weight:600}
.card{background:var(--c-surface);border:1px solid var(--c-line);border-radius:20rpx;padding:28rpx;box-shadow:0 2rpx 12rpx rgba(58,52,46,.05)}
.album{display:flex;flex-direction:column;gap:14rpx}
.album-head{display:flex;justify-content:space-between;align-items:center;gap:16rpx}
.album-head :deep(.editor){margin-top:0}
.album-title{font-size:32rpx;font-weight:650;flex:1;min-width:0}
.album-body{color:var(--family-text);font-size:27rpx;line-height:1.6}
.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:12rpx}
.cell{width:100%;aspect-ratio:1;border-radius:12rpx;background:var(--c-surface-2)}
.empty{padding:80rpx 0;text-align:center;color:var(--family-muted)}
</style>
