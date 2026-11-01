import { computed, ref } from "vue";
import { PAGE_SIZE, hasMore, mergePage, summarizeReload } from "./paged-list.mjs";

/**
 * 分页列表状态机（四端共用）。
 *
 * fetchPage(page, size) 由调用端注入，负责拼 page/size 并发请求，返回数组。
 * 设计要点：
 * - refresh() 重置到第一页，并让仍在飞行中的旧请求作废（token 机制）；
 * - reload() 就地刷新：已翻到第 N 页时重拉 1..N 页并原地替换，不打断阅读位置；
 * - loadMore() 追加下一页，与 refresh 竞争时以先发起者为准；
 * - 出错不抛出，写入 error 并由调用端决定提示方式，避免每个面板重复 try/catch 文案。
 */
export function usePagedList(fetchPage, { size = PAGE_SIZE, keyOf } = {}) {
  const items = ref([]);
  const page = ref(0);
  const loading = ref(false);
  const loadingMore = ref(false);
  // 就地刷新走独立状态：不能让列表退回骨架屏，也不能让「加载更多」趁机插进来。
  const syncing = ref(false);
  const exhausted = ref(false);
  const error = ref(null);
  let token = 0;

  function apply(rows) {
    return Array.isArray(rows) ? rows : [];
  }

  async function refresh() {
    const current = ++token;
    loading.value = true;
    syncing.value = false;
    error.value = null;
    try {
      const rows = apply(await fetchPage(1, size));
      if (current !== token) return true;
      items.value = mergePage([], rows, keyOf);
      page.value = 1;
      exhausted.value = !hasMore(rows.length, size);
      return true;
    } catch (e) {
      if (current === token) error.value = e;
      return false;
    } finally {
      if (current === token) loading.value = false;
    }
  }

  /**
   * 就地刷新（实时事件用）：保持「已翻到第几页」，把 1..N 页重拉回来原地替换。
   *
   * 不能直接复用 refresh()：用户正翻到第 3 页时，别的设备改了一条任务，
   * refresh 会把他拽回第一页，滚动位置也没了——这比不刷新更烦人。
   * 失败时保留原内容、只记 error，绝不把列表清空。
   */
  async function reload() {
    const target = page.value;
    // 还没加载过或者只有一页，没什么可保持的，交给 refresh 保持单一入口。
    if (target <= 1) return refresh();
    const current = ++token;
    syncing.value = true;
    error.value = null;
    try {
      const pages = await Promise.all(
        Array.from({ length: target }, (_, index) => fetchPage(index + 1, size).then(apply))
      );
      if (current !== token) return true;
      const next = summarizeReload(pages, size);
      items.value = mergePage([], next.rows, keyOf);
      page.value = next.page;
      exhausted.value = next.exhausted;
      return true;
    } catch (e) {
      if (current === token) error.value = e;
      return false;
    } finally {
      if (current === token) syncing.value = false;
    }
  }

  async function loadMore() {
    // 已在加载或已到底时不重复请求；这对调用方是「没事发生」而不是失败，所以返回 true。
    if (loading.value || loadingMore.value || syncing.value || exhausted.value) return true;
    const current = token;
    const next = page.value + 1;
    loadingMore.value = true;
    error.value = null;
    try {
      const rows = apply(await fetchPage(next, size));
      if (current !== token) return true;
      items.value = mergePage(items.value, rows, keyOf);
      page.value = next;
      exhausted.value = !hasMore(rows.length, size);
      return true;
    } catch (e) {
      if (current === token) error.value = e;
      return false;
    } finally {
      if (current === token) loadingMore.value = false;
    }
  }

  /** 清空并作废进行中的请求，用于退出登录等场景。 */
  function reset() {
    token++;
    items.value = [];
    page.value = 0;
    loading.value = false;
    loadingMore.value = false;
    syncing.value = false;
    exhausted.value = false;
    error.value = null;
  }

  return {
    items,
    page,
    loading,
    loadingMore,
    syncing,
    exhausted,
    error,
    refresh,
    reload,
    loadMore,
    reset,
    canLoadMore: computed(() => !exhausted.value && !loading.value && !loadingMore.value && !syncing.value)
  };
}
