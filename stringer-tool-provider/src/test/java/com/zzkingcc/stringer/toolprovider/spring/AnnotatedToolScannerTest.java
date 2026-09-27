package com.zzkingcc.stringer.toolprovider.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.stringer.api.annotation.StringerTool;
import com.zzkingcc.stringer.api.annotation.ToolParam;
import com.zzkingcc.stringer.api.annotation.ToolPolicy;
import com.zzkingcc.stringer.toolprovider.ToolHandler;
import com.zzkingcc.stringer.toolprovider.ToolRegistrar;
import com.zzkingcc.stringer.toolprovider.ToolSpec;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注解扫描器的行为验证：方法签名 → ToolSpec、参数 JSON → 方法调用
 */
class AnnotatedToolScannerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void 方法签名变成工具声明与参数schema() {
        Map<String, ToolSpec> specs = new LinkedHashMap<>();
        Map<String, ToolHandler> handlers = new LinkedHashMap<>();

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(DemoTools.class)) {
            int count = new AnnotatedToolScanner(ctx).registerTo(new Collector(specs, handlers));
            assertEquals(3, count);
        }

        ToolSpec note = specs.get("addNote");
        assertEquals("记一条备注", note.description());
        assertEquals(List.of("admin"), note.profiles());
        assertEquals("WRITE", note.sideEffect());
        assertTrue(note.requiresApproval(), "声明了 ALWAYS 审批就应需要人工确认");
        assertEquals("ALWAYS", note.approvalMode());

        JsonNode parameters = note.parameters();
        assertEquals("object", parameters.path("type").asText());
        assertTrue(parameters.path("required").toString().contains("text"));
        assertEquals("string", parameters.path("properties").path("text").path("type").asText());
        assertEquals("备注内容", parameters.path("properties").path("text").path("description").asText());
    }

    @Test
    void 参数JSON反射调用方法() throws Exception {
        Map<String, ToolSpec> specs = new LinkedHashMap<>();
        Map<String, ToolHandler> handlers = new LinkedHashMap<>();

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(DemoTools.class)) {
            new AnnotatedToolScanner(ctx).registerTo(new Collector(specs, handlers));
        }

        assertEquals("已记录: 张三申请退款",
                handlers.get("addNote").handle("{\"text\":\"张三申请退款\"}"));

        // 带类型参数：int / 枚举 / List 全部由签名推导并绑定
        assertEquals("城市 1 个：杭州，类型 VIP",
                handlers.get("countCities").handle("{\"count\":1,\"city\":\"杭州\",\"level\":\"VIP\"}"));

        // 缺必填参数：抛异常交给 SDK 包装成工具失败原因
        assertThrows(IllegalArgumentException.class, () -> handlers.get("addNote").handle("{}"));
    }

    @Test
    void 工具名重复直接失败() {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(DuplicatedTools.class)) {
            assertThrows(IllegalStateException.class,
                    () -> new AnnotatedToolScanner(ctx).registerTo(new Collector(new LinkedHashMap<>(), new LinkedHashMap<>())));
        }
    }

    // ==================== 被测用的工具 Bean ====================

    static class DemoTools {

        @StringerTool(name = "addNote", description = "记一条备注", profiles = {"admin"},
                category = "运营", sideEffect = StringerTool.SideEffect.WRITE)
        @ToolPolicy(approval = @ToolPolicy.Approval(mode = ToolPolicy.Approval.Mode.ALWAYS, reason = "写操作需确认"))
        public String addNote(@ToolParam(description = "备注内容") String text) {
            return "已记录: " + text;
        }

        @StringerTool(description = "统计城市数量，用于演示类型化参数绑定")
        public String countCities(int count, String city, Level level) {
            return "城市 " + count + " 个：" + city + "，类型 " + level;
        }

        @StringerTool(description = "无参数工具")
        public String ping() {
            return "pong";
        }
    }

    enum Level {
        NORMAL, VIP
    }

    static class DuplicatedTools {

        @StringerTool(name = "sameName", description = "第一个")
        public String first() {
            return "1";
        }

        @StringerTool(name = "sameName", description = "第二个")
        public String second() {
            return "2";
        }
    }

    /** 把注册结果收进 Map，便于断言 */
    private record Collector(Map<String, ToolSpec> specs, Map<String, ToolHandler> handlers) implements ToolRegistrar {
        @Override
        public ToolRegistrar register(ToolSpec spec, ToolHandler handler) {
            specs.put(spec.name(), spec);
            handlers.put(spec.name(), handler);
            return this;
        }
    }
}
