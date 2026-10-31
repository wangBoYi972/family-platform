package com.familyhome.content.mapper;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 内容-文件关联表（content_file_mapping）的读写。
 *
 * <p>V4 建了这张表却一直没人写也没人读，file_ids 那串逗号分隔的字符串仍是唯一真相。
 * 从这一版开始：写入以本表为准（同时继续回写 file_ids 做兼容），读取优先本表，
 * 本表没有记录时回退旧列，保证历史数据不需要停机迁移。
 */
@Mapper
public interface ContentFileMappingMapper {

    /** 批量写入某条内容的文件关联。 */
    void insertBatch(
            @Param("contentType") String contentType,
            @Param("contentId") long contentId,
            @Param("fileIds") List<String> fileIds);

    /** 清空某条内容的关联（更新与删除前都会先调用）。 */
    int deleteByContent(@Param("contentType") String contentType, @Param("contentId") long contentId);

    /** 批量读取：一次查回多条内容的关联，避免列表页 N+1。 */
    List<Map<String, Object>> selectByContentIds(
            @Param("contentType") String contentType, @Param("contentIds") Collection<Long> contentIds);

    /**
     * 找出「旧列有值但关联表为空」的内容，用于一次性回填。
     *
     * <p>{@code table} 只接受调用方白名单里的固定表名（见 ContentFileMappingService 的枚举），
     * 不接受任何外部输入。
     */
    List<Map<String, Object>> selectLegacyRows(
            @Param("table") String table,
            @Param("contentType") String contentType,
            @Param("limit") int limit);

    /**
     * 删除某个文件在所有内容类型上的关联（文件服务删除文件后由事件触发）。
     *
     * <p>返回删掉的行数，仅为日志与统计用；重复消费同一事件的第二次调用返回 0，天然幂等。
     */
    int deleteByFileId(@Param("fileId") String fileId);

    /**
     * 按内容类型找出旧列里「疑似引用该文件」的行。
     *
     * <p>SQL 只能做 LIKE 粗筛（避免全表扫），调用方必须再按逗号切分做精确比对，
     * 否则 f1 会误伤 f12。
     */
    List<Map<String, Object>> selectLegacyByFileId(
            @Param("table") String table, @Param("fileId") String fileId);

    /** 回写旧列（去掉被删文件后的剩余引用）。table 同样只接受白名单表名。 */
    int updateLegacyFileIds(
            @Param("table") String table,
            @Param("id") long id,
            @Param("fileIds") String fileIds);
}
