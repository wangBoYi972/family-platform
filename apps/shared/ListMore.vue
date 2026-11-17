<script setup>
// 分页列表底部：四端共用一套「加载更多 / 没有更多了」的交互与文案。
defineProps({
  count: { type: Number, default: 0 },
  loading: { type: Boolean, default: false },
  exhausted: { type: Boolean, default: false },
  hasError: { type: Boolean, default: false }
});
const emit = defineEmits(["more"]);
</script>

<template>
  <view v-if="count > 0" class="list-more">
    <text v-if="hasError" class="list-more-error">加载失败</text>
    <button
      v-if="!exhausted"
      size="mini"
      class="list-more-button"
      :loading="loading"
      :disabled="loading"
      @click="emit('more')"
    >{{ loading ? "加载中…" : hasError ? "重试" : "加载更多" }}</button>
    <text v-else class="list-more-end">没有更多了</text>
  </view>
</template>

<style scoped>
.list-more{display:flex;flex-direction:column;align-items:center;gap:12rpx;padding:24rpx 0 8rpx}
.list-more-button{margin:0;padding:0 40rpx;height:64rpx;line-height:64rpx;border-radius:8rpx;font-size:24rpx;background:var(--c-surface-2,#f4efe8);color:var(--family-text,#3a342e);border:1px solid var(--c-line,#e6ded3)}
.list-more-end,.list-more-error{font-size:22rpx;color:var(--family-muted,#8b8177)}
.list-more-error{color:var(--c-danger,#b96a5b)}
</style>
