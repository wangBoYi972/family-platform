<script setup>
import { onLaunch } from "@dcloudio/uni-app";
onLaunch(() => {});
</script>
<style>
:root { --family-primary:#2f6b5e; --family-bg:#f7f9f8; --family-text:#1d2a26; --family-muted:#687570; --family-border:#e4e9e7;
  /* 通话组件与家庭端共用 --c-* 语义色，这里映射到 APP 的品牌绿。 */
  --c-primary:#2f6b5e; --c-primary-deep:#245448; --c-primary-soft:#e3efeb; --c-primary-tint:rgba(47,107,94,.12);
  --c-ink:#1d2a26; --c-ink-2:#3b4a45; --c-muted:#687570; --c-line:#e4e9e7;
  --c-surface:#ffffff; --c-surface-2:#f1f6f4; --c-danger:#b4563f; --c-danger-soft:#f7e7e3;
  --c-glass:rgba(255,255,255,.8); --c-glass-line:rgba(255,255,255,.7); }
page { background:var(--family-bg); color:var(--family-text); font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif; }
</style>
