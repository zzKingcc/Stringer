package com.zzkingcc.stringer.kbclient.autoconfigure;

import com.zzkingcc.stringer.clientcore.autoconfigure.StringerClientAutoConfiguration;
import com.zzkingcc.stringer.clientcore.http.ClientCredential;
import com.zzkingcc.stringer.clientcore.properties.ClientProperties;
import com.zzkingcc.stringer.kbclient.KnowledgeBaseClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 知识库 SDK 自动装配。
 *
 * <p>只多一个 Bean：{@code stringerKnowledgeBaseClient}。WebClient / 凭证 / 启动探测
 * 由 {@link StringerClientAutoConfiguration} 提供 —— 引不引对话 SDK 都一样，因此
 * 单独引本模块也能开箱即用。</p>
 *
 * @author zzkingcc
 */
@AutoConfiguration(after = StringerClientAutoConfiguration.class)
public class KnowledgeBaseAutoConfiguration {

    /**
     * 知识库客户端。
     */
    @Bean
    @ConditionalOnMissingBean
    public KnowledgeBaseClient stringerKnowledgeBaseClient(WebClient stringerWebClient,
                                                           ClientProperties properties,
                                                           ClientCredential stringerClientCredential) {
        return new KnowledgeBaseClient(stringerWebClient, properties, stringerClientCredential);
    }
}
