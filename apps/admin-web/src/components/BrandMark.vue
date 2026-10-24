<script setup>
/**
 * BrandMark — 手绘线稿小屋，替代原来的渐变方块"家"徽标。
 * 线条故意带一点抖动，像用签字笔随手画的，不像 AI 生成的标准图形。
 */
defineProps({
  size: { type: Number, default: 26 },
});
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 28 28"
    fill="none"
    stroke="currentColor"
    stroke-width="2"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <!-- 烟囱 -->
    <path d="M19.1 6.3 l0.2 -3.1 l2.7 0.15 l-0.25 4.2" />
    <!-- 屋顶（两笔错位，模拟手绘） -->
    <path d="M3.4 13.6 C 7 10.4, 10.6 7.4, 14 4.9 C 17.3 7.5, 20.9 10.6, 24.5 13.9" />
    <path d="M6.1 13.2 C 8.8 10.9, 11.4 8.9, 13.9 7 C 16.2 8.9, 18.8 11, 21.6 13.4" />
    <!-- 墙体 -->
    <path d="M6.9 12.9 C 6.95 16.4, 7 19.9, 7.05 23.3 C 11.6 23.45, 16.3 23.45, 20.9 23.3 C 20.95 19.9, 21 16.4, 21.1 13.1" />
    <!-- 门 -->
    <path d="M11.4 23.2 C 11.45 20.9, 11.5 18.7, 11.6 16.6 C 12.9 15.9, 15 15.9, 16.3 16.6 C 16.4 18.7, 16.45 20.9, 16.5 23.2" />
    <!-- 门把手 -->
    <circle cx="15.15" cy="19.4" r="0.5" fill="currentColor" stroke="none" />
  </svg>
</template>
