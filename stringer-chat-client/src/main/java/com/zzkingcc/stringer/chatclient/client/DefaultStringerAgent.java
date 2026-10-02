package com.zzkingcc.stringer.chatclient.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.stringer.clientcore.exception.StringerException;
import com.zzkingcc.stringer.api.agent.AgentRequest;
import com.zzkingcc.stringer.api.agent.AgentService;
import com.zzkingcc.stringer.api.agent.ApprovalRequiredException;
import com.zzkingcc.stringer.api.agent.CallerContext;
import com.zzkingcc.stringer.api.agent.StringerAgent;
import com.zzkingcc.stringer.api.code.ErrorCode;
import com.zzkingcc.stringer.api.event.AgentEvent;
import com.zzkingcc.stringer.api.event.AgentEventType;
import com.zzkingcc.stringer.api.model.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link StringerAgent} 的实现：把"答案 / 逐字 / 事件"三种消费方式收在同一个已绑定域的门面上。
 *
 * <p>三种方式的差别只在于<b>怎么消费那条事件流</b>，底层走的是同一个 {@link AgentService}
 * 远程通道 —— 没有第二套协议，也没有第二份状态。</p>
 *
 * @author zzkingcc
 */
public class DefaultStringerAgent implements StringerAgent {

    private static final Logger log = LoggerFactory.getLogger(DefaultStringerAgent.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentService transport;
    private final String domainId;

    public DefaultStringerAgent(AgentService transport, String domainId) {
        this.transport = Objects.requireNonNull(transport, "transport 不能为空");
        this.domainId = domainId;
    }

    @Override
    public String domainId() {
        return domainId;
    }

    @Override
    public String ask(String sessionId, String question) {
        return ask(sessionId, question, null, null);
    }

    @Override
    public String ask(String sessionId, String question, String tenantId, String userId) {
        List<AgentEvent> collected = events(sessionId, question, tenantId, userId).collectList().block();
        if (collected == null) {
            return "";
        }
        StringBuilder answer = new StringBuilder();
        for (AgentEvent event : collected) {
            switch (event.getType()) {
                case TOKEN -> answer.append(event.getContent() == null ? "" : event.getContent());
                // 审批挂起：宿主必须介入，因此不返回答案而是抛出去
                case INTERRUPT -> throw approvalRequired(sessionId, event);
                case ERROR -> throw failure(event);
                // 已产出的部分就是此刻能给出的全部；被停止不是错误
                case STOPPED -> {
                    return answer.toString();
                }
                default -> {
                    // TOOL_CALL / TOOL_RESULT / DONE：ask 只关心最终文本
                }
            }
        }
        return answer.toString();
    }

    @Override
    public Flux<String> stream(String sessionId, String question) {
        return stream(sessionId, question, null, null);
    }

    @Override
    public Flux<String> stream(String sessionId, String question, String tenantId, String userId) {
        return events(sessionId, question, tenantId, userId).concatMap(event -> switch (event.getType()) {
            case TOKEN -> Flux.just(event.getContent() == null ? "" : event.getContent());
            case INTERRUPT -> Flux.error(approvalRequired(sessionId, event));
            case ERROR -> Flux.error(failure(event));
            default -> Flux.empty();
        });
    }

    @Override
    public Flux<AgentEvent> events(String sessionId, String question) {
        return events(sessionId, question, null, null);
    }

    @Override
    public Flux<AgentEvent> events(String sessionId, String question, String tenantId, String userId) {
        return transport.chat(AgentRequest.builder()
                .sessionId(sessionId)
                .message(question)
                .profile(domainId)
                .tenantId(tenantId)
                .userId(userId)
                .build());
    }

    @Override
    public Flux<AgentEvent> resume(String sessionId, boolean approved) {
        return transport.resume(sessionId, approved, CallerContext.of(domainId));
    }

    @Override
    public boolean stop(String sessionId) {
        return transport.stop(sessionId);
    }

    // ==================== 事件 → 异常 ====================

    private ApprovalRequiredException approvalRequired(String sessionId, AgentEvent event) {
        return new ApprovalRequiredException(sessionId, domainId,
                parseToolCalls(event.getPayload()), event.getTraceId());
    }

    /**
     * 解析中断 payload（{@code {"tools":[{"name":..,"arguments":{..},"requireApproval":true}]}}）。
     *
     * <p>解析失败不当成"异常"往上抛：宿主仍然可以批准/拒绝，把"需要审批"这件事本身弄丢才是更糟的。</p>
     */
    private static List<ToolCall> parseToolCalls(String payload) {
        if (payload == null || payload.isBlank()) {
            return List.of();
        }
        try {
            List<ToolCall> calls = new ArrayList<>();
            for (JsonNode tool : MAPPER.readTree(payload).path("tools")) {
                String name = tool.path("name").asText("");
                if (name.isBlank()) {
                    continue;
                }
                JsonNode arguments = tool.path("arguments");
                calls.add(ToolCall.of(
                        name,
                        arguments.isMissingNode() || arguments.isNull() ? null : arguments.toString(),
                        tool.path("requireApproval").asBoolean(false)));
            }
            return List.copyOf(calls);
        } catch (Exception e) {
            log.warn("[StringerAgent] 中断 payload 解析失败，待审批清单按空处理: {}", e.getMessage());
            return List.of();
        }
    }

    /** ERROR 事件 → 带码异常；traceId 并进文案，方便一键排障 */
    private static StringerException failure(AgentEvent event) {
        ErrorCode code = event.getCode() == null ? null : ErrorCode.of(event.getCode());
        if (code == null) {
            // 服务端给了未在本版本枚举里的码：兜底成可重试的服务错误，但保留原文案
            code = ErrorCode.UNEXPECTED_ERROR;
        }
        String message = event.getContent() == null || event.getContent().isBlank()
                ? code.getMessage() : event.getContent();
        if (event.getTraceId() != null && !event.getTraceId().isBlank()) {
            message = message + "（traceId=" + event.getTraceId() + "）";
        }
        return new StringerException(code, message);
    }

    @Override
    public String toString() {
        return "StringerAgent{domainId='" + domainId + "'}";
    }
}
