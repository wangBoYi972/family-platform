<script setup>
import EntryEditor from "./EntryEditor.vue";
import { ref, onMounted } from "vue";
import { request } from "../utils/request";

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
    <EntryEditor kind="notices" @changed="load" />
    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="!notices.length" class="empty">暂无公告</view>
    <view v-else class="list">
      <view v-for="item in notices" :key="item.id" class="row card">
        <view class="row-head">
          <text class="row-title">{{ item.title }}</text>
          <text v-if="item.pinned" class="badge">置顶</text>
          <EntryEditor kind="notices" :entry="item" @changed="load" />
        </view>
        <text v-if="item.body" class="row-body">{{ item.body }}</text>
        <view class="row-foot">
          <text class="row-meta">{{ item.visibility === "PRIVATE" ? "仅创建者可见" : "家庭成员可见" }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.panel{display:flex;flex-direction:column;gap:24rpx}
.toolbar{display:flex;justify-content:space-between;align-items:center}
.section{font-size:36rpx;font-weight:600}
.card{background:var(--c-surface);border:1px solid var(--c-line);border-radius:20rpx;padding:28rpx;box-shadow:0 2rpx 12rpx rgba(58,52,46,.05)}
.row{display:flex;flex-direction:column;gap:16rpx}
.row-head{display:flex;justify-content:space-between;align-items:center;gap:16rpx}
.row-head :deep(.editor){margin-top:0}
.row-head .badge{flex-shrink:0}
.row-title{font-size:32rpx;font-weight:650;flex:1;min-width:0}
.row-body{color:var(--family-text);font-size:27rpx;line-height:1.7;white-space:pre-wrap}
.row-foot{display:flex;align-items:center;border-top:1px solid var(--c-surface-2);padding-top:14rpx;margin-top:4rpx}
.row-meta{color:var(--family-muted);font-size:24rpx}
.badge{font-size:22rpx;padding:6rpx 18rpx;border-radius:999rpx;background:var(--c-sand-soft);color:var(--c-sand)}
.empty{padding:80rpx 0;text-align:center;color:var(--family-muted)}
</style>
