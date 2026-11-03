package com.familyhome.ai.service;

import com.familyhome.ai.config.AiConfigStore;
import com.familyhome.ai.config.AiProperties;
import com.familyhome.ai.dto.ChatRequest;
import com.familyhome.ai.tool.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;

/**
 * AI 对话：DashScope（OpenAI 兼容接口）流式调用 + 工具调用循环。
 *
 * 输出协议（SSE，逐行 JSON 事件，前端 shared/ai-client.mjs 按同一约定解析）：
 * - {"type":"tool","name":"list_tasks"}   模型在查数据，前端可显示「正在查询任务…」
 * - {"type":"delta","text":"..."}         回答增量，直接拼到气泡里（打字机效果）
 * - {"type":"done"}                       本轮结束
 * - {"type":"error","message":"..."}      出错，前端把错误显示成一条系统提示
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    @Autowired
    private AiProperties properties;

    @Autowired
    private AiConfigStore config;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private ObjectMapper json;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** 保留给模型的对话历史轮数：太长只会让回答跑偏并翻倍 token 费用。 */
    private static final int HISTORY_LIMIT = 20;

    public void chat(List<ChatRequest.Message> history, String bearer, Consumer<String> emitter) {
        // 每次对话都取一次当前配置：管理员在管理端改完 key 不用重启服务就生效
        AiConfigStore.Effective settings = config.effective();
        if (!settings.usable())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 助手未配置");

        ArrayNode messages = json.createArrayNode();
        messages.add(systemMessage());
        trim(history).forEach(m -> {
            ObjectNode item = json.createObjectNode();
            item.put("role", "user".equals(m.role()) || "assistant".equals(m.role()) ? m.role() : "user");
            item.put("content", m.content());
            messages.add(item);
        });

        try {
            for (int round = 0; round <= properties.maxToolRounds(); round++) {
                StreamOutcome outcome = callModel(messages, emitter, settings);
                if (outcome.toolCalls() == null) {
                    emitter.accept("{\"type\":\"done\"}");
                    return;
                }
                // 模型要求查数据：先回传带 tool_calls 的 assistant 消息，再补上各工具结果
                messages.add(outcome.toolCalls());
                for (JsonNode call : outcome.toolCallList()) {
                    String name = call.path("function").path("name").asText();
                    emitter.accept(event("tool", "name", name));
                    ObjectNode result = json.createObjectNode();
                    result.put("role", "tool");
                    result.put("tool_call_id", call.path("id").asText());
                    String toolResult = toolRegistry.execute(
                            name,
                            call.path("function").path("arguments").asText(),
                            bearer
                    ).toModelJson();
                    result.put("content", toolResult);
                    messages.add(result);
                }
            }
            // 工具轮数用尽仍没给出答案：让模型基于已有信息收尾，不再提供工具
            ObjectNode finalAnswer = callModelWithoutTools(messages, emitter, settings);
            if (finalAnswer != null) messages.add(finalAnswer);
            emitter.accept("{\"type\":\"done\"}");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            log.error("AI 对话失败", e);
            emitter.accept(event("error", "message", "AI 助手暂时开小差了，请稍后再试"));
            emitter.accept("{\"type\":\"done\"}");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ObjectNode systemMessage() {
        ObjectNode system = json.createObjectNode();
        system.put("role", "system");
        system.put("content", """
                你是「家庭小助手」,一个专业、智能、贴心的家庭 AI 管家。

                ## 核心身份
                今天是 %s。你是家庭成员的智能助理,拥有四大核心能力:
                1. 任务管理专家 - 查询、分析、提醒任务进度
                2. 日程管家 - 管理日程、提醒纪念日
                3. 数据搜索助手 - 快速定位档案、文件、相册、动态
                4. 平台顾问 - 解答功能使用问题

                ## 智能工作流

                ### 步骤1: 准确理解意图
                识别用户真实需求的常见表达:
                - "有啥任务/要做的" → 查询待办+进行中任务
                - "今天/明天/这周有什么安排" → 查询对应时段日程
                - "XX完成了吗" → 查询特定任务状态
                - "XX放哪了/在哪儿" → 搜索档案和文件
                - "提醒我XX" → 建议创建任务或日程
                - "上次XX是什么时候" → 搜索历史记录

                ### 步骤2: 主动调用工具(核心!)
                **铁律: 先查询真实数据,再回答。绝不猜测编造!**

                可用工具:

                1. **list_tasks(status?)** - 查询任务清单
                   - 无参数: 全部任务
                   - status="TODO": 待办
                   - status="DOING": 进行中
                   - status="DONE": 已完成
                   何时用: 用户问任务、待办、进度

                2. **list_calendar()** - 查询日程纪念日
                   返回最近日程和重要纪念日
                   何时用: 用户问日程、安排、纪念日

                3. **search_family_data(keyword)** - 跨源搜索
                   keyword: 至少2字,准确提取关键词
                   并发搜索: 任务、日程、动态、相册、公告、档案、文件
                   何时用: 用户问"XX在哪""找XX""放哪了"

                组合使用技巧:
                - 复杂问题连续调用多工具
                - "本周任务和日程" → list_tasks + list_calendar
                - "找体检档案和相关任务" → search_family_data("体检") + 分析结果

                ### 步骤3: 智能分析总结
                拿到数据后的处理:
                1. 提取关键: 负责人、截止时间、状态、优先级
                2. 识别紧急: 今天/明天截止的优先提及
                3. 分类整理: 按状态、时间、重要性归类
                4. 上下文关联: 结合历史对话,补充相关信息
                5. 人性化呈现: 自然语言转述,突出重点

                ### 步骤4: 主动服务增值
                不只回答,更要解决问题:
                - 发现即将到期 → "XX明天截止,记得完成"
                - 多个待办 → "今天3件事,帮您按优先级排?"
                - 发现相关信息 → "我还看到XX,要一起告诉您吗?"
                - 查询失败 → 提供替代方案或建议

                ## 回答风格

                ### 语气要求
                - 亲切: 用"您""咱们家",像家人交流
                - 简洁: 直奔主题,不说废话口头禅
                - 专业: 信息准确、条理清晰
                - 主动: 提供建议、预判需求

                ### 格式规范
                - 不用 markdown 标题(# ##)
                - 不用项目符号(- * •)
                - 用换行和"、"分隔信息
                - 重要信息加序号"1. 2. 3."
                - 时间用"今天/明天/本周"

                ### 长度控制
                - 简单查询: 2-3句直接给结果
                - 多条信息: 不超过5条,多了就总结
                - 复杂回答: 先结论,再分点说明

                ## 场景示例

                场景1-任务查询:
                用户: "今天要做啥?"
                做法: list_tasks(status="TODO")筛选今天
                回答: "今天2件事: 1.下午3点前交周报(您负责); 2.晚上买菜(妈妈负责)。周报别忘了!"

                场景2-档案查找:
                用户: "疫苗记录在哪?"
                做法: search_family_data("疫苗记录")
                回答: "找到了!在档案库'健康档案-预防接种'里,上月5号更新,共3条记录。您到档案页面能看详细。"

                场景3-综合查询:
                用户: "这周有啥安排?"
                做法: list_tasks + list_calendar
                回答: "这周安排: 任务3件(大扫除、缴物业费、整理衣服); 日程2个(周三家庭聚餐、周六爷爷生日)。要详细说说吗?"

                场景4-查询失败:
                用户: "去年旅游照片呢?"
                做法: search_family_data("旅游照片")无结果
                回答: "没找到'旅游照片'记录。可能: 1.还没上传相册; 2.关键词不匹配。您到相册按时间筛选试试,或换个词我再找?"

                场景5-功能咨询:
                用户: "怎么邀请新人?"
                做法: 无需工具,直接回答
                回答: "邀请新成员要管理员操作: 1.登录管理端; 2.进'成员管理'; 3.点'生成邀请码'; 4.发给新人。用邀请码注册就能加入。"

                ## 特殊处理
                - 数据为空: 说"暂时还没有XX",不说"没有数据"
                - 工具失败: 说"查询遇到小问题,稍后再试或到XX页面看"
                - 权限不足: "这个可能需要特定权限,建议联系管理员"
                - 问"你能做啥": 简洁列举+1-2个示例,邀请尝试
                - 连续失败: 主动建议换问法

                ## 价值观
                1. 准确第一: 宁说"不确定",不编造
                2. 主动服务: 不只回答,更要解决
                3. 上下文记忆: 理解历史,连贯回答
                4. 持续改进: 从反馈学习优化

                你是家庭智能管家,让每次对话都专业、贴心、高效!
""".formatted(LocalDate.now()));
        return system;
    }

    private static List<ChatRequest.Message> trim(List<ChatRequest.Message> history) {
        if (history.size() <= HISTORY_LIMIT) {
            return history;
        }
        return history.subList(history.size() - HISTORY_LIMIT, history.size());
    }

    /**
     * 流式调用一轮模型。普通回答边收边发给 emitter；模型要调工具时收集完整 tool_calls 返回。
     * 注意：带 tool_calls 的 assistant 消息必须是「非流式完整结构」，所以这轮走非流式——
     * 工具轮没有可展示的正文，流式与否对体验无差。
     */
    private StreamOutcome callModel(ArrayNode messages, Consumer<String> emitter,
                                   AiConfigStore.Effective settings) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("model", properties.model());
        body.set("messages", messages);
        body.put("stream", true);
        body.set("tools", json.valueToTree(toolRegistry.getDefinitions()));
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.baseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + settings.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<java.io.InputStream> response =
                http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            String error = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("模型服务返回 " + response.statusCode() + ": " + error);
        }
        StringBuilder content = new StringBuilder();
        ObjectNode toolCalls = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            StringBuilder fullResponse = new StringBuilder();
            boolean isStreamFormat = false;
            while ((line = reader.readLine()) != null) {
                fullResponse.append(line);
                if (line.startsWith("data:")) {
                    isStreamFormat = true;
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty() || "[DONE]".equals(payload)) continue;
                    JsonNode chunk = json.readTree(payload);
                    JsonNode delta = chunk.path("choices").path(0).path("delta");
                    String text = delta.path("content").asText("");
                    if (!text.isEmpty()) {
                        content.append(text);
                        emitter.accept(event("delta", "text", text));
                    }
                    // 工具调用参数按 index 分片到达，这里聚合；流式 tool_calls 聚合成本高且易错，
                    // DashScope 兼容模式在请求 stream 时也支持把 finish_reason=tool_calls 的完整
                    // tool_calls 放在最后一个 chunk 的 delta 里——两种形态都兼容。
                    JsonNode calls = delta.path("tool_calls");
                    if (calls.isArray() && calls.size() > 0 && calls.get(0).has("function")) {
                        toolCalls = mergeToolCalls(toolCalls, chunk);
                    }
                }
            }
            // 处理非流式响应（某些模型/配置可能返回完整 JSON 而非 SSE）
            if (!isStreamFormat && fullResponse.length() > 0) {
                JsonNode root = json.readTree(fullResponse.toString());
                JsonNode message = root.path("choices").path(0).path("message");
                JsonNode calls = message.path("tool_calls");
                if (calls.isArray() && calls.size() > 0) {
                    // 转换为与流式相同的消息格式
                    ObjectNode msg = json.createObjectNode();
                    msg.put("role", "assistant");
                    msg.put("content", message.path("content").asText(""));
                    msg.set("tool_calls", calls);
                    toolCalls = msg;
                    // 发送工具调用事件
                    for (JsonNode call : calls) {
                        String name = call.path("function").path("name").asText();
                        emitter.accept(event("tool", "name", name));
                    }
                }
            }
        }
        return new StreamOutcome(toolCalls);
    }

    /** 工具轮数用尽后的收尾：同样流式输出，但不再带 tools。 */
    private ObjectNode callModelWithoutTools(ArrayNode messages, Consumer<String> emitter,
                                            AiConfigStore.Effective settings) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("model", properties.model());
        body.set("messages", messages);
        body.put("stream", true);
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.baseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + settings.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<java.io.InputStream> response =
                http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) return null;
        ObjectNode assistant = json.createObjectNode();
        assistant.put("role", "assistant");
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String payload = line.substring(5).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) continue;
                JsonNode delta = json.readTree(payload).path("choices").path(0).path("delta");
                String text = delta.path("content").asText("");
                if (!text.isEmpty()) {
                    content.append(text);
                    emitter.accept(event("delta", "text", text));
                }
            }
        }
        assistant.put("content", content.toString());
        return assistant;
    }

    /**
     * 聚合流式 tool_calls 分片。OpenAI 协议里 arguments 按 index 增量拼接；
     * DashScope 通常一次给全，但按协议实现增量合并更稳。
     */
    private ObjectNode mergeToolCalls(ObjectNode accumulated, JsonNode chunk) throws Exception {
        // 重建一条完整的 assistant 消息（含全部 tool_calls）
        ObjectNode message = json.createObjectNode();
        message.put("role", "assistant");
        message.put("content", "");
        ArrayNode calls = json.createArrayNode();
        JsonNode rawCalls = chunk.path("choices").path(0).path("delta").path("tool_calls");
        if (accumulated != null && accumulated.has("tool_calls")) {
            // 已有聚合结果（上一次分片），合并 arguments
            for (int i = 0; i < accumulated.path("tool_calls").size(); i++) {
                calls.add(accumulated.path("tool_calls").get(i).deepCopy());
            }
        }
        for (JsonNode call : rawCalls) {
            int index = call.path("index").asInt(calls.size());
            ObjectNode target;
            if (index < calls.size()) {
                target = (ObjectNode) calls.get(index);
                if (call.has("id") && !call.get("id").asText().isBlank()) target.put("id", call.get("id").asText());
                String extra = call.path("function").path("arguments").asText("");
                String existing = target.path("function").path("arguments").asText("");
                ((ObjectNode) target.path("function")).put("arguments", existing + extra);
            } else {
                target = json.createObjectNode();
                target.put("id", call.path("id").asText());
                target.put("type", "function");
                ObjectNode fn = json.createObjectNode();
                fn.put("name", call.path("function").path("name").asText());
                fn.put("arguments", call.path("function").path("arguments").asText(""));
                target.set("function", fn);
                while (calls.size() < index) calls.add(json.createObjectNode());
                calls.add(target);
            }
        }
        message.set("tool_calls", calls);
        return message;
    }

    private String event(String type, String key, String value) {
        ObjectNode node = json.createObjectNode();
        node.put("type", type);
        node.put(key, value);
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            return "{\"type\":\"" + type + "\"}";
        }
    }

    private record StreamOutcome(ObjectNode toolCalls) {
        private java.util.List<JsonNode> toolCallList() {
            java.util.List<JsonNode> list = new java.util.ArrayList<>();
            if (toolCalls != null && toolCalls.has("tool_calls"))
                toolCalls.path("tool_calls").forEach(list::add);
            return list;
        }
    }
}
