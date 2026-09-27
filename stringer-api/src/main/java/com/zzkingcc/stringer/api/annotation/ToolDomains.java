package com.zzkingcc.stringer.api.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 类级默认域：这个类里所有 {@link Tool} 方法共用一组可用域。
 *
 * <p>解决的问题：一个类里的工具通常同属一个域，逐个在 {@code @Tool(domains = ...)} 上重写
 * 是纯粹的重复。方法级 {@code domains} 优先于本注解（就近覆盖）。</p>
 *
 * <pre>{@code
 * @Service
 * @ToolDomains("admin")
 * public class OrderAdminTools {
 *
 *     @Tool(desc = "关闭订单。用户明确要求取消时调用", effect = Tool.Effect.WRITE)
 *     public String closeOrder(String orderNo) { ... }   // 自动属于 admin 域
 * }
 * }</pre>
 *
 * @author zzkingcc
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface ToolDomains {

    /**
     * 默认可用域；留空等价于不写（该类工具按"留空 → 只属于兜底域 default"处理）。
     */
    String[] value() default {};
}
