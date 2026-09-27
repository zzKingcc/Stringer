package com.zzkingcc.stringer.api.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具的高级可选参数 —— 承接"偶尔要写、但绝不该默认出现在每个工具上"的字段。
 *
 * <p>为什么单独一个注解：这些字段若塞进 {@link Tool}，就会让每个工具声明都变成
 * "大多数是空、偶尔填一项"的长尾列表。它们放在这里，绝大多数工具<b>永远不用看</b>。</p>
 *
 * <p>约定：<b>按参数名对应，不做位置对齐</b> —— 位置对齐在参数增删或调序时会静默错位，
 * 而编译器不会提醒。</p>
 *
 * <pre>{@code
 * @Tool(desc = "查询订单")
 * @ToolAdvanced(example = {"orderNo=FR2024001"}, sensitive = {"idCard"})
 * public OrderVO query(String orderNo, String idCard) { ... }
 * }</pre>
 *
 * @author zzkingcc
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface ToolAdvanced {

    /**
     * 参数示例，按 {@code 参数名=示例值} 给出，如 {@code {"orderNo=FR2024001"}}。
     */
    String[] example() default {};

    /**
     * 枚举白名单，按 {@code 参数名=值1|值2} 给出，如 {@code {"status=PAID|REFUNDED"}}。
     */
    String[] allowValues() default {};

    /**
     * 需要脱敏的参数名清单：日志、事件、审批 payload 中只显示掩码。
     */
    String[] sensitive() default {};
}
