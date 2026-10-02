package com.zzkingcc.stringer.runtime.tool;

import com.zzkingcc.stringer.api.code.ErrorCode;
import com.zzkingcc.stringer.api.tool.ToolDescriptor;
import com.zzkingcc.stringer.runtime.domain.DomainRegistry;
import com.zzkingcc.stringer.runtime.metrics.MetricsRegistry;
import com.zzkingcc.stringer.runtime.usage.TokenUsageRecorder;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 工具路由器 —— Agent 调用能力的唯一入口
 * @author zzkingcc
 */
public class ToolRouter {

    private static final Logger log = LoggerFactory.getLogger(ToolRouter.class);

    private final ToolRegistry registry;
    /** 域注册表：内置与人工创建的域在这里；工具声明派生的域在 registry 里 */
    private final DomainRegistry domainRegistry;

    /** 只接工具注册表时，域注册表用缺省实例（仅含根域）—— 供单元测试与最小装配使用 */
    public ToolRouter(ToolRegistry registry) {
        this(registry, new DomainRegistry());
    }

    public ToolRouter(ToolRegistry registry, DomainRegistry domainRegistry) {
        this.registry = registry;
        this.domainRegistry = domainRegistry == null ? new DomainRegistry() : domainRegistry;
        log.info("[工具路由] 初始化完成，注册表内共 {} 个工具，其中 {} 个需人工授权: {}",
                registry.size(), registry.toolsRequiringApproval().size(),
                registry.toolsRequiringApproval());
        if (registry.isEmpty()) {
            log.warn("[工具路由] 当前没有任何工具被注册：请确认工具方法上标注了 @Tool，且所在 Bean 已被 Spring 扫描到");
        }
    }

    /**
     * 获取所有工具规格（全量；无权限校验场景使用）
     */
    public List<ToolSpecification> getToolSpecifications() {
        return registry.toolSpecifications();
    }

    /**
     * 按本轮域过滤后的工具规格
     *
     * @param profile 本轮所处的域
     */
    public List<ToolSpecification> getToolSpecifications(String profile) {
        return registry.toolSpecifications(profile);
    }

    /**
     * 获取需授权工具名集合（全量；管理页 / 无域场景使用）
     */
    public Set<String> getToolsRequiringApproval() {
        return registry.toolsRequiringApproval();
    }

    /**
     * 按本轮域过滤后的"需授权工具名"集合（供条件路由决定是否走 review 中断）。
     */
    public Set<String> getToolsRequiringApproval(String profile) {
        return registry.toolsRequiringApproval(profile);
    }

    /**
     * 域<b>全集</b> —— 注册表登记的域 ∪ 工具声明派生过的域（含"被声明过、此刻已无工具"的域）。
     *
     * <p>口径是"出现过"，因此它<b>只适合展示与排障</b>（管控台域空间、提示词页、启动自检）；
     * 判断"能不能被调用"必须用 {@link #isCallableDomain(String)}。</p>
     */
    public Set<String> getKnownProfiles() {
        Set<String> all = new LinkedHashSet<>(domainRegistry.ids());
        all.addAll(registry.knownProfiles());
        return Set.copyOf(all);
    }

    /**
     * 域<b>可调用集</b> —— 入口唯一认可的域集合（显式标记为可调用单元的域）。
     *
     * <p>不含"仅被工具声明过"的域，也不含装配节点：父域可以只做装配，由叶子当入口。</p>
     */
    public Set<String> getCallableProfiles() {
        return domainRegistry.callableIds();
    }

    /**
     * 该域是否是<b>可调用单元</b>（入口判据）。
     *
     * <p>只认注册表里显式声明的可调用性；根域恒为可调用。</p>
     */
    public boolean isCallableDomain(String profile) {
        return domainRegistry.isCallable(profile);
    }

    /**
     * 该域是否已知（任一处存在即为真）。
     */
    public boolean hasProfile(String profile) {
        return domainRegistry.contains(profile) || registry.hasProfile(profile);
    }

    /**
     * 域是否<b>已被登记或声明过</b>（域全集判据）。
     *
     * <p>入口<b>不用它</b>：入口用 {@link #isCallableDomain(String)}。这里保留是因为
     * "曾经被工具声明过"对展示、排障、以及"域不该随工具断开而消失"的历史语义仍然有意义。</p>
     */
    public boolean acceptsProfile(String profile) {
        return domainRegistry.contains(profile) || registry.acceptsProfile(profile);
    }

    /**
     * 判断某工具对指定域是否可见（工具执行前的兜底校验）。
     */
    public boolean isToolVisible(String toolName, String profile) {
        return registry.find(toolName)
                .map(r -> r.descriptor().visibleIn(profile))
                .orElse(false);
    }

    /**
     * 工具当前是否存在于注册表（用于区分"域外"与"已下线/不存在"两种拒绝原因）。
     */
    public boolean exists(String toolName) {
        return registry.find(toolName).isPresent();
    }

    /**
     * 获取全部工具描述符（供管理页 / 审计使用；不含任何 Class 引用）
     */
    public List<ToolDescriptor> getToolDescriptors() {
        return registry.descriptors();
    }

    /**
     * 某工具的敏感参数名（供工具调用事件与审批 payload 做值掩码）。
     */
    public Set<String> sensitiveParams(String toolName) {
        return registry.sensitiveParams(toolName);
    }

    /**
     * 执行工具调用（统一入口）
     *
     * @param request LLM 生成的工具调用请求
     * @return 工具执行结果；工具不存在或执行失败时返回错误描述文本
     */
    public String execute(ToolExecutionRequest request) {
        Optional<ToolRegistry.Registered> found = registry.find(request.name());
        if (found.isEmpty()) {
            // 文案取自 ErrorCode,与 toolsNode 复核层拒绝时的文案同源——同一个事实只该有一种说法
            String message = ErrorCode.TOOL_NOT_FOUND.getMessage() + ": " + request.name();
            log.warn("[工具路由] {}", message);
            MetricsRegistry.toolCall(false);
            return message;
        }

        ToolRegistry.Registered registered = found.get();
        ToolExecutor executor = registered.executor();
        String arguments = request.arguments() == null ? "" : request.arguments();

        log.info("[工具路由] 执行工具: {}（sideEffect={}, 需授权={}）",
                request.name(), registered.descriptor().sideEffect(),
                registered.descriptor().requiresApproval());

        String result;
        boolean success = true;
        try {
            // memoryId 恒为 null：当前工具均为无状态能力，不依赖会话记忆
            result = executor.execute(request, null);
            if (result == null) {
                result = "";
            }
        } catch (Exception e) {
            success = false;
            log.error("[工具路由] 工具[{}] 执行失败: {}", request.name(), e.getMessage(), e);
            result = "工具 " + request.name() + " 执行失败: " + e.getMessage();
        }
        // 成功失败都计：失败率是运维最需要的那个数。"工具不存在"也算失败——它对模型同样是失败信号
        MetricsRegistry.toolCall(success);

        // 统一记录 Token 用量（唯一统计点）
        TokenUsageRecorder.recordToolCall(request.name(), arguments, result);
        return result;
    }
}
