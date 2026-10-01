package com.zzkingcc.stringer.server.model;

import com.zzkingcc.stringer.common.exception.NotConfiguredException;
import com.zzkingcc.stringer.runtime.model.ModelResolver;
import dev.langchain4j.model.chat.StreamingChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 默认模型解析器：把「域 → 档案 → 客户端」这条链路接起来。
 *
 * <p>没有"内置 default"这一说：对话模型只来自用户自建的模型档案。别名命中档案后由
 * {@code ModelClientFactory} 按指纹缓存构建；命中不了（域没绑 / 档案不存在 / 不可用 / 非对话类型）就如实抛出
 * {@link NotConfiguredException}，让调用方拿到明确原因，而不是回落到一个幽灵模型。</p>
 *
 * @author zzkingcc
 */
@Slf4j
public class DefaultModelResolver implements ModelResolver {

    private final ModelProfileRegistry registry;
    private final ModelClientFactory factory;

    public DefaultModelResolver(ModelProfileRegistry registry,
                                ModelClientFactory factory) {
        this.registry = registry;
        this.factory = factory;
    }

    @Override
    public StreamingChatModel streamingChat(String domain) {
        List<String> aliases = registry.resolveAliases(domain);

        /* 域一个可调用模型都没配 → 如实抛出，由调用点转成明确失败。
           不再回落任何内置 default：域必须先显式配置自己的模型（新建域即处于"无可调用"状态）。 */
        if (aliases.isEmpty()) {
            throw new NotConfiguredException("域 " + domain + " 未配置可调用模型"
                    + " —— 请到管控台「域空间」为该域指定至少一个模型");
        }

        /* 域的可调用列表按顺序试：首个可用即用（列表只有一个时行为与"单值绑定"一致） */
        for (String alias : aliases) {
            ModelProfile profile = registry.profile(alias).orElse(null);
            if (profile == null || !profile.isUsable() || !profile.isChat()) {
                log.warn("[模型档案] 域 {} 的可调用模型 {} 不存在 / 不可用 / 非对话类型，尝试列表中的下一个（档案列表：{}）",
                        domain, alias, registry.profiles().stream().map(ModelProfile::alias).toList());
                continue;
            }
            if (!profile.supportsTools()) {
                // 只告警不拒绝：先用便宜模型把链路跑通是合理诉求，但必须让人看见
                log.warn("[模型档案] 域 {} 使用的档案 {} 未声明 {} 能力：模型将不会调用任何工具",
                        domain, alias, ModelProfile.CAP_TOOLS);
            }
            return factory.streamingChat(profile);
        }

        /* 列表里的档案全都不存在 / 不可用 / 非对话类型 → 同样不再回落任何默认模型 */
        log.warn("[模型档案] 域 {} 的可调用列表 {} 均不可用（档案列表：{}）",
                domain, aliases, registry.profiles().stream().map(ModelProfile::alias).toList());
        throw new NotConfiguredException("域 " + domain + " 的可调用列表 " + aliases
                + " 中没有可用模型 —— 请到管控台「域空间」重新指定");
    }
}
