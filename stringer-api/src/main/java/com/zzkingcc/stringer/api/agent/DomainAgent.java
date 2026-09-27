package com.zzkingcc.stringer.api.agent;

import com.zzkingcc.stringer.api.event.AgentEvent;
import reactor.core.publisher.Flux;

/**
 * 已绑定域的 Agent 门面 —— 每一个实例都固定属于某个域。
 *
 * <p>与 {@link AgentService} 的区别：这里没有"传不传域"的余地。
 * 域在取得实例时即已确定（{@link DomainAgentFactory#forDomain(String)}），
 * 因此调用方<b>写不出</b>"忘记指定域"的代码 —— 这是编译期的强制，而非运行期的校验。</p>
 *
 * @author zzkingcc
 */
public interface DomainAgent {

    /**
     * 本门面绑定的域标识，永不为空（未指定时是 {@link Domains#DEFAULT}）。
     */
    String domainId();

    /**
     * 发起一轮对话。
     *
     * @param sessionId 会话 ID，同时作为记忆与检查点 key
     * @param message   用户输入
     */
    Flux<AgentEvent> chat(String sessionId, String message);

    /**
     * 发起一轮对话，并声明调用方归属（用于多租户计量与审计）。
     */
    Flux<AgentEvent> chat(String sessionId, String message, String tenantId, String userId);

    /**
     * 恢复被审批挂起的会话。
     *
     * @param approved true=批准执行工具；false=拒绝，由模型重新决策
     */
    Flux<AgentEvent> resume(String sessionId, boolean approved);

    /**
     * 恢复被审批挂起的会话，并声明调用方归属。
     */
    Flux<AgentEvent> resume(String sessionId, boolean approved, String tenantId, String userId);

    /**
     * 请求停止会话任务。
     */
    boolean stop(String sessionId);
}
