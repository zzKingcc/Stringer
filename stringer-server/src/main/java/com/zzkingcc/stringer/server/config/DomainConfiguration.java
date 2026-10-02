package com.zzkingcc.stringer.server.config;

import com.zzkingcc.stringer.api.agent.Domains;
import com.zzkingcc.stringer.runtime.domain.DomainRegistry;
import com.zzkingcc.stringer.server.settings.DomainStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 域装配：把域注册表装进内核，并在启动期恢复管控台声明的域与它们的可调用性。
 *
 * @author zzkingcc
 */
@Configuration
public class DomainConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DomainConfiguration.class);

    /**
     * 域注册表：根域 default 由构造期预置（恒为可调用单元），管控台声明的域从落盘恢复。
     *
     * <p>恢复时<b>只按条目显式给定的可调用性</b>，沿链补齐出来的祖先一律是装配节点。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public DomainRegistry domainRegistry(DomainStore domainStore) {
        DomainRegistry registry = new DomainRegistry();
        DomainStore.StoredDomains stored = domainStore.load();
        registry.loadManual(stored.callables());
        log.info("[域注册表] 初始化完成：已登记 {} 个域 {}，其中可调用 {} 个 {}；"
                        + "另有工具声明派生的域由注册表侧维护",
                registry.ids().size(), registry.ids(),
                registry.callableIds().size(), registry.callableIds());
        return registry;
    }

    /**
     * 一次性迁移：把"当前已经有子域"的域改成<b>装配节点</b>（不可直接调用），根域豁免。
     *
     * <p>为什么需要它：可调用性曾经是"登记过就算"，于是父域也能当入口用。改成显式声明之后，
     * 存量部署的父域会把"能调"变成"不能调"；这里在升级后的首次启动做一次快照，
     * 让行为与升级前一致（该能调的照旧能调），同时把父域收成装配节点。</p>
     *
     * <p>迁移结果<b>落盘</b>（含派生域）：否则重启后派生域会按"被声明的域可调用"重新变回可调用。</p>
     *
     * <p>只跑一次（落盘的 {@code callableMigrated} 为真即跳过）；结果可在管控台「域空间」逐个改回。</p>
     */
    @Bean
    public SmartInitializingSingleton domainCallableMigration(DomainRegistry domainRegistry,
                                                             DomainStore domainStore) {
        return () -> {
            if (domainStore.load().callableMigrated()) {
                return;
            }
            List<String> changed = new ArrayList<>();
            for (DomainRegistry.Domain domain : domainRegistry.all()) {
                if (Domains.DEFAULT.equals(domain.id()) || !domain.callable()) {
                    continue;
                }
                if (domainRegistry.hasChildren(domain.id())) {
                    domainRegistry.setCallable(domain.id(), false);
                    changed.add(domain.id());
                }
            }
            domainStore.save(domainRegistry.manualConfig(), true);
            if (changed.isEmpty()) {
                log.info("[域] 可调用性迁移完成：没有需要改为装配节点的域（可调用集合 {} 个）",
                        domainRegistry.callableIds().size());
            } else {
                log.warn("[域] 可调用性迁移：已把 {} 个「有子域」的域改为装配节点（不可直接调用，根域豁免）：{}；"
                                + "如需其中一个照旧能被调用，请在管控台「域空间」把它切回可调用",
                        changed.size(), changed);
            }
        };
    }
}
