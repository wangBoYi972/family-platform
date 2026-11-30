package com.familyhome.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.familyhome.security.SessionPrincipal;
import com.familyhome.user.config.WxProperties;
import com.familyhome.user.dto.WxGrantsDTO;
import com.familyhome.user.dto.WxNotifyDTO;
import com.familyhome.user.mapper.FamilyMemberMapper;
import com.familyhome.user.mapper.FamilyUserMapper;
import com.familyhome.user.mapper.WxSubscribeGrantMapper;
import com.familyhome.user.model.FamilyMember;
import com.familyhome.user.model.FamilyUser;
import com.familyhome.user.model.WxSubscribeGrant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 微信订阅消息：openid 绑定、授权配额台账、发送。
 *
 * <p>链路：客户端 wx.login 拿 code → 这里 code2session 换 openid 绑到用户；
 * 用户在点击里授权模板 → 客户端上报落一行 grant；事件发生时业务服务调 {@link #notify}，
 * 这里消耗一条 grant、按模板配置把语义字段翻译成微信字段 ID 后调 subscribe/send。
 *
 * <p>两条铁律：未配置（enabled=false / 模板缺 / openid 缺）一律静默跳过，
 * 推送失败只记日志——订阅消息是锦上添花，绝不能把消息主链路拖出异常。
 */
@Service
public class WxService {
    private static final Logger log = LoggerFactory.getLogger(WxService.class);
    /** 微信 thing 类字段上限 20 字符，超了整条发送会被拒。 */
    private static final int THING_MAX = 20;

    @Autowired private WxProperties wx;
    @Autowired private FamilyUserMapper users;
    @Autowired private FamilyMemberMapper members;
    @Autowired private WxSubscribeGrantMapper grants;
    @Autowired private ObjectMapper mapper;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    /** access_token 全局唯一、2 小时有效，过期前 5 分钟换新。 */
    private volatile String accessToken;
    private volatile long accessTokenExpiresAt;

    /** code2session：openid 是订阅消息的收件地址，没有它推不了。 */
    public void bind(SessionPrincipal p, String code) {
        requireEnabled();
        JsonNode res = getJson(jscode2sessionUrl(code));
        if (res.hasNonNull("openid") == false)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "微信登录凭证无效（" + res.path("errcode").asInt(0) + "）");
        FamilyUser user = users.selectById(p.userId());
        if (user == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        user.setWxOpenid(res.get("openid").asText());
        users.updateById(user);
    }

    /** 一次授权一条配额：accept 的每个模板落一行，reject 的忽略。 */
    public int reportGrants(SessionPrincipal p, List<WxGrantsDTO.Grant> items) {
        int saved = 0;
        for (WxGrantsDTO.Grant item : items) {
            if (!item.accepted()) continue;
            var grant = new WxSubscribeGrant();
            grant.setUserId(p.userId());
            grant.setTemplateKey(item.templateKey());
            grant.setGrantedAt(LocalDateTime.now());
            grant.setCreatedAt(LocalDateTime.now());
            grants.insert(grant);
            saved++;
        }
        return saved;
    }

    /**
     * 给 {@code userId} 发一条订阅消息。返回结果码：sent / skipped / no-quota / failed。
     *
     * <p>调用方必须与目标同家庭：这个端点拿的是用户 bearer，能拿到 token 就说明是家庭成员，
     * 但「能发」不等于「能对任意人发」——校验一下才不会变成家庭成员之间的骚扰通道。
     */
    public String notify(SessionPrincipal caller, WxNotifyDTO i) {
        if (!wx.isEnabled()) return "skipped";
        WxProperties.Template template = wx.getTemplates().get(i.templateKey());
        if (template == null || isBlank(template.getTemplateId())) return "skipped";
        FamilyUser target = users.selectById(i.userId());
        if (target == null || isBlank(target.getWxOpenid())) return "skipped";
        if (!sameHousehold(caller.userId(), i.userId())) return "skipped";
        if (!consumeGrant(i.userId(), i.templateKey())) return "no-quota";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("touser", target.getWxOpenid());
        body.put("template_id", template.getTemplateId());
        if (!isBlank(i.page())) body.put("page", i.page());
        else if (!isBlank(template.getPage())) body.put("page", template.getPage());
        body.put("data", renderFields(i.data(), template.getFields()));
        try {
            JsonNode res = postJson(
                wx.getApiBase() + "/cgi-bin/message/subscribe/send?access_token=" + accessToken(),
                body);
            int errcode = res.path("errcode").asInt(0);
            if (errcode == 0) return "sent";
            // 43101 = 用户未订阅/已取消订阅：配额已经消耗了，记下原因就好
            log.warn("订阅消息发送失败 userId={} template={} errcode={} errmsg={}",
                i.userId(), i.templateKey(), errcode, res.path("errmsg").asText(""));
            return "failed";
        } catch (Exception ex) {
            log.warn("订阅消息发送异常 userId={} template={}", i.userId(), i.templateKey(), ex);
            return "failed";
        }
    }

    /** 消耗最早的一条未用配额；乐观条件更新，并发推送同一条配额只会成功一次。 */
    private boolean consumeGrant(long userId, String templateKey) {
        WxSubscribeGrant grant = grants.selectOne(new LambdaQueryWrapper<WxSubscribeGrant>()
            .eq(WxSubscribeGrant::getUserId, userId)
            .eq(WxSubscribeGrant::getTemplateKey, templateKey)
            .isNull(WxSubscribeGrant::getConsumedAt)
            .orderByAsc(WxSubscribeGrant::getGrantedAt)
            .last("LIMIT 1"));
        if (grant == null) return false;
        return grants.update(null, new LambdaUpdateWrapper<WxSubscribeGrant>()
            .eq(WxSubscribeGrant::getId, grant.getId())
            .isNull(WxSubscribeGrant::getConsumedAt)
            .set(WxSubscribeGrant::getConsumedAt, LocalDateTime.now())) > 0;
    }

    /** 语义字段 → 微信模板字段。sender 特殊处理成昵称，其余截断到 thing 上限。 */
    private Map<String, Object> renderFields(Map<String, Object> data, Map<String, String> fields) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        if (data == null) return rendered;
        for (var entry : fields.entrySet()) {
            Object value = data.get(entry.getKey());
            if (value == null) continue;
            if ("sender".equals(entry.getKey()) && value instanceof Number id)
                value = Optional.ofNullable(users.selectById(id.longValue()))
                    .map(FamilyUser::getDisplayName).orElse("家人");
            String text = String.valueOf(value);
            rendered.put(entry.getValue(), Map.of("value", text.length() > THING_MAX
                ? text.substring(0, THING_MAX - 1) + "…" : text));
        }
        return rendered;
    }

    private boolean sameHousehold(long callerId, long targetId) {
        Long caller = householdOf(callerId), target = householdOf(targetId);
        return caller != null && caller.equals(target);
    }

    private Long householdOf(long userId) {
        FamilyMember m = members.selectOne(new LambdaQueryWrapper<FamilyMember>()
            .eq(FamilyMember::getUserId, userId).last("LIMIT 1"));
        return m == null ? null : m.getHouseholdId();
    }

    private void requireEnabled() {
        if (!wx.isEnabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "微信能力未配置");
    }

    private String accessToken() throws Exception {
        long now = System.currentTimeMillis();
        String token = accessToken;
        if (token != null && now < accessTokenExpiresAt) return token;
        JsonNode res = getJson(wx.getApiBase() + "/cgi-bin/token?grant_type=client_credential&appid="
            + URLEncoder.encode(wx.getAppid(), StandardCharsets.UTF_8)
            + "&secret=" + URLEncoder.encode(wx.getSecret(), StandardCharsets.UTF_8));
        if (!res.hasNonNull("access_token"))
            throw new IllegalStateException("获取 access_token 失败：" + res);
        accessToken = res.get("access_token").asText();
        accessTokenExpiresAt = now + Duration.ofSeconds(Math.max(60, res.path("expires_in").asInt(7200) - 300)).toMillis();
        return accessToken;
    }

    private String jscode2sessionUrl(String code) {
        return wx.getApiBase() + "/sns/jscode2session?appid="
            + URLEncoder.encode(wx.getAppid(), StandardCharsets.UTF_8)
            + "&secret=" + URLEncoder.encode(wx.getSecret(), StandardCharsets.UTF_8)
            + "&js_code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
            + "&grant_type=authorization_code";
    }

    private JsonNode getJson(String url) {
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return mapper.readTree(res.body());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "微信接口不可用");
        }
    }

    private JsonNode postJson(String url, Map<String, Object> body) throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build(), HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(res.body());
    }

    private static boolean isBlank(String v) { return v == null || v.isBlank(); }
}
