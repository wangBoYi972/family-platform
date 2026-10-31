<script setup>
import { onLaunch } from "@dcloudio/uni-app";
onLaunch(() => {});
</script>
<style>
:root { --family-primary:#2f6b5e; --family-bg:#f7f9f8; --family-text:#1d2a26; --family-muted:#687570; --family-border:#e4e9e7;
  /* 共享面板（apps/shared/panels、apps/shared/call）统一用 --c-* 语义色，
     这里按 APP 的品牌绿重新映射；缺一个变量在 H5 上只会静默丢样式，所以四个辅助色也必须补齐。 */
  --c-primary:#2f6b5e; --c-primary-deep:#245448; --c-primary-soft:#e3efeb; --c-primary-tint:rgba(47,107,94,.12);
  --c-ink:#1d2a26; --c-ink-2:#3b4a45; --c-muted:#687570; --c-line:#e4e9e7;
  --c-surface:#ffffff; --c-surface-2:#f1f6f4; --c-danger:#b4563f; --c-danger-soft:#f7e7e3;
  --c-sky:#7e9ab0; --c-sky-soft:#e6edf2;
  --c-sage:#7d9488; --c-sage-soft:#e7efe8;
  --c-sand:#b9955c; --c-sand-soft:#f8eedc;
  --c-plum:#8a6a8f; --c-plum-soft:#f1e9f2;
  --c-glass:rgba(255,255,255,.8); --c-glass-line:rgba(255,255,255,.7);
  --e-spring:cubic-bezier(.34,1.56,.64,1); }
page { background:var(--family-bg); color:var(--family-text); font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif; }
</style>
