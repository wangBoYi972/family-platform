<script setup>
import { ref, onMounted } from "vue";
import { request } from "../../utils/request";

const props = defineProps({ token: String });
const notices = ref([]);
const loading = ref(false);

async function load() {
  if (!props.token) return;
  loading.value = true;
  try {
    notices.value = await request("/notices");
  } catch (error) {
    uni.showToast({ title: error.message || "加载失败", icon: "none" });
  } finally {
    loading.value = false;
  }
}

defineExpose({ load });
onMounted(load);
</script>

<template>
  <view class="panel">
    <view class="toolbar"><text class="section">家庭公告</text></view>
    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="!notices.length" class="empty">暂无公告</view>
    <view v-else class="list">
      <view v-for="item in notices" :key="item.id" class="row card">
        <view class="row-head">
          <text class="row-title">{{ item.title }}</text>
          <text v-if="item.pinned" class="badge">置顶</text>
        </view>
        <text v-if="item.body" class="row-body">{{ item.body }}</text>
        <text class="row-meta">{{ item.visibility === "PRIVATE" ? "仅创建者可见" : "家庭成员可见" }}</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.panel{display:flex;flex-direction:column;gap:24rpx}
.toolbar{display:flex;justify-content:space-between;align-items:center}
.section{font-size:36rpx;font-weight:600}
.card{background:#fff;border-radius:16rpx;padding:26rpx;box-shadow:0 2rpx 12rpx rgba(29,42,38,.04)}
.row{display:flex;flex-direction:column;gap:12rpx}
.row-head{display:flex;justify-content:space-between;align-items:center}
.row-title{font-size:31rpx;font-weight:600}
.row-body{color:var(--family-text);font-size:27rpx;line-height:1.6;white-space:pre-wrap}
.row-meta{color:var(--family-muted);font-size:24rpx}
.badge{font-size:22rpx;padding:6rpx 16rpx;border-radius:999rpx;background:#fdf1e3;color:#b26a00}
.empty{padding:80rpx 0;text-align:center;color:var(--family-muted)}
</style>
