package com.zzkingcc.stringer.server.config;

import com.zzkingcc.stringer.runtime.domain.DomainRegistry;
import com.zzkingcc.stringer.server.settings.DomainStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * 域装配：把域注册表装进内核，并在启动期恢复人工创建的域。
 *
 * @author zzkingcc
 */
@Configuration
public class DomainConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DomainConfiguration.class);

    /**
     * 域注册表：根域 default 由构造期预置，人工创建的域从落盘恢复（沿链补齐）。
     */
    @Bean
    @ConditionalOnMissingBean
    public DomainRegistry domainRegistry(DomainStore domainStore) {
        DomainRegistry registry = new DomainRegistry();
        Set<String> manual = domainStore.loadManualDomains();
        registry.loadManual(manual);
        log.info("[域注册表] 初始化完成：已登记 {} 个域 {}；另有工具声明派生的域由注册表侧维护",
                registry.ids().size(), registry.ids());
        return registry;
    }
}
