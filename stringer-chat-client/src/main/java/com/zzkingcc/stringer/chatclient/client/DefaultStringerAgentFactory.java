package com.zzkingcc.stringer.chatclient.client;

import com.zzkingcc.stringer.api.agent.AgentService;
import com.zzkingcc.stringer.api.agent.Domains;
import com.zzkingcc.stringer.api.agent.StringerAgent;
import com.zzkingcc.stringer.api.agent.StringerAgentFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link StringerAgentFactory} 的默认实现：按域缓存门面，同一个域永远拿到同一个实例。
 *
 * <p>缓存键是<b>归一化后</b>的域名 —— {@code null} / 空白 / {@code " default "} 都落到同一个
 * 门面，不会因为写法不同而造出多个等价门面。门面本身无状态，缓存它只是为了省去重复 new。</p>
 *
 * @author zzkingcc
 */
public class DefaultStringerAgentFactory implements StringerAgentFactory {

    private final AgentService transport;
    private final Map<String, StringerAgent> agents = new ConcurrentHashMap<>();

    public DefaultStringerAgentFactory(AgentService transport) {
        this.transport = Objects.requireNonNull(transport, "transport 不能为空");
    }

    @Override
    public StringerAgent forDomain(String domainId) {
        String normalized = Domains.normalize(domainId);
        return agents.computeIfAbsent(normalized, domain -> new DefaultStringerAgent(transport, domain));
    }
}
