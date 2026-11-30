package com.familyhome.user.model;

import com.baomidou.mybatisplus.annotation.*;
import java.time.*;
import lombok.Data;

/**
 * 微信订阅消息授权台账。一行 = 用户授权了一次某个模板 = 服务端可以推一条。
 *
 * <p>一次性订阅模板的配额模型：用户在 requestSubscribeMessage 里点一次「允许」，
 * 微信侧记一次可发送额度，客户端把这次授权上报进来落一行 {@code consumed_at = null}；
 * 服务端真正推送时消耗最早的一条。没绑定 openid 或模板没配置时原样留着，下次还能用。
 */
@TableName("wx_subscribe_grant")
@Data
public class WxSubscribeGrant {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String templateKey;
    private LocalDateTime grantedAt;
    private LocalDateTime consumedAt;
    private LocalDateTime createdAt;
}
