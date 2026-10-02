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
        boolean terminated = false;
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
                case DONE -> terminated = true;
                // TOOL_CALL / TOOL_RESULT：ask 只关心最终文本
                default -> {
                }
            }
        }
        // 没收到任何终止事件就走到这里 = 流被中途掐断（容器异步超时、网关截断、连接重置…）。
        // 此时手上的 answer 只是半截，绝不能当成功返回 —— 那等于让业务方拿着残缺答案继续跑。
        if (!terminated) {
            throw truncated(sessionId, collected.size());
        }
        return answer.toString();
    }

    /**
     * 终止事件：流"正常结束"的唯一凭据。
     *
     * <p>没有它，HTTP 连接被中间层掐断与"这一轮答完了"在客户端看来一模一样。</p>
     */
    private static boolean isTerminal(AgentEventType type) {
        return type == AgentEventType.DONE
                || type == AgentEventType.STOPPED
                || type == AgentEventType.ERROR
                || type == AgentEventType.INTERRUPT;
    }

    /** 流在收到终止事件前结束 */
    private static StringerException truncated(String sessionId, int received) {
        log.warn("[StringerAgent] 会话[{}] 事件流未收到终止事件即结束，已收到 {} 个事件，判定为流被截断",
                sessionId, received);
        return new StringerException(ErrorCode.EXTERNAL_SERVICE_TIMEOUT,
                "事件流在收到终止事件（DONE/STOPPED/ERROR）前结束，答案不完整（已收到 " + received
                        + " 个事件）；若本轮耗时较长，请调大 stringer.client.read-timeout 或服务端"
                        + " spring.mvc.async.request-timeout");
    }

    @Override
    public Flux<String> stream(String sessionId, String question) {
        return stream(sessionId, question, null, null);
    }

    @Override
    public Flux<String> stream(String sessionId, String question, String tenantId, String userId) {
        // 截断检测：记录是否见过终止事件，流"正常结束"时若没见过就补一个错误 ——
        // 没有终止事件就没有"答完了"的凭据，半截答案绝不能按完整答案交给业务方。
        var terminated = new java.util.concurrent.atomic.AtomicBoolean(false);
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        return events(sessionId, question, tenantId, userId)
                .concatMap(event -> {
                    counter.incrementAndGet();
                    if (isTerminal(event.getType())) {
                        terminated.set(true);
                    }
                    return switch (event.getType()) {
                        case TOKEN -> Flux.just(event.getContent() == null ? "" : event.getContent());
                        case INTERRUPT -> Flux.error(approvalRequired(sessionId, event));
                        case ERROR -> Flux.error(failure(event));
                        default -> Flux.empty();
                    };
                })
                .concatWith(Flux.defer(() -> terminated.get()
                        ? Flux.empty()
                        : Flux.error(truncated(sessionId, counter.get()))));
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
        // 会话状态按 (域, sessionId) 隔离：stop 必须带上本门面绑定的域，否则停不到这个域里的会话
        return transport.stop(sessionId, CallerContext.of(domainId));
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
