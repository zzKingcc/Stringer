package com.zzkingcc.stringer.api.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明 tool 注解
 *
 * @author zzkingcc
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface StringerTool {

    /** 工具名；留空则取方法名。全局唯一，重名注册会直接失败 */
    String name() default "";

    /** 给 LLM 的用途说明（必填）。写法建议：写清"何时调用 / 何时不要调用"，比参数描述更重要 */
    String description();

    /** 分组，用于管理页分类（<b>不参与任何过滤</b>） */
    String category() default "default";

    /**
     * 本工具<b>允许被哪些域使用</b> —— 授权边界，不是展示标签。
     *
     * <p>三种写法：</p>
     * <ul>
     *   <li>显式域名：只在这些域下可用，如 {@code {"customer", "admin"}}；</li>
     *   <li>留空：<b>只属于兜底域 {@code default}</b>（不再视为全域可见）；</li>
     *   <li>通配 {@code {"*"}}：任何域都可用 —— 必须显式写出来，让"全域"是一个决定而不是漏写。</li>
     * </ul>
     *
     * <p>域侧只能在声明的范围内决定用不用、怎么用，<b>不能</b>把工具拉进未授权的域
     * （越界引用会在发布校验时失败，不静默放行）。</p>
     */
    String[] domains() default {};

    /**
     * @deprecated 更名为 {@link #domains()}（语义从"可见性"升级为"授权边界"）。
     *             保留为别名：仅当 {@code domains} 留空时回落读取，计划两个版本周期后移除。
     */
    @Deprecated(since = "1.0")
    String[] profiles() default {};

    /**
     * 工具自身的语义化版本（如 {@code 1.0.0}），<b>与 SDK 发版号无关</b>：SDK 升级不改工具契约时不要动它。
     * <p>当前注册表按工具名归并（同名＝同一工具的多副本），该字段仅登记展示，不参与归并或路由。
     */
    String version() default "1.0.0";

    /** 副作用等级；{@link SideEffect#READ} 可自由调用，写/破坏性操作应配 {@link ToolPolicy} 的审批 */
    SideEffect sideEffect() default SideEffect.READ;

    /** 是否幂等 */
    boolean idempotent() default true;

    /** 结果是否回填给 LLM */
    boolean toModel() default true;

    /**
     * 副作用等级
     */
    enum SideEffect {
        /** 只读，无副作用 */
        READ,
        /** 写操作，可回滚或可重复执行 */
        WRITE,
        /** 破坏性操作，必须配审批 */
        DESTRUCTIVE
    }
}
