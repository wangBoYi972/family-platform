<script setup>
import EntryEditor from "./EntryEditor.vue";
import { computed, ref, onMounted } from "vue";
import { request, loadImageUrl } from "../utils/request";

const props = defineProps({ token: String });
const albums = ref([]);
const loading = ref(false);
const keyword = ref(""), loadError = ref("");
const filteredAlbums = computed(() => albums.value.filter(item => (item.title+' '+(item.body||'')).toLocaleLowerCase().includes(keyword.value.trim().toLocaleLowerCase())));
function photoCount(item) { return String(item.fileIds||'').split(',').filter(Boolean).length; }

async function load() {
  if (!props.token) return;
  loading.value = true;
  loadError.value = "";
  try {
    albums.value = await request("/albums");
    await Promise.all(
      albums.value.map(async item => {
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
  } catch (error) {
    loadError.value = error.message || "相册加载失败";
    uni.showToast({ title: error.message || "加载失败", icon: "none" });
  } finally {
    loading.value = false;
  }
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
