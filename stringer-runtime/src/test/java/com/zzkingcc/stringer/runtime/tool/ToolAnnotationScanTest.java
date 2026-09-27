package com.zzkingcc.stringer.runtime.tool;

import com.zzkingcc.stringer.api.annotation.Tool;
import com.zzkingcc.stringer.api.annotation.ToolDomains;
import com.zzkingcc.stringer.api.annotation.ToolParam;
import com.zzkingcc.stringer.api.tool.ToolDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新注解 {@code @Tool} 与 DTO 参数展开的回归测试。
 *
 * <p>这里盯的是两条曾经出错、且改坏了不会立即被发现的规则：</p>
 * <ol>
 *   <li>DTO / record 参数必须展开成嵌套的 object（旧实现退化成 string，模型根本构造不出对象）；</li>
 *   <li>类级 {@code @ToolDomains} 必须被方法继承。</li>
 * </ol>
 *
 * @author zzkingcc
 */
class ToolAnnotationScanTest {

    /** 参数说明标在 record 的字段上（只写一次，可被多个工具复用） */
    public record OrderQuery(@ToolParam("订单号，如 FR2024001") String orderNo,
                             @ToolParam("是否返回明细") Boolean detail) {
    }

    @ToolDomains("admin")
    static class DemoTools {

        @Tool(desc = "按条件查询订单。用户追问发货/物流时调用")
        public String query(OrderQuery args) {
            return "ok";
        }

        @Tool(desc = "关闭订单。用户明确要求取消时调用",
                effect = Tool.Effect.WRITE,
                approval = Tool.Approval.ALWAYS,
                approvalReason = "关单需人工确认")
        public String close(@ToolParam("订单号") String orderNo) {
            return "ok";
        }
    }

    private static ToolDescriptor find(List<ToolRegistry.Registered> tools, String name) {
        return tools.stream()
                .map(ToolRegistry.Registered::descriptor)
                .filter(descriptor -> name.equals(descriptor.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到工具 " + name));
    }

    @Test
    void registersToolsFromNewAnnotation() {
        List<ToolRegistry.Registered> tools = AnnotatedToolScanner.scan(new DemoTools());
        assertEquals(2, tools.size(), "@Tool 标注的方法都应被注册");
    }

    @Test
    void dtoParameterIsExpandedAsObject() {
        ToolDescriptor query = find(AnnotatedToolScanner.scan(new DemoTools()), "query");

        assertEquals(1, query.params().size());
        ToolDescriptor.Param args = query.params().get(0);

        // 关键断言：DTO 不能被退化成 string，否则模型产出字符串、反序列化必然失败
        assertEquals("object", args.type(), "DTO 参数必须展开为 object");
        assertEquals(2, args.properties().size(), "record 的字段应被展开");

        ToolDescriptor.Param orderNo = args.properties().get(0);
        assertEquals("orderNo", orderNo.name());
        assertEquals("订单号，如 FR2024001", orderNo.description(), "字段上的 @ToolParam 必须被读取");
        assertEquals("string", orderNo.type());
    }

    @Test
    void classLevelDomainsAndApprovalAreInherited() {
        ToolDescriptor close = find(AnnotatedToolScanner.scan(new DemoTools()), "close");

        assertTrue(close.profiles().contains("admin"), "应继承类级 @ToolDomains");
        assertTrue(close.requiresApproval(), "@Tool(approval = ALWAYS) 应生效");
        assertEquals("关单需人工确认", close.approval().reason());
        assertEquals("WRITE", close.sideEffect().name());
    }

    @Test
    void missingParamDescriptionIsCounted() {
        // 未写参数说明的工具：能注册成功，但缺说明要被统计出来（由扫描器打 WARN）
        ToolDescriptor close = find(AnnotatedToolScanner.scan(new DemoTools()), "close");
        assertFalse(com.zzkingcc.stringer.api.tool.ParamSchemaResolver
                        .countMissingDescription(List.of(close.params().get(0))) > 0,
                "本例的 close 写了参数说明，不应计为缺失");
    }
}
