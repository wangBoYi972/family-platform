<script setup>
import { ref, onMounted } from "vue";
import { request, roleLabel } from "../../utils/request";

const props = defineProps({ token: String });
const members = ref([]);
const loading = ref(false);

async function load() {
  if (!props.token) return;
  loading.value = true;
  try {
    members.value = await request("/users/members");
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
    <view class="toolbar"><text class="section">家庭成员</text></view>
    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="!members.length" class="empty">暂无成员</view>
    <view v-else class="list">
      <view v-for="item in members" :key="item.id" class="row card">
        <view class="avatar">{{ (item.displayName || "?").slice(0, 1) }}</view>
        <view class="member">
          <text class="member-name">{{ item.displayName }}</text>
          <text class="member-role">{{ roleLabel[item.role] || item.role }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.panel{display:flex;flex-direction:column;gap:24rpx}
.toolbar{display:flex;justify-content:space-between;align-items:center}
.section{font-size:36rpx;font-weight:600}
.card{background:#fff;border-radius:16rpx;padding:24rpx;box-shadow:0 2rpx 12rpx rgba(29,42,38,.04)}
.row{display:flex;align-items:center;gap:22rpx}
.avatar{width:76rpx;height:76rpx;border-radius:50%;background:var(--family-primary);color:#fff;text-align:center;line-height:76rpx;font-size:30rpx}
.member{display:flex;flex-direction:column;gap:6rpx}
.member-name{font-size:30rpx;font-weight:600}
.member-role{color:var(--family-muted);font-size:24rpx}
.empty{padding:80rpx 0;text-align:center;color:var(--family-muted)}
</style>
