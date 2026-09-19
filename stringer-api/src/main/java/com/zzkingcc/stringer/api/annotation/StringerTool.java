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
     * 声明 tool 属于哪些域，自动创建域。
     */
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
