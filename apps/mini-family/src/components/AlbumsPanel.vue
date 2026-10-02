<script setup>
import { ref, onMounted } from "vue";
import { request, loadImageUrl } from "../../utils/request";

const props = defineProps({ token: String });
const albums = ref([]);
const loading = ref(false);

async function load() {
  if (!props.token) return;
  loading.value = true;
  try {
    albums.value = await request("/albums");
    await Promise.all(
      albums.value.map(async item => {
        item.images = [];
        if (!item.fileIds) return;
        const ids = String(item.fileIds).split(",").filter(Boolean);
        for (const id of ids.slice(0, 9)) {
          try {
            item.images.push(await loadImageUrl(id));
          } catch (error) {
            // 单张图片失败不阻塞相册
          }
        }
      })
    );
  } catch (error) {
    uni.showToast({ title: error.message || "加载失败", icon: "none" });
  } finally {
    loading.value = false;
  }
}

function preview(images, current) {
  uni.previewImage({ urls: images, current });
}

defineExpose({ load });
onMounted(load);
</script>

<template>
  <view class="panel">
    <view class="toolbar">
      <text class="section">家庭相册</text>
    </view>
    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="!albums.length" class="empty">相册还是空的</view>
    <view v-else class="list">
      <view v-for="item in albums" :key="item.id" class="album card">
        <text class="album-title">{{ item.title }}</text>
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
.toolbar{display:flex;justify-content:space-between;align-items:center}
.section{font-size:36rpx;font-weight:600}
.card{background:#fff;border-radius:16rpx;padding:26rpx;box-shadow:0 2rpx 12rpx rgba(29,42,38,.04)}
.album{display:flex;flex-direction:column;gap:14rpx}
.album-title{font-size:31rpx;font-weight:600}
.album-body{color:var(--family-text);font-size:27rpx;line-height:1.6}
.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:12rpx}
.cell{width:100%;aspect-ratio:1;border-radius:12rpx;background:#eef2f0}
.empty{padding:80rpx 0;text-align:center;color:var(--family-muted)}
</style>
