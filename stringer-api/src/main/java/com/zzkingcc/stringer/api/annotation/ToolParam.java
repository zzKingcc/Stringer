package com.zzkingcc.stringer.api.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 描述工具参数的语义注解 —— 可标在<b>形参前</b>，也可标在<b>参数 DTO 的字段上</b>。
 *
 * <p>推荐写法只用一个值（参数说明）：</p>
 *
 * <pre>{@code
 * // 标在形参前：1~2 个简单参数时最直观
 * public OrderVO query(@ToolParam("订单号，如 FR2024001") String orderNo) { ... }
 *
 * // 标在 DTO 字段上：3+ 参数、被多个工具复用、或有嵌套时只写一次
 * public record OrderQuery(@ToolParam("订单号，如 FR2024001") String orderNo,
 *                          @ToolParam("是否返回明细") Boolean detail) { }
 * }</pre>
 *
 * <p>两者同时存在时：<b>形参注解优先</b>（就近覆盖）。</p>
 *
 * @author zzkingcc
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Documented
public @interface ToolParam {

    /**
     * 参数说明：写清业务含义、单位、格式、边界，例如"退款金额，单位：元，必须 ≤ 订单实付金额"。
     *
     * <p>不写也能注册成功，但模型只能靠参数名猜测 —— 复杂参数（订单号、金额、日期）
     * 的调用准确率会明显下降，因此<b>强烈建议写</b>。</p>
     */
    String value() default "";

    /**
     * @deprecated 改用 {@link #value()}：新写法用一个值即可，无需显式写 {@code description = ...}。
     *             保留为别名（{@code value} 留空时回落读取），两个版本后移除。
     */
    @Deprecated(since = "1.1")
    String description() default "";

    /** 参数名；留空则取形参名（或标在字段上时取字段名） */
    String name() default "";

    /** 是否必填；{@code Optional<T>} 会被自动判定为可选 */
    boolean required() default true;

    /**
     * @deprecated 改用 {@link ToolAdvanced#example()}（按参数名对应，与参数顺序无关）。
     */
    @Deprecated(since = "1.1")
    String example() default "";

    /**
     * @deprecated 改用 {@link ToolAdvanced#allowValues()}（按参数名对应）。
     */
    @Deprecated(since = "1.1")
    String[] allowValues() default {};

    /**
     * @deprecated 改用 {@link ToolAdvanced#sensitive()}（按参数名给出清单）。
     */
    @Deprecated(since = "1.1")
    boolean sensitive() default false;
}
