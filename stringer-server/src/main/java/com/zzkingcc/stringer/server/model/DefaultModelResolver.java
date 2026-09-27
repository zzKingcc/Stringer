package com.zzkingcc.stringer.server.model;

import com.zzkingcc.stringer.runtime.model.ModelResolver;
import com.zzkingcc.stringer.server.settings.LlmModelHolder;
import dev.langchain4j.model.chat.StreamingChatModel;
import lombok.extern.slf4j.Slf4j;

/**
 * 默认模型解析器：把「域 → 档案 → 客户端」这条链路接起来。
 *
 * <p>两种结果：</p>
 * <ul>
 *   <li>别名是内置 {@code default}（含"域没绑定"的情况）→ 返回 {@code LlmModelHolder} 的<b>委派代理</b>。
 *       用代理而不是快照，是为了让管控台「模型设置」页改了之后立即生效 —— 与升级前的单模型行为一致。</li>
 *   <li>别名是自建档案 → 由 {@code ModelClientFactory} 按指纹缓存构建。</li>
 * </ul>
 *
 * <p>档案不存在或不可用（只有手工改坏配置文件才会发生）时<b>回落内置 default 并告警</b>：
 * 可用性优先，问题通过日志暴露，而不是让整轮对话直接失败。</p>
 *
 * @author zzkingcc
 */
@Slf4j
public class DefaultModelResolver implements ModelResolver {

    private final ModelProfileRegistry registry;
    private final ModelClientFactory factory;
    private final LlmModelHolder holder;

    public DefaultModelResolver(ModelProfileRegistry registry,
                                ModelClientFactory factory,
                                LlmModelHolder holder) {
        this.registry = registry;
        this.factory = factory;
        this.holder = holder;
    }

    @Override
    public StreamingChatModel streamingChat(String domain) {
        String alias = registry.resolveAlias(domain);

        if (registry.isBuiltinDefault(alias)) {
            return holder.streamingChatModel();
        }

        ModelProfile profile = registry.profile(alias).orElse(null);
        if (profile == null || !profile.isUsable()) {
            log.warn("[模型档案] 域 {} 绑定的档案 {} 不存在或不可用，已回落内置 default；"
                    + "请到管控台「模型设置」修正绑定（档案列表：{}）",
                    domain, alias, registry.profiles().stream().map(ModelProfile::alias).toList());
            return holder.streamingChatModel();
        }

        if (!profile.supportsTools()) {
            // 只告警不拒绝：先用便宜模型把链路跑通是合理诉求，但必须让人看见
            log.warn("[模型档案] 域 {} 使用的档案 {} 未声明 {} 能力：模型将不会调用任何工具",
                    domain, alias, ModelProfile.CAP_TOOLS);
        }
        return factory.streamingChat(profile);
    }
}
