package com.zzkingcc.stringer.server.config;

import com.zzkingcc.stringer.runtime.model.ModelResolver;
import com.zzkingcc.stringer.server.model.DefaultModelResolver;
import com.zzkingcc.stringer.server.model.ModelClientFactory;
import com.zzkingcc.stringer.server.model.ModelProfileRegistry;
import com.zzkingcc.stringer.server.model.ModelProfileStore;
import com.zzkingcc.stringer.server.settings.LlmModelHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模型档案装配：档案注册表 → 客户端工厂 → 解析器。
 *
 * <p>解析器以 {@link ModelResolver} 契约暴露给内核（{@code stringer-runtime}），
 * 内核不感知档案、绑定这些概念。</p>
 *
 * @author zzkingcc
 */
@Configuration
public class ModelProfileConfiguration {

    /**
     * 档案注册表：档案与「域 → 档案」绑定的唯一真相。
     */
    @Bean
    @ConditionalOnMissingBean
    public ModelProfileRegistry modelProfileRegistry(ModelProfileStore store) {
        return new ModelProfileRegistry(store);
    }

    /**
     * 客户端工厂：按档案指纹构建并缓存模型客户端。
     */
    @Bean
    @ConditionalOnMissingBean
    public ModelClientFactory modelClientFactory() {
        return new ModelClientFactory();
    }

    /**
     * 模型解析器：内置 {@code default} 走 {@link LlmModelHolder}（保持热替换），自建档案走工厂。
     */
    @Bean
    @ConditionalOnMissingBean(ModelResolver.class)
    public ModelResolver modelResolver(ModelProfileRegistry registry,
                                       ModelClientFactory factory,
                                       LlmModelHolder holder) {
        return new DefaultModelResolver(registry, factory, holder);
    }
}
