package com.zzkingcc.stringer.api.agent;

/**
 * 域标识的约定与工具方法。
 *
 * <p>域标识是大小写敏感的普通字符串。平台预置一个兜底域 {@link #DEFAULT}：
 * 工具声明留空、调用未指定域时都归入它，因此系统中不存在"没有域的对话"与"没有归属的工具"。</p>
 *
 * @author zzkingcc
 */
public final class Domains {

    /**
     * 兜底域：工具未声明归属、调用未指定域时使用。
     *
     * <p>它在服务端启动时被幂等预置，且不可删除 —— 否则兜底就无处可落。</p>
     */
    public static final String DEFAULT = "default";

    /**
     * 通配域：<b>仅用于工具的域声明</b>，表示"任何域都可用"。
     *
     * <p>必须显式写出。留空表示"只属于兜底域"，两者语义不同，不可混用。</p>
     */
    public static final String ANY = "*";

    private Domains() {
    }

    /**
     * 归一化域标识：{@code null} / 空白 → {@link #DEFAULT}；其余去除首尾空白。
     */
    public static String normalize(String domainId) {
        return domainId == null || domainId.isBlank() ? DEFAULT : domainId.trim();
    }

    /**
     * 是否兜底域（{@code null} / 空白视为兜底域）。
     */
    public static boolean isDefault(String domainId) {
        return DEFAULT.equals(normalize(domainId));
    }
}
