package com.zzkingcc.stringer.server.config;

import com.zzkingcc.stringer.api.agent.AgentService;
import com.zzkingcc.stringer.runtime.cancellation.CancellationRegistry;
import com.zzkingcc.stringer.runtime.model.ModelResolver;
import com.zzkingcc.stringer.runtime.orchestration.AgentOrchestrationService;
import com.zzkingcc.stringer.runtime.prompt.SystemPromptResolver;
import com.zzkingcc.stringer.runtime.tool.ToolRouter;
import com.zzkingcc.stringer.infrastructure.redis.checkpoint.RedisCheckpointSaver;
import com.zzkingcc.stringer.server.prompt.DomainSystemPromptResolver;
import com.zzkingcc.stringer.server.settings.DomainSettingsStore;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 编排配置
 * @author zzkingcc
 */
@Configuration
@EnableConfigurationProperties({PromptProperties.class, AgentProperties.class})
public class GraphConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GraphConfiguration.class);

    /**
     * 图编排专用线程池
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService agentExecutor(AgentProperties agentProperties) {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicLong seq = new AtomicLong();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "stringer-agent-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                agentProperties.getCorePoolSize(),
                agentProperties.getMaxPoolSize(),
                agentProperties.getKeepAliveSeconds(),
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(agentProperties.getQueueCapacity()),
                factory);

        log.info("[Agent编排配置] 编排线程池 core={}, max={}, queue={}",
                agentProperties.getCorePoolSize(), agentProperties.getMaxPoolSize(),
                agentProperties.getQueueCapacity());
        return executor;
    }

    /**
     * 对外只暴露 {@link AgentService} 契约,接入方不感知具体编排实现。
     */
    @Bean
    @Lazy
    public AgentService agentService(
            @Qualifier("openAiStreamingChatModel") StreamingChatModel streamingChatModel,
            @Qualifier("chatMemoryProvider") ChatMemoryProvider chatMemoryProvider,
            ToolRouter toolRouter,
            SystemPromptResolver promptResolver,
            RedisCheckpointSaver checkpointSaver,
            ObjectStreamStateSerializer<MessagesState<ChatMessage>> graphStateSerializer,
            CancellationRegistry cancellationRegistry,
            @Qualifier("agentExecutor") ExecutorService agentExecutor,
            ModelResolver modelResolver) {

        log.info("[Agent编排配置] 创建 AgentOrchestrationService，默认模型={}，会话记忆=Redis持久化，"
                        + "检查点=Redis(支持 interrupt/resume)，系统提示词解析器={}，模型解析器={}",
                streamingChatModel == null ? "unknown" : streamingChatModel.getClass().getSimpleName(),
                promptResolver.getClass().getSimpleName(),
                modelResolver.getClass().getSimpleName());

        return new AgentOrchestrationService(streamingChatModel, chatMemoryProvider, toolRouter,
                promptResolver, checkpointSaver, graphStateSerializer, cancellationRegistry, agentExecutor,
                modelResolver);
    }

    /**
     * 系统提示词解析器
     */
    @Bean
    @ConditionalOnMissingBean(SystemPromptResolver.class)
    public SystemPromptResolver systemPromptResolver(DomainSettingsStore domainSettingsStore,
                                                     PromptProperties promptProperties) {
        return new DomainSystemPromptResolver(domainSettingsStore, promptProperties);
    }
}
