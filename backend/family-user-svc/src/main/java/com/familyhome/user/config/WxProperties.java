package com.familyhome.user.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 微信小程序接入配置。全部留空时订阅消息链路自动关闭（send 返回 skipped），
 * 拿到正式的 appid / secret / 模板 ID 后只需填 yml，代码不用动：
 *
 * <pre>
 * family:
 *   wx:
 *     enabled: true
 *     appid: wx1234567890abcdef
 *     secret: xxxx
 *     templates:
 *       im-message:
 *         template-id: "AbCdEf..."        # 微信后台申请的订阅消息模板 ID
 *         fields: { sender: "thing1", preview: "thing2" }   # 语义字段 -> 模板字段 ID
 * </pre>
 */
@ConfigurationProperties(prefix = "family.wx")
public class WxProperties {
    private boolean enabled = false;
    private String appid;
    private String secret;
    /** 微信接口域名，测试时可指向本地桩服务。 */
    private String apiBase = "https://api.weixin.qq.com";
    /** 模板配置：key 是业务侧语义名（im-message），value 含模板 ID 与字段映射。 */
    private Map<String, Template> templates = new LinkedHashMap<>();

    public static class Template {
        private String templateId;
        /** 页面路径，用户点开服务通知后落到哪。 */
        private String page = "pages/index/index";
        /** 语义字段 -> 微信模板字段 ID（thing1 / time2 …），由申请模板时的字段顺序决定。 */
        private Map<String, String> fields = new LinkedHashMap<>();

        public String getTemplateId() { return templateId; }
        public void setTemplateId(String v) { this.templateId = v; }
        public String getPage() { return page; }
        public void setPage(String v) { this.page = v; }
        public Map<String, String> getFields() { return fields; }
        public void setFields(Map<String, String> v) { this.fields = v; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public String getAppid() { return appid; }
    public void setAppid(String v) { this.appid = v; }
    public String getSecret() { return secret; }
    public void setSecret(String v) { this.secret = v; }
    public String getApiBase() { return apiBase; }
    public void setApiBase(String v) { this.apiBase = v; }
    public Map<String, Template> getTemplates() { return templates; }
    public void setTemplates(Map<String, Template> v) { this.templates = v; }
}
