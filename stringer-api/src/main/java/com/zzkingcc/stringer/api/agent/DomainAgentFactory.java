package com.zzkingcc.stringer.api.agent;

/**
 * 域门面工厂 —— 取得绑定到指定域的 Agent。
 *
 * <p>这是消费侧唯一的域入口：先绑定，再调用。与 {@link AgentService} 并存，
 * 后者保留给旧调用方（未声明域的路径）作为兜底。</p>
 *
 * @author zzkingcc
 */
public interface DomainAgentFactory {

    /**
     * 取得绑定到指定域的门面。
     *
     * @param domainId 域标识；{@code null} / 空白 → 兜底域 {@link Domains#DEFAULT}
     * @return 绑定该域的门面，可重复使用（线程安全）
     */
    DomainAgent forDomain(String domainId);
}
