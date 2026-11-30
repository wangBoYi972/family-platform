-- 微信订阅消息：openid 绑定 + 授权配额台账
-- 订阅消息是「用户点一次授权 = 服务端能发一条」的一次性配额模型：
-- 客户端 requestSubscribeMessage 授权成功后上报，这里落一行未消费的 grant；
-- 服务端要推送时消耗最早的一条，发不出去（没绑定 openid / 未配置模板）就原样留着。
ALTER TABLE family_user ADD COLUMN wx_openid VARCHAR(64) NULL;

CREATE TABLE wx_subscribe_grant (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    template_key VARCHAR(64) NOT NULL,
    granted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    consumed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_wx_grant_pending (user_id, template_key, consumed_at)
);
