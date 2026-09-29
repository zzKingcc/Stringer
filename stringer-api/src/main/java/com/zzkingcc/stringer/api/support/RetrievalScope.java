package com.zzkingcc.stringer.api.support;

import com.zzkingcc.stringer.api.agent.Domains;

/**
 * 检索时使用的域（线程绑定）。
 *
 * <p>为什么要有它：知识库文档的归属域写在切片元数据里，过滤必须<b>下推到检索通道内</b>
 * （见 {@code NativeScriptScoreContentRetriever} / {@code KeywordMatchContentRetriever}）。
 * 但检索器拿不到"这次调用属于哪个域"——工具方法签名里没有域参数，让模型自己填更不可信。
 * 所以由编排层在调用工具前把它绑到线程上，检索器直接读。</p>
 *
 * <p>绑定点唯一：{@code AgentOrchestrationService#toolsNode}，与 {@code ToolInvocationContext}
 * 同一处绑定、同一处解除（工具执行是同步阻塞调用，不会泄漏到同线程的下一轮）。</p>
 *
 * <p>未绑定时的语义 = <b>不过滤</b>：只可能发生在非对话路径（管控台预览、重建等），
 * 保持升级前"全库检索"的行为，避免这些调用凭空查不到东西。</p>
 *
 * @author zzkingcc
 */
public final class RetrievalScope {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private RetrievalScope() {
    }

    /**
     * 绑定本轮检索所用的域；{@code null} / 空白按 {@link Domains#DEFAULT} 处理
     * （与工具的域声明语义一致：留空 → 只属兜底域）。
     */
    public static void bind(String domain) {
        HOLDER.set(Domains.normalize(domain));
    }

    /** 当前域；未绑定时返回 {@code null}（表示不做域过滤） */
    public static String current() {
        return HOLDER.get();
    }

    /** 解除绑定（与 {@link #bind} 成对调用） */
    public static void clear() {
        HOLDER.remove();
    }
}
