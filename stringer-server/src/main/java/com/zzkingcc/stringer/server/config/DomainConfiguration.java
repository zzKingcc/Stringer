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
        if (stored.status() == DomainStore.StoredDomains.Status.CORRUPT) {
            log.error("[域注册表] 配置文件损坏且无法解析（{}），已用空声明初始化注册表："
                            + "人工创建的域本轮不可用，但服务照常启动，且不会把这份空配置写回磁盘",
                    domainStore.filePath());
        }
        log.info("[域注册表] 初始化完成：已登记 {} 个域 {}，其中可调用 {} 个 {}；"
                        + "另有工具声明派生的域由注册表侧维护",
                registry.ids().size(), registry.ids(),
                registry.callableIds().size(), registry.callableIds());
        return registry;
    }

    /**
     * 一次性迁移：把"当前已经有子域"的域改成<b>装配节点</b>（不可直接调用），根域豁免。
     *
     * <p>为什么需要它：可调用性是<b>显式声明</b>的，存量配置里"有子域却仍被登记为可调用"的父域
     * 若直接按显式语义生效，会让该能调的入口变成"不能调"。这里在首次启动做一次快照，
     * 让该能调的照旧能调，同时把父域收成装配节点。</p>
     *
     * <p>迁移结果<b>落盘</b>（含派生域）：否则重启后派生域会按"被声明的域可调用"重新变回可调用。</p>
     *
     * <p>只跑一次（落盘的 {@code callableMigrated} 为真即跳过）；结果可在管控台「域空间」逐个改回。</p>
     */
    @Bean
    public SmartInitializingSingleton domainCallableMigration(DomainRegistry domainRegistry,
                                                             DomainStore domainStore) {
        return () -> {
            // 自检类的 bean 一律不该把启动带崩：这里是唯一没有 try/catch 的一个，
            // 而配置目录在容器里常常是只读挂载 —— 迁移失败应当"下次启动再试"，而不是进程起不来。
            try {
                runMigration(domainRegistry, domainStore);
            } catch (Exception e) {
                log.error("[域] 可调用性迁移失败（不影响启动，下次启动会重试）：{}", e.getMessage(), e);
            }
        };
    }

    private void runMigration(DomainRegistry domainRegistry, DomainStore domainStore) {
        DomainStore.StoredDomains stored = domainStore.load();
        if (stored.status() == DomainStore.StoredDomains.Status.CORRUPT) {
            // 损坏时**绝不迁移、绝不写盘**：此刻注册表里只有根域与工具派生域，
            // 人工建的域一个都没有。此时写回磁盘就是把它们全部抹掉，且迁移标记一旦置 true
            // 就再也不会重来 —— 一次磁盘故障升级成永久的数据丢失。
            // 让服务带着"只有派生域"的状态启动，至少原始文件还在，运维修好后重启即可恢复。
            log.error("[域] 配置文件已损坏，跳过可调用性迁移并拒绝写盘。"
                    + "请修复或恢复 {}；在此之前人工建的域不会生效（服务仍可正常启动）", domainStore.filePath());
            return;
        }
        if (stored.callableMigrated()) {
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
    }
}
