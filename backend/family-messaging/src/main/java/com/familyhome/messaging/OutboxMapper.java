package com.familyhome.messaging;

import java.time.OffsetDateTime;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 事件持久化统一使用业务服务的 MyBatis 会话与数据源。 */
public interface OutboxMapper {
    int insert(@Param("id") String id, @Param("aggregateType") String aggregateType,
            @Param("aggregateId") String aggregateId, @Param("eventType") String eventType,
            @Param("householdId") long householdId, @Param("payload") String payload,
            @Param("createdAt") OffsetDateTime createdAt);
    List<Map<String, Object>> pending();

    /**
     * 认领一条待发事件：把 locked_at 打到当前时间，返回 1 表示本实例抢到。
     *
     * <p>多实例同时轮询时，只有 UPDATE 命中的那次能继续投递，避免同一事件被重复发送；
     * 认领后进程崩溃，租约到期（30 秒）会被其它实例重新捡起。
     */
    int claim(@Param("id") String id);

    int published(@Param("id") String id);

    int retry(@Param("id") String id, @Param("nextAttemptAt") Timestamp nextAttemptAt,
            @Param("lastError") String lastError);

    /** 重试到上限后转为 FAILED，不再参与轮询，留给人工排查。 */
    int markFailed(@Param("id") String id, @Param("lastError") String lastError);

    /** 分批删除已发布且超过保留期的事件，返回本次删除行数。 */
    int purgePublished(@Param("before") Timestamp before, @Param("limit") int limit);

    /** 统计积压超过保留期仍未发布的事件——正常情况下应长期为 0。 */
    int countStalePending(@Param("before") Timestamp before);

    /** 统计已转入死信的事件数。 */
    int countFailed();
}
