package com.zzkingcc.stringer.runtime.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.stringer.api.agent.AgentRequest;
import com.zzkingcc.stringer.api.agent.AgentService;
import com.zzkingcc.stringer.api.agent.CallerContext;
import com.zzkingcc.stringer.api.agent.Domains;
import com.zzkingcc.stringer.api.code.ErrorCode;
import com.zzkingcc.stringer.api.event.AgentEvent;
import com.zzkingcc.stringer.api.model.ToolCall;
import com.zzkingcc.stringer.api.model.ToolCallPayload;
import com.zzkingcc.stringer.api.support.TraceId;
import com.zzkingcc.stringer.common.exception.NotConfiguredException;
import com.zzkingcc.stringer.domain.memory.DualConstraintChatMemory;
import com.zzkingcc.stringer.runtime.cancellation.CancellationRegistry;
import com.zzkingcc.stringer.runtime.model.ModelResolver;
import com.zzkingcc.stringer.runtime.prompt.SystemPromptResolver;
import com.zzkingcc.stringer.runtime.stream.StreamContext;
import com.zzkingcc.stringer.runtime.stream.StreamSinkRegistry;
import com.zzkingcc.stringer.runtime.tool.ToolInvocationContext;
import com.zzkingcc.stringer.runtime.tool.ToolRouter;
import com.zzkingcc.stringer.runtime.usage.TokenUsageRecorder;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncCommandAction;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.bsc.langgraph4j.action.Command;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

/**
 * 基于 LangGraph4j 的 Agent 图节点状态机编排
 *
 * @author zzkingcc
 */
