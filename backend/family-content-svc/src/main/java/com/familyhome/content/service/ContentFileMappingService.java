package com.familyhome.content.service;

import com.familyhome.content.mapper.ContentFileMappingMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内容与文件的关联关系。
 *
 * <p>真相在 {@code content_file_mapping}；{@code file_ids} 旧列保留为兼容写入与回退读取，
 * 这样老数据、老客户端和灰度期间的新旧实例都能正常工作。
 */
@Service
public class ContentFileMappingService {
    private static final Logger log = LoggerFactory.getLogger(ContentFileMappingService.class);

    /** 单条内容最多关联 30 个文件，与 EntryServiceImpl 的校验保持一致。 */
    private static final int MAX_FILES = 30;

    /** 回填批量大小，避免一次性把整表读进内存。 */
    private static final int BACKFILL_BATCH = 200;

    /** kind → (content_type, 业务表名)。表名是白名单常量，绝不拼接外部输入。 */
    private static final Map<String, Kind> KINDS =
            Map.of(
                    "notices", new Kind("NOTICE", "family_notice"),
                    "calendar", new Kind("CALENDAR", "family_calendar"),
                    "dynamics", new Kind("DYNAMIC", "family_dynamic"),
                    "albums", new Kind("ALBUM", "family_album"));

    private record Kind(String contentType, String table) {}

    @Autowired private ContentFileMappingMapper mapping;

    /** 覆盖式写入：先清空再按给定顺序写入，更新与创建共用。 */
    @Transactional
    public void replace(String kind, long contentId, String csv) {
        Kind k = kind(kind);
        mapping.deleteByContent(k.contentType(), contentId);
        List<String> ids = split(csv);
        if (!ids.isEmpty()) mapping.insertBatch(k.contentType(), contentId, ids);
    }

    @Transactional
    public void remove(String kind, long contentId) {
        mapping.deleteByContent(kind(kind).contentType(), contentId);
    }

    /**
     * 批量读取关联，返回 contentId → 逗号串。
     *
     * <p>列表页一次查完，避免每条内容都查一次映射表。
     */
    @Transactional(readOnly = true)
    public Map<Long, String> load(String kind, Collection<Long> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) return Map.of();
        List<Map<String, Object>> rows =
                mapping.selectByContentIds(kind(kind).contentType(), contentIds);
        if (rows.isEmpty()) return Map.of();
        Map<Long, String> result = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("content_id")).longValue();
            String fileId = String.valueOf(row.get("file_id"));
            result.merge(id, fileId, (a, b) -> a + "," + b);
        }
        return result;
    }

    /**
     * 一次性回填历史数据：旧列有值、映射表为空的行补写进映射表。
     *
     * <p>幂等，可以每次启动都跑；没有待回填数据时只是四次空查询。
     *
     * @return 回填的总行数
     */
    @Transactional
    public int backfillLegacy() {
        int total = 0;
        for (Map.Entry<String, Kind> entry : KINDS.entrySet()) {
            int filled = 0;
            while (true) {
                List<Map<String, Object>> rows =
                        mapping.selectLegacyRows(
                                entry.getValue().table(), entry.getValue().contentType(), BACKFILL_BATCH);
                if (rows.isEmpty()) break;
                for (Map<String, Object> row : rows) {
                    long id = ((Number) row.get("id")).longValue();
                    List<String> ids = split(String.valueOf(row.get("file_ids")));
                    if (!ids.isEmpty())
                        mapping.insertBatch(entry.getValue().contentType(), id, ids);
                }
                filled += rows.size();
                if (rows.size() < BACKFILL_BATCH) break;
            }
            if (filled > 0) log.info("content_file_mapping 回填 kind={} rows={}", entry.getKey(), filled);
            total += filled;
        }
        return total;
    }

    /** 逗号串 → 去重有序的文件 id，空白与超限都在这里收口。 */
    static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String id = part.trim();
            if (!id.isEmpty()) unique.add(id);
        }
        List<String> ids = new ArrayList<>(unique);
        return ids.size() > MAX_FILES ? ids.subList(0, MAX_FILES) : ids;
    }

    private static Kind kind(String kind) {
        Kind k = KINDS.get(kind);
        if (k == null) throw new IllegalArgumentException("未知内容类型: " + kind);
        return k;
    }
}