@Slf4j
public class AgentOrchestrationService implements AgentService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 条件边的分支键。
     */
    private static final String BRANCH_EXIT = "exit";
    private static final String BRANCH_AUTO = "auto";
    private static final String BRANCH_REVIEW = "review";

    private final CompiledGraph<MessagesState<ChatMessage>> compiledGraph;
    private final ChatMemoryProvider chatMemoryProvider;
    private final StreamingChatModel streamingChatModel;
    /**
     * 系统提示词解析器
     */
    private final SystemPromptResolver promptResolver;
    private final CancellationRegistry cancellationRegistry;
    private final BaseCheckpointSaver checkpointSaver;
    /** 工具路由器,Agent 调用能力的唯一入口 */
    private final ToolRouter toolRouter;
    /** 会话 → 流式上下文（以 sessionId 定位，与线程解耦） */
    private final StreamSinkRegistry streamSinks;
    /** 执行图编排的专用线程池(阻塞式流式调用,不占用公共 ForkJoinPool) */
    private final Executor executor;

    /**
     * 按域解析对话模型；为 {@code null} 时全部使用构造期注入的那个模型（行为与"全局一个模型"一致）。
     */
    private final ModelResolver modelResolver;

    /**
     * 会话 → 中断时所用的域。
     */
    private final Map<String, String> interruptedProfiles = new ConcurrentHashMap<>();

    /**
     * 单模型构造（兼容入口）：所有域共用同一个模型。
     */
    public AgentOrchestrationService(
            StreamingChatModel streamingChatModel,
            ChatMemoryProvider chatMemoryProvider,
            ToolRouter toolRouter,
            SystemPromptResolver promptResolver,
            BaseCheckpointSaver checkpointSaver,
            ObjectStreamStateSerializer<MessagesState<ChatMessage>> stateSerializer,
            CancellationRegistry cancellationRegistry,
            Executor executor) {
        this(streamingChatModel, chatMemoryProvider, toolRouter, promptResolver, checkpointSaver,
                stateSerializer, cancellationRegistry, executor, null);
    }

    public AgentOrchestrationService(
            StreamingChatModel streamingChatModel,
            ChatMemoryProvider chatMemoryProvider,
            ToolRouter toolRouter,
            SystemPromptResolver promptResolver,
            BaseCheckpointSaver checkpointSaver,
            ObjectStreamStateSerializer<MessagesState<ChatMessage>> stateSerializer,
            CancellationRegistry cancellationRegistry,
            Executor executor,
            ModelResolver modelResolver) {

        this.streamingChatModel = streamingChatModel;
        this.chatMemoryProvider = chatMemoryProvider;
        this.promptResolver = Objects.requireNonNull(promptResolver, "promptResolver");
        this.checkpointSaver = checkpointSaver;
        this.cancellationRegistry = cancellationRegistry;
        this.toolRouter = toolRouter;
        this.streamSinks = new StreamSinkRegistry();
        this.executor = Objects.requireNonNull(executor, "executor");
        this.modelResolver = modelResolver;

        // 工具规格不在构造期固化：域是按请求变化的，工具集必须在 agent 节点运行时
        // 用 toolRouter.getToolSpecifications(profile) 按本轮所处的域过滤。
        int totalTools = toolRouter.getToolSpecifications().size();
        int approvalTools = toolRouter.getToolsRequiringApproval().size();

        this.compiledGraph = buildGraph(checkpointSaver, stateSerializer);

        log.info("[Agent编排-LangGraph] 初始化完成,注册表内共 {} 个工具(其中 {} 个需授权);"
                        + "已出现的域: {};实际可见工具集与系统提示词都按每次请求的域动态解析;"
                        + "图结构: agent→(auto|review)→tools→agent(条件循环)",
                totalTools, approvalTools, toolRouter.getKnownProfiles());
    }

    /**
     * 构建 StateGraph 并编译
     */
    private CompiledGraph<MessagesState<ChatMessage>> buildGraph(BaseCheckpointSaver checkpointSaver,
                                                                 ObjectStreamStateSerializer<MessagesState<ChatMessage>> stateSerializer) {
        try {
            var workflow = new StateGraph<MessagesState<ChatMessage>>(messagesSchema(), stateSerializer)
                    .addNode("agent", syncNode(this::agentNode))
                    .addNode("review", syncNode((state, config) -> Map.<String, Object>of()))   // no-op 节点,仅作中断锚点
                    .addNode("tools", syncNode(this::toolsNode))
                    .addEdge(START, "agent")
                    // 条件边需要 RunnableConfig（要从中取 sessionId → 调用方权限）才能决定路由。
                    // langgraph4j 1.8.17 的 AsyncEdgeAction 只吃一个参数，得用 AsyncCommandAction ——
                    // 它本质是 BiFunction<State, RunnableConfig, Command>，与 addConditionalEdges 兼容。
                    // 注意：Command 里放的是【分支键】，不是节点名（详见 routeAfterAgent 注释）。
                    .addConditionalEdges("agent", routeAfterAgent(),
                            Map.of(BRANCH_EXIT, END, BRANCH_AUTO, "tools", BRANCH_REVIEW, "review"))
                    .addEdge("review", "tools")   // approve 后执行工具
                    .addEdge("tools", "agent");

            var compileConfig = org.bsc.langgraph4j.CompileConfig.builder()
                    .checkpointSaver(checkpointSaver)
                    .interruptBefore("review")    // 仅 review 节点前中断,tools 直连不暂停
                    .releaseThread(true)          // 正常结束后释放 checkpoint,避免无限堆积
                    .build();

            return workflow.compile(compileConfig);
        } catch (org.bsc.langgraph4j.GraphStateException e) {
            throw new IllegalStateException("StateGraph 构建失败", e);
        }
    }

    /**
     * 图状态的通道定义
     */
    private static Map<String, Channel<?>> messagesSchema() {
        Map<String, Channel<?>> schema = new HashMap<>(MessagesState.SCHEMA);
        schema.put(MessagesState.MESSAGES_STATE,
                Channels.<ChatMessage>appenderWithDuplicate(ArrayList::new));
        return schema;
    }

    /**
     * 同步节点包装器:在调用线程上执行节点逻辑,不切换线程
     */
    private AsyncNodeActionWithConfig<MessagesState<ChatMessage>> syncNode(
            BiFunction<MessagesState<ChatMessage>, RunnableConfig, Map<String, Object>> action) {
        return (state, config) -> {
            try {
                return CompletableFuture.completedFuture(action.apply(state, config));
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        };
    }

    /** 从 RunnableConfig 取 sessionId */
    private String sessionIdOf(RunnableConfig config) {
        return config.threadId().orElseThrow(() -> new IllegalStateException("RunnableConfig 缺少 threadId"));
    }

    /**
     * agent 节点:调用流式模型,推送 TOKEN 事件,返回 AiMessage 到状态
     */
    private Map<String, Object> agentNode(MessagesState<ChatMessage> state, RunnableConfig config) {
        String sessionId = sessionIdOf(config);
        StreamContext context = streamSinks.get(sessionId);
        AtomicInteger llmOutputTokens = new AtomicInteger(0);

        // 按本轮所处的域过滤工具集：只有声明了该域（或未声明任何域）的工具才会出现在模型视野里。
        // 域是"按请求"的，因此必须在此处（运行时）过滤，不能在构造函数里固化。
        String profile = context == null ? null : context.profile();
        List<dev.langchain4j.agent.tool.ToolSpecification> visibleTools =
                toolRouter.getToolSpecifications(profile);

        // 本轮使用哪个模型：按域解析（执行单元内取一次，与"域、提示词"同一条冻结规则）
        StreamingChatModel model = resolveModel(profile);

        try {
            var parameters = ChatRequestParameters.builder()
                    .toolSpecifications(visibleTools)
                    .build();
            var request = ChatRequest.builder()
                    .messages(state.messages())
                    .parameters(parameters)
                    .build();

            CompletableFuture<AiMessage> future = new CompletableFuture<>();

            model.chat(request, new StreamingChatResponseHandler() {                @Override
                public void onPartialResponse(String partialResponse) {
                    // 停止检查:用户请求停止后,完成 future 抛 CancellationException,终止流
                    if (cancellationRegistry.isCancelled(sessionId)) {
                        future.completeExceptionally(new CancellationException("用户主动停止"));
                        return;
                    }
                    if (context != null) {
                        llmOutputTokens.addAndGet(TokenUsageRecorder.estimateTokens(partialResponse));
                        context.appendPartial(partialResponse);
                        context.emit(AgentEvent.token(sessionId, partialResponse));
                    }
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    // 这个回调同样要查停止标志：模型不分片、一次性返回时若只在 onPartialResponse
                    // 里检查，取消就完全不生效——用户点了停止，这一轮照旧跑完并写入记忆。
                    if (cancellationRegistry.isCancelled(sessionId)) {
                        future.completeExceptionally(new CancellationException("用户主动停止"));
                        return;
                    }
                    future.complete(completeResponse.aiMessage());
                }

                @Override
                public void onError(Throwable error) {
                    future.completeExceptionally(error);
                }
            });

            AiMessage aiMessage = future.join();
            return Map.of("messages", aiMessage);
        } finally {
            // 失败路径也要统计：模型已经开始产 token 就产生了用量，只记成功路径会漏
            TokenUsageRecorder.addLlmOutputTokens(llmOutputTokens.get());
        }
    }

    /**
     * 解析本轮使用的对话模型。
     *
     * <p>没有解析器（未装配模型档案的部署、单元测试）或解析不到时，退回构造期注入的模型 ——
     * 于是"不配置多模型"与升级前的行为完全一致。</p>
     */
    private StreamingChatModel resolveModel(String profile) {
        if (modelResolver == null) {
            return streamingChatModel;
        }
        try {
            StreamingChatModel resolved = modelResolver.streamingChat(profile);
            return resolved == null ? streamingChatModel : resolved;
        } catch (Exception e) {
            // 解析失败不该让整轮对话失败：退回默认模型，并把原因留在日志里
            log.warn("[Agent编排] 会话模型解析失败（域={}），已回落默认模型: {}", profile, e.getMessage());
            return streamingChatModel;
        }
    }

    /**
     * tools 节点:执行 LLM 请求的工具调用,返回 ToolExecutionResultMessage 列表到状态
     */
    private Map<String, Object> toolsNode(MessagesState<ChatMessage> state, RunnableConfig config) {
        String sessionId = sessionIdOf(config);
        StreamContext context = streamSinks.get(sessionId);

        var lastMessage = state.lastMessage()
                .orElseThrow(() -> new IllegalStateException("消息列表为空"));

        if (!(lastMessage instanceof AiMessage aiMessage)) {
            throw new IllegalStateException("最后一条消息不是 AiMessage");
        }

        List<ToolExecutionResultMessage> results = new ArrayList<>();
        // 域取自本轮 StreamContext：resume 时会用它重新校验待执行的工具调用，
        // 这是"执行单元内不换提示词"之下唯一能拦住陈旧上下文放行危险动作的机制。
        String profile = context == null ? null : context.profile();
        for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
            // 停止检查:每个工具执行前检查标志,避免停止后继续执行后续工具
            if (cancellationRegistry.isCancelled(sessionId)) {
                throw new CancellationException("用户主动停止");
            }

            // 兜底校验:正常链路下域外工具根本不会喂给模型,但模型可能凭历史上下文
            // 调用了上一轮可见、本轮已不可见的工具（resume 场景尤其如此）。此处拦住,
            // 避免绕过域过滤真实执行。
            // 复核是 fail-closed 的:工具已不在注册表（提供它的实例下线、条目整体移除）
            // 同样被拦下——否则动态下线对新会话不生效,模型会一直"看得见"已消失的工具。
            if (!toolRouter.isToolVisible(request.name(), profile)) {
                // 用 ErrorCode 的枚举文案，而不是自造一句：文案统一由枚举提供，
                // 改文案时不用满项目找散落的字符串。
                // 两种拒绝原因分开:*域外*是权限问题,*不存在*是能力已消失(工具提供方下线),
                // 对模型是不同信号,后者告知用户比反复重试更有用。
                boolean exists = toolRouter.exists(request.name());
                String denied = exists
                        ? ErrorCode.TOOL_PERMISSION_DENIED.getMessage()
                        + "（" + request.name() + "）"
                        : ErrorCode.TOOL_NOT_FOUND.getMessage()
                        + "（" + request.name() + "，可能已随提供方下线）";
                log.warn("[Agent编排] 会话[{}] traceId={} 拦截{}的工具调用: {}（domain={}）",
                        sessionId, TraceId.current(), exists ? "域外" : "已下线",
                        request.name(), profile);
                if (context != null) {
                    context.emit(AgentEvent.toolCall(sessionId, request.name(), request.arguments()));
                    context.emit(AgentEvent.toolResult(sessionId, request.name(), denied));
                }
                results.add(ToolExecutionResultMessage.from(request, denied));
                continue;
            }

            if (context != null) {
                context.emit(AgentEvent.toolCall(sessionId, request.name(), request.arguments()));
            }

            // 通过 ToolRouter 统一路由执行(内含 Token 用量统计与异常兜底)
            // 远程工具要把租户/用户/traceId 带给工具实例,而 execute(request, memoryId) 的签名里
            // 没有身份位置,故在此绑定线程上下文、执行完立即解除。工具执行是同步阻塞调用,
            // 绑定与解除都在本线程同一次调用内,不会泄漏到同线程的下一轮。
            ToolInvocationContext.bind(context == null ? null : context.getCaller(),
                    context == null ? null : context.traceId());
            String result;
            try {
                result = toolRouter.execute(request);
            } finally {
                ToolInvocationContext.clear();
            }

            if (context != null) {
                context.emit(AgentEvent.toolResult(sessionId, request.name(), result));
            }
            results.add(ToolExecutionResultMessage.from(request, result));
        }

        return Map.of("messages", results);
    }

    /**
     * 条件边:agent 节点执行后,按工具调用类型三路分流
     * @return "exit" 无工具→END; "auto" 全自主工具→tools直连; "review" 含需授权工具→review节点
     */
    private AsyncCommandAction<MessagesState<ChatMessage>> routeAfterAgent() {
        return (state, config) -> {
            var lastMessage = state.lastMessage()
                    .orElseThrow(() -> new IllegalStateException("消息列表为空"));

            String sessionId = sessionIdOf(config);
            StreamContext context = streamSinks.get(sessionId);
            // 与 agentNode 使用同一份域、同一份过滤条件，保证"模型可见工具集"与"路由判断"一致
            Set<String> approvalTools = toolRouter.getToolsRequiringApproval(
                    context == null ? null : context.profile());

            if (lastMessage instanceof AiMessage aiMessage) {
                if (aiMessage.hasToolExecutionRequests()) {
                    boolean needsApproval = aiMessage.toolExecutionRequests().stream()
                            .anyMatch(req -> approvalTools.contains(req.name()));
                    if (needsApproval) {
                        log.info("[Agent编排-条件边] 含需授权工具,路由到 review 中断点");
                        return CompletableFuture.completedFuture(new Command(BRANCH_REVIEW));
                    }
                    log.info("[Agent编排-条件边] 全自主工具,直连 tools");
                    return CompletableFuture.completedFuture(new Command(BRANCH_AUTO));
                }
            }
            return CompletableFuture.completedFuture(new Command(BRANCH_EXIT));
        };
    }

    /**
     * 对外契约入口:发起一轮对话
     */
    @Override
    public Flux<AgentEvent> chat(AgentRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        CallerContext caller = CallerContext.from(request);
        log.info("[Agent编排] 会话[{}] 收到调用请求, profile={}, tenantId={}, userId={}, attributes={}",
                request.getSessionId(), caller == null ? null : caller.profile(),
                request.getTenantId(), request.getUserId(), request.getAttributes().keySet());

        ProfileCheck check = checkProfile(caller);
        if (check != ProfileCheck.OK) {
            log.warn("[Agent编排] 会话[{}] 域校验未通过: {}", request.getSessionId(), check.detail());
            // 本方法跑在 HTTP 线程上，那时还没 begin 过 traceId：用 currentOrNew 保证事件里的
            // traceId 一定非空，否则调用方拿到的是一串 null，上报也无从查起
            return Flux.just(AgentEvent.error(request.getSessionId(), check.detail(), check.code(),
                    TraceId.currentOrNew()));
        }
        return orchestrate(request.getSessionId(), request.getMessage(), caller);
    }

    /**
     * 校验本轮调用方身份与域：缺身份拒绝；<b>域为空回落到兜底域</b>；域不存在报错。
     */
    private ProfileCheck checkProfile(CallerContext caller) {
        if (caller == null) {
            return ProfileCheck.fail(ErrorCode.CALLER_CONTEXT_REQUIRED,
                    "缺少调用方身份（CallerContext 必填：tenantId / userId / profile）");
        }
        // 域为空 → 落到兜底域 default（服务端已预置），不再当作入参错误。
        // 注意：兜底不等于放宽 —— default 之外的域仍须被声明过，否则照旧 10004，
        // 与之配合的是"工具声明留空即只属于 default"的授权语义。
        String profile = Domains.normalize(caller.normalizedProfile());
        if (!toolRouter.acceptsProfile(profile)) {
            return ProfileCheck.fail(ErrorCode.PROFILE_NOT_FOUND,
                    "域不存在: " + profile + "（已注册的域: " + toolRouter.getKnownProfiles() + "）");
        }
        return ProfileCheck.OK;
    }

    /**
     * 身份 / 域校验结果
     *
     * @param code   失败时的错误码；通过时为 {@code null}
     * @param detail 面向调用方的说明（含具体缺什么、当前有哪些域可选）
     */
    private record ProfileCheck(ErrorCode code, String detail) {

        static final ProfileCheck OK = new ProfileCheck(null, null);

        static ProfileCheck fail(ErrorCode code, String detail) {
            return new ProfileCheck(code, detail);
        }
    }

    /**
     * 对外契约入口:请求停止任务
     */
    @Override
    public boolean stop(String sessionId) {
        return cancellationRegistry.requestStop(sessionId);
    }

    /**
     * Agent 对话入口(流式)
     *
     * <p>流程:
     * <ol>
     *   <li>清理上一轮遗留的停止标志与 checkpoint</li>
     *   <li>加载会话记忆,添加 UserMessage</li>
     *   <li>构建初始状态: [SystemMessage] + 历史消息</li>
     *   <li>stream 消费 graph 执行,实时推送事件</li>
     *   <li>检测到中断(review 前):推送 INTERRUPT 事件,挂起等待 resume</li>
     *   <li>正常完成:将最终 AiMessage 存入会话记忆</li>
     * </ol>
     *
     * @param sessionId 会话 ID,同时作为 checkpoint 的 threadId
     * @param message   用户问题
     * @param caller    调用方身份（域 / 租户 / 用户）；域决定本轮可见工具集
     * @return 事件流,挂起时下发 {@code INTERRUPT} 事件,正常结束下发 {@code DONE}
     */
    public Flux<AgentEvent> orchestrate(String sessionId, String message, CallerContext caller) {
        return Flux.create(sink -> {
            StreamContext context = streamSinks.register(sessionId, sink, caller);
            // 流被取消(客户端断连)或终止时,通知执行线程退出并清理上下文。
            // 只有"自己仍是当前上下文"才算客户端断连：同一会话换了一轮（中断后紧接 resume、
            // 客户端重试）时，上一轮的收尾也会跑到这里，若照旧置停止标志，会把刚启动的新一轮一起取消。
            sink.onDispose(() -> {
                if (streamSinks.isCurrent(sessionId, context)) {
                    cancellationRegistry.requestStop(sessionId);
                }
                streamSinks.unregister(sessionId, context);
            });

            try {
                executor.execute(() -> runOrchestrate(sessionId, message, context));
            } catch (RejectedExecutionException e) {
                log.error("[Agent编排] 会话[{}] 提交失败,编排线程池已满", sessionId);
                streamSinks.unregister(sessionId, context);
                sink.next(AgentEvent.error(sessionId, ErrorCode.SYSTEM_BUSY, context.traceId()));
                sink.complete();
            }
        });
    }

    private void runOrchestrate(String sessionId, String message, StreamContext context) {
        // 同会话串行：本会话已有一轮在执行则直接拒绝（最细粒度的并发控制）。
        // 必须放在 clear() 之前——clear 会清掉停止标志，若先 clear，并发请求会把
        // 正在执行那一轮的 stop 标志抹掉，用户点停止将不生效。
        if (!cancellationRegistry.tryMarkRunning(sessionId)) {
            log.warn("[Agent编排] 会话[{}] 正在执行中，拒绝并发请求（如需重开请先 stop 并等其结束）", sessionId);
            context.emit(AgentEvent.error(sessionId, ErrorCode.SESSION_BUSY, TraceId.currentOrNew()));
            context.complete();
            streamSinks.unregister(sessionId, context);
            return;
        }

        // 入口清理:避免上一轮遗留的停止标志导致本轮一启动就被终止
        cancellationRegistry.clear(sessionId);

        // 本轮排障标识：贯穿日志与 ERROR 事件，便于用 traceId 串起一次完整调用
        TraceId.begin(context == null ? null : context.traceId());
        // 提问前的记忆快照（停止时回滚用）；声明在 try 之外，停止分支也要用
        List<ChatMessage> memoryBefore = List.of();
        try {
            // 上一轮若停在审批点，那个断点就是"待授权动作"的唯一载体：清掉它等于让用户点了同意也执行不了，
            // 而用户看到的是"会话不存在"。这里明确拒绝新一轮，让调用方先去处理审批。
            boolean pending;
            try {
                pending = pendingApproval(sessionId);
            } catch (IllegalStateException e) {
                // 读不出来 ≠ 没有待审批。此刻绝不能继续走到入口清理——那恰好会把待审批的断点删掉，
                // 正是本轮要防的那件事。直接报"检查点读写失败"并返回，由调用方退避后重试。
                log.error("[Agent编排] 会话[{}] 读取检查点失败，无法判断是否存在待审批中断点", sessionId, e);
                context.emit(AgentEvent.error(sessionId, ErrorCode.CHECKPOINT_ERROR,
                        TraceId.currentOrNew()));
                context.complete();
                return;
            }
            if (pending) {
                log.warn("[Agent编排] 会话[{}] 存在待审批的中断点，拒绝新一轮对话", sessionId);
                context.emit(AgentEvent.error(sessionId, ErrorCode.SESSION_STATE_INVALID,
                        TraceId.currentOrNew()));
                context.complete();
                return;
            }
            releaseCheckpointQuietly(sessionId, "入口清理");

            TokenUsageRecorder.begin();

            ChatMemory memory = chatMemoryProvider.get(sessionId);
            // 先拍快照再写提问：停止时要把记忆恢复到这一刻。只删"最后一条"是不够的——
            // 若这次写入触发了窗口淘汰，被挤掉的旧消息不会回来，历史从此对不上。
            memoryBefore = List.copyOf(memory.messages());
            memory.add(UserMessage.from(message));

            List<ChatMessage> messages = new ArrayList<>();
            // 系统提示词按本轮所处的域解析：公共基线 + 域差异。
            // 执行单元内冻结、单元之间用最新 —— resume 不重跑本方法（它从检查点恢复），
            // 因此这里读到的就是本次执行单元开始时的提示词，天然满足该语义。
            String systemMessage = promptResolver.resolve(context == null ? null : context.profile());
            if (systemMessage != null && !systemMessage.isBlank()) {
                messages.add(SystemMessage.from(systemMessage));
            }
            messages.addAll(memory.messages());

            RunnableConfig config = RunnableConfig.builder()
                    .threadId(sessionId)
                    .build();

            log.info("[Agent编排] 会话[{}] traceId={} 开始图编排,消息数={}",
                    sessionId, TraceId.current(), messages.size());
            long startedAt = System.currentTimeMillis();

            // stream 消费,中断时迭代自然停止;lastState 用于绕过 releaseThread 后 stateOf 失效的问题
            boolean interrupted = false;
            MessagesState<ChatMessage> lastState = null;

            for (var output : compiledGraph.stream(GraphInput.args(Map.of("messages", messages)), config)) {
                lastState = output.state();
                log.debug("[Agent编排] 会话[{}] 节点完成: {}", sessionId, output.node());

                if ("agent".equals(output.node()) && isInterruptedBeforeReview(config)) {
                    // 记录中断发生时的域：resume 必须沿用同一域，否则旧域提示词会配上新域工具集。
                    // 必须在 break 前落表，否则首轮 resume 的域一致性校验会因未命中而放行。
                    interruptedProfiles.put(sessionId, context.profile());
                    context.emit(AgentEvent.interrupt(sessionId,
                            buildInterruptPayload(lastState, context.profile())));
                    interrupted = true;
                    log.info("[Agent编排] 会话[{}] 中断于 review,等待人工授权,耗时={}ms",
                            sessionId, System.currentTimeMillis() - startedAt);
                    break;
                }
            }

            if (!interrupted) {
                rememberFinalAnswer(memory, lastState);
                // 耗时是排查"为什么慢"的第一线索：LLM 慢还是工具慢，靠它定位
                log.info("[Agent编排] 会话[{}] traceId={} 图编排完成,耗时={}ms",
                        sessionId, TraceId.current(), System.currentTimeMillis() - startedAt);
                context.emit(AgentEvent.done(sessionId));
            }
            context.complete();
        } catch (Exception e) {
            if (isCancellationException(e)) {
                handleStop(sessionId, context, "orchestrate", memoryBefore);
            } else if (!handleNotConfigured(sessionId, context, e)) {
                log.error("[Agent编排] 会话[{}] traceId={} 执行失败", sessionId, TraceId.current(), e);
                // 异常中断时图没走到 END，releaseThread 不会触发，checkpoint 会残留。
                // 残留状态里可能有"悬空的 AiMessage（带工具调用却没有结果）"，
                // 下一轮带着它继续跑会反复失败——必须在这里主动清掉，避免一次异常拖垮后续所有提问。
                releaseCheckpointQuietly(sessionId, "执行异常");
                context.emit(AgentEvent.error(sessionId, ErrorCode.ORCHESTRATION_FAILED,
                        TraceId.currentOrNew()));
                context.complete();
            }
        } finally {
            TokenUsageRecorder.finishAndLog();
            cancellationRegistry.clear(sessionId);
            // 释放"执行中"标记：必须与 tryMarkRunning 成对，否则该会话会被永久锁死
            cancellationRegistry.unmarkRunning(sessionId);
            // 只注销自己这一轮的上下文：期间若已换了新的一轮（客户端重试），不能把新的一起删掉
            streamSinks.unregister(sessionId, context);
            // 线程池会复用线程，必须清理 ThreadLocal，否则下一个任务会继承到本次 traceId
            TraceId.end();
        }
    }

    /**
     * 恢复被中断的会话
     *
     * @param sessionId 会话 ID,需与 orchestrate 时一致
     * @param approved  true=批准执行工具; false=拒绝,注入拒绝反馈让 agent 重新决策
     * @param caller    调用方身份（域 / 租户 / 用户），不得为 {@code null}
     * @return 事件流,resume 后的输出继续推送
     */
    @Override
    public Flux<AgentEvent> resume(String sessionId, boolean approved, CallerContext caller) {
        ProfileCheck check = checkProfile(caller);
        if (check != ProfileCheck.OK) {
            log.warn("[Agent编排] 会话[{}] resume 域校验未通过: {}", sessionId, check.detail());
            return Flux.just(AgentEvent.error(sessionId, check.detail(), check.code(),
                    TraceId.currentOrNew()));
        }
        return Flux.create(sink -> {
            StreamContext context = streamSinks.register(sessionId, sink, caller);
            // 同 orchestrate：只有"自己仍是当前上下文"才算客户端断连，
            // 否则上一轮的收尾会把这轮的停止标志置上
            sink.onDispose(() -> {
                if (streamSinks.isCurrent(sessionId, context)) {
                    cancellationRegistry.requestStop(sessionId);
                }
                streamSinks.unregister(sessionId, context);
            });

            try {
                executor.execute(() -> runResume(sessionId, approved, context));
            } catch (RejectedExecutionException e) {
                log.error("[Agent编排] 会话[{}] resume 提交失败,编排线程池已满", sessionId);
                streamSinks.unregister(sessionId, context);
                sink.next(AgentEvent.error(sessionId, ErrorCode.SYSTEM_BUSY, context.traceId()));
                sink.complete();
            }
        });
    }

    private void runResume(String sessionId, boolean approved, StreamContext context) {
        // 同会话串行同样适用于 resume：本会话已有一轮在执行时拒绝，理由与 chat 一致。
        // 同样放在 clear() 之前，避免把正在执行那轮的 stop 标志抹掉。
        if (!cancellationRegistry.tryMarkRunning(sessionId)) {
            log.warn("[Agent编排-resume] 会话[{}] 正在执行中，拒绝并发 resume", sessionId);
            context.emit(AgentEvent.error(sessionId, ErrorCode.SESSION_BUSY, TraceId.currentOrNew()));
            context.complete();
            streamSinks.unregister(sessionId, context);
            return;
        }
        cancellationRegistry.clear(sessionId);
        TraceId.begin(context == null ? null : context.traceId());

               try {
            TokenUsageRecorder.begin();

            RunnableConfig config = RunnableConfig.builder()
                    .threadId(sessionId)
                    .build();

            var snapshot = compiledGraph.stateOf(config);
            if (snapshot.isEmpty()) {
                // 会话无检查点：可能是已结束/已过期/从未创建。这是明确的业务状态，
                // 用 SESSION_NOT_FOUND 而不是笼统的执行失败，前端才能正确提示"请新建会话"
                log.warn("[Agent编排] 会话[{}] 无检查点,无法 resume", sessionId);
                context.emit(AgentEvent.error(sessionId, ErrorCode.SESSION_NOT_FOUND,
                        TraceId.currentOrNew()));
                context.complete();
                return;
            }

            log.info("[Agent编排] 会话[{}] resume,approved={}, 当前节点={}, next={}",
                    sessionId, approved, snapshot.get().node(), snapshot.get().next());

            if (!BRANCH_REVIEW.equals(snapshot.get().next())) {
                // resume 的语义是"授权某一次待执行的工具调用"。断点不停在审批点时（例如上一轮异常
                // 残留的断点），这个 approved 会被套到不该套的状态上，等于让图从任意位置继续。
                log.warn("[Agent编排] 会话[{}] 的断点不停在审批点(当前 next={})，拒绝 resume",
                        sessionId, snapshot.get().next());
                context.emit(AgentEvent.error(sessionId, ErrorCode.SESSION_STATE_INVALID,
                        TraceId.currentOrNew()));
                context.complete();
                return;
            }

            String interruptedProfile = interruptedProfiles.get(sessionId);
            if (interruptedProfile != null && !interruptedProfile.equals(context.profile())) {
                // 提示词随断点一起冻结（SystemMessage 已进图状态），而工具可见性按本次请求的域实时过滤。
                // 允许换域就等于让模型看到"旧域的提示词 + 新域的工具集"，因此这里拒绝。
                log.warn("[Agent编排] 会话[{}] resume 携带的域({})与中断时的域({})不一致，拒绝",
                        sessionId, context.profile(), interruptedProfile);
                context.emit(AgentEvent.error(sessionId, ErrorCode.SESSION_STATE_INVALID,
                        TraceId.currentOrNew()));
                context.complete();
                return;
            }

            if (!approved) {
                // 拒绝:只为这批待执行的工具调用追加"被拒绝"的结果,由模型据此重新生成不带工具调用的回复。
                List<ChatMessage> rejections = new ArrayList<>();
                var history = snapshot.get().state().messages();
                for (int i = history.size() - 1; i >= 0; i--) {
                    if (history.get(i) instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                        for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                            rejections.add(ToolExecutionResultMessage.from(req,
                                    "用户拒绝了此工具调用,请直接回复或换一种方式回答"));
                        }
                        break;
                    }
                }
                // asNode 必须是 "tools"，不是 "review"：
                // 它表示"假装 tools 节点已产出本次更新"，图随即沿 tools → agent 这条出边进入
                // agent，由模型基于"用户已拒绝"的结果重新生成回复。
                // 若写成 "review"：review 的出边是 tools，会直接进 toolsNode，而此刻最后一条
                // 消息是 ToolExecutionResultMessage（非 AiMessage），必撞 toolsNode 的
                // "最后一条消息不是 AiMessage" 校验，整轮被兜底成 ORCHESTRATION_FAILED。
                compiledGraph.updateState(config, Map.of("messages", rejections), "tools");
            }

            boolean interrupted = false;
            MessagesState<ChatMessage> lastState = null;

            for (var output : compiledGraph.stream(GraphInput.resume(), config)) {
                lastState = output.state();
                log.debug("[Agent编排-resume] 会话[{}] 节点完成: {}", sessionId, output.node());

                if ("agent".equals(output.node()) && isInterruptedBeforeReview(config)) {
                    // 同一轮内可能多次中断，每次都记一遍域：下一段 resume 必须与这一次一致
                    interruptedProfiles.put(sessionId, context.profile());
                    context.emit(AgentEvent.interrupt(sessionId,
                            buildInterruptPayload(lastState, context.profile())));
                    interrupted = true;
                    log.info("[Agent编排-resume] 会话[{}] 再次中断于 review", sessionId);
                    break;
                }
            }

            if (!interrupted) {
                rememberFinalAnswer(chatMemoryProvider.get(sessionId), lastState);
                // 走到 END 后 releaseThread 会释放断点，中断域的记录也随之作废
                interruptedProfiles.remove(sessionId);
                log.info("[Agent编排-resume] 会话[{}] resume 完成", sessionId);
                context.emit(AgentEvent.done(sessionId));
            }
            context.complete();
        } catch (Exception e) {
            if (isCancellationException(e)) {
                // resume 不写记忆，没有需要回滚的提问
                handleStop(sessionId, context, "resume", null);
            } else if (!handleNotConfigured(sessionId, context, e)) {
                log.error("[Agent编排-resume] 会话[{}] traceId={} 执行失败",
                        sessionId, TraceId.current(), e);
                // 同 orchestrate：异常中断不会触发 releaseThread，需主动清残留 checkpoint
                releaseCheckpointQuietly(sessionId, "resume 异常");
                context.emit(AgentEvent.error(sessionId, ErrorCode.ORCHESTRATION_FAILED,
                        TraceId.currentOrNew()));
                context.complete();
            }
        } finally {
            TokenUsageRecorder.finishAndLog();
            cancellationRegistry.clear(sessionId);
            // 释放"执行中"标记：必须与 tryMarkRunning 成对，否则该会话会被永久锁死
            cancellationRegistry.unmarkRunning(sessionId);
            // 只注销自己这一轮的上下文（同一会话可能已有新一轮注册进来）
            streamSinks.unregister(sessionId, context);
            TraceId.end();
        }
    }

    /**
     * 把本轮最终回复写入会话记忆
     */
    private void rememberFinalAnswer(ChatMemory memory, MessagesState<ChatMessage> lastState) {
        if (memory == null || lastState == null) {
            log.warn("[Agent编排] 无可用状态,跳过记忆写入");
            return;
        }
        var finalMessages = lastState.messages();
        for (int i = finalMessages.size() - 1; i >= 0; i--) {
            if (finalMessages.get(i) instanceof AiMessage ai && !ai.hasToolExecutionRequests()) {
                memory.add(ai);
                return;
            }
        }
        log.warn("[Agent编排] 未找到可写入记忆的最终回复");
    }

    /**
     * 会话是否停在审批点（存在待授权的中断点）。
     */
    private boolean pendingApproval(String sessionId) {
        RunnableConfig config = RunnableConfig.builder().threadId(sessionId).build();
        try {
            return compiledGraph.stateOf(config)
                    .map(snapshot -> BRANCH_REVIEW.equals(snapshot.next()))
                    .orElse(false);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "读取会话检查点失败，无法判断是否存在待审批中断点: " + e.getMessage(), e);
        }
    }

    /**
     * 判断图是否已中断在 review 节点之前
     */
    private boolean isInterruptedBeforeReview(RunnableConfig config) {
        try {
            var snapshot = compiledGraph.stateOf(config).orElse(null);
            return snapshot != null && BRANCH_REVIEW.equals(snapshot.next());
        } catch (Exception e) {
            // 读不出来 ≠ 没中断。两者混为一谈会让"本该停下等审批"的一轮以"完成"收尾：
            // 中间态被写进记忆、DONE 照发，而审批入口在界面上永远不出现。
            // 读不到就明确失败，让调用方看到错误，而不是拿到一个错的"完成"。
            throw new IllegalStateException(
                    "读取图中断状态失败，无法判断是否停在审批点: " + e.getMessage(), e);
        }
    }

    /**
     * 构建中断事件 payload:待授权工具调用列表(JSON)
     */
    private String buildInterruptPayload(MessagesState<ChatMessage> state, String profile) {
        Set<String> approvalTools = toolRouter.getToolsRequiringApproval(profile);
        List<ToolCall> calls = new ArrayList<>();
        if (state != null) {
            var messages = state.messages();
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i) instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                    for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                        calls.add(ToolCall.of(req.name(), req.arguments(),
                                approvalTools.contains(req.name())));
                    }
                    break;
                }
            }
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(ToolCallPayload.of(calls));
        } catch (Exception e) {
            log.error("[Agent编排] 中断 payload 序列化失败,降级为空列表", e);
            return "{\"tools\":[]}";
        }
    }

    /** 异常描述,避免把 null 或空串下发给调用方 */
    private String describe(Throwable e) {
        String message = e.getMessage();
        return (message == null || message.isBlank()) ? e.getClass().getSimpleName() : message;
    }

    /**
     * 判断异常链中是否包含 CancellationException
     */
    private boolean isCancellationException(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof CancellationException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * "依赖未配置"的专用收尾 —— 把它从通用执行失败里择出来。
     *
     * @return true 表示已按"未配置"处理并结束流，调用方不要再走通用兜底
     */
    private boolean handleNotConfigured(String sessionId, StreamContext context, Throwable e) {
        NotConfiguredException notConfigured = findNotConfigured(e);
        if (notConfigured == null) {
            return false;
        }
        log.warn("[Agent编排] 会话[{}] traceId={} 依赖未配置，按 {} 回报：{}",
                sessionId, TraceId.current(),
                notConfigured.getCodeName(), notConfigured.getMessage());
        releaseCheckpointQuietly(sessionId, "依赖未配置");
        // 用 4 参重载，把"去管控台哪一页填"的具体指引带进事件体（枚举默认文案只有一句概括）
        context.emit(AgentEvent.error(sessionId, notConfigured.getMessage(),
                notConfigured.getErrorCode(), TraceId.currentOrNew()));
        context.complete();
        return true;
    }

    /**
     * 在异常链上查找 {@link NotConfiguredException}。
     */
    private static NotConfiguredException findNotConfigured(Throwable e) {
        Throwable cur = e;
        int guard = 0;
        while (cur != null && guard++ < 10) {
            if (cur instanceof NotConfiguredException nce) {
                return nce;
            }
            Throwable cause = cur.getCause();
            if (cause == cur) {
                break;
            }
            cur = cause;
        }
        return null;
    }

    /**
     * 处理用户主动停止:回滚记忆、清 checkpoint、推送停止事件
     *
     * @param memoryBefore 本轮提问前的记忆快照；{@code null} = 本次不涉及记忆回滚
     */
    private void handleStop(String sessionId, StreamContext context, String source,
                            List<ChatMessage> memoryBefore) {
        log.info("[Agent编排] 会话[{}] 任务被用户停止,source={}, 半截文本长度={}",
                sessionId, source, context == null ? 0 : context.partialOutput().length());

        // 1. 回滚记忆(仅 orchestrate 场景,resume 未写记忆)
        if ("orchestrate".equals(source)) {
            restoreMemory(sessionId, memoryBefore);
        }

        // 2. 清 checkpoint(不可恢复)
        releaseCheckpointQuietly(sessionId, "用户停止");

        // 3. 推送停止事件给调用方
        if (context != null) {
            context.emit(AgentEvent.stopped(sessionId));
            context.complete();
        }
    }

    /**
     * 把会话记忆恢复到"本轮提问之前"的那一刻。
     *
     * @param memoryBefore 提问前的快照；{@code null} 表示异常发生在拍快照之前
     */
    private void restoreMemory(String sessionId, List<ChatMessage> memoryBefore) {
        try {
            ChatMemory memory = chatMemoryProvider.get(sessionId);
            if (!(memory instanceof DualConstraintChatMemory dual)) {
                log.warn("[Agent编排] 会话[{}] memory 非 DualConstraintChatMemory,无法回滚记忆", sessionId);
                return;
            }
            if (memoryBefore == null) {
                // 没有快照：退回"删最后一条"，至少不把本轮提问留在记忆里
                dual.removeLastMessage();
                return;
            }
            dual.restore(memoryBefore);
            log.info("[Agent编排] 会话[{}] 记忆已回滚到本轮提问前（{} 条）", sessionId, memoryBefore.size());
        } catch (Exception ex) {
            log.error("[Agent编排] 会话[{}] 回滚记忆失败", sessionId, ex);
        }
    }

    /** 静默清理 checkpoint:失败只记日志,不阻断主流程 */
    private void releaseCheckpointQuietly(String sessionId, String reason) {
        try {
            RunnableConfig config = RunnableConfig.builder().threadId(sessionId).build();
            checkpointSaver.release(config);
            log.debug("[Agent编排] 会话[{}] checkpoint 已清除({})", sessionId, reason);
        } catch (Exception ex) {
            log.warn("[Agent编排] 会话[{}] 清 checkpoint 失败({}): {}", sessionId, reason, ex.getMessage());
        }
        // 断点没了，"中断时的域"这条记录也就没有意义了；留着会让下一次 resume 拿它做无谓的域比对
        interruptedProfiles.remove(sessionId);
    }

    /** 当前注册的流上下文数量(观测用) */
    public int activeStreamCount() {
        return streamSinks.size();
    }
}
