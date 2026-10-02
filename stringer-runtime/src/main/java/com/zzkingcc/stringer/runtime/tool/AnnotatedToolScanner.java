package com.zzkingcc.stringer.runtime.tool;

import com.zzkingcc.stringer.api.agent.Domains;
import com.zzkingcc.stringer.api.annotation.Tool;
import com.zzkingcc.stringer.api.annotation.ToolDomains;
import com.zzkingcc.stringer.api.annotation.ToolParam;
import com.zzkingcc.stringer.api.tool.ParamSchemaResolver;
import com.zzkingcc.stringer.api.tool.ToolDescriptor;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 注解扫描器
 * @author zzkingcc
 */
public final class AnnotatedToolScanner {

    private static final Logger log = LoggerFactory.getLogger(AnnotatedToolScanner.class);

    private AnnotatedToolScanner() {
    }

    /**
     * 扫描一个工具提供者实例。
     *
     * <p>方法上有 {@code @Tool} 即算工具。</p>
     *
     * @param provider 工具提供者 Bean
     * @return 该 Bean 上所有工具方法的注册项（可能为空）
     */
    public static List<ToolRegistry.Registered> scan(Object provider) {
        List<ToolRegistry.Registered> registered = new ArrayList<>();
        if (provider == null) {
            return registered;
        }

        for (Method method : provider.getClass().getMethods()) {
            ToolDescriptor descriptor = describe(provider.getClass(), method);
            if (descriptor == null) {
                continue;
            }
            String name = descriptor.name();

            List<ToolDescriptor.Param> params = descriptor.params();
            int missing = ParamSchemaResolver.countMissingDescription(params);
            if (missing > 0) {
                // 不阻断：缺说明只是"模型要猜"，但必须让人看见
                log.warn("[工具扫描] {} 有 {} 个参数缺少说明，可能影响调用准确率；"
                        + "建议在形参前或参数 DTO 的字段上加 @ToolParam(\"…\")", name, missing);
            }

            ToolSpecification specification = ToolSpecification.builder()
                    .name(name)
                    .description(descriptor.description())
                    .parameters(toJsonSchema(params))
                    .build();

            // 复用 LangChain4j 的参数反序列化：arguments(JSON) → 方法参数
            DefaultToolExecutor executor = new DefaultToolExecutor(provider, method);

            registered.add(ToolRegistry.Registered.local(descriptor, specification, executor));
        }

        if (registered.isEmpty()) {
            log.warn("[工具扫描] {} 未实现任何 @Tool 方法，已注册 0 个工具",
                    provider.getClass().getName());
        }
        return registered;
    }

    /**
     * 由方法上的注解解析出工具描述；不是工具方法时返回 {@code null}。
     *
     * <p>参数结构一律交给 {@link ParamSchemaResolver} —— 与工具实例 SDK 共用同一份实现，
     * 避免"同一段工具代码搬到另一侧得到不同 schema"。</p>
     */
    private static ToolDescriptor describe(Class<?> declaringClass, Method method) {
        Tool tool = method.getAnnotation(Tool.class);
        if (tool == null) {
            return null;
        }

        String source = declaringClass.getSimpleName() + "#" + method.getName();
        String name = firstNonBlank(tool.value(), method.getName());
        String description = tool.desc();

        List<String> domains = resolveDomains(declaringClass, tool);
        ToolDescriptor.Approval approval = resolveApproval(tool);

        return new ToolDescriptor(
                name,
                description,
                "default",
                "1.0.0",
                tool.effect(),
                true,
                true,
                List.copyOf(ParamSchemaResolver.resolve(method)),
                List.copyOf(domains),
                approval,
                source);
    }

    /** {@code @Tool(approval = ALWAYS)} → Approval 记录 */
    private static ToolDescriptor.Approval resolveApproval(Tool tool) {
        if (tool.approval() != Tool.Approval.ALWAYS) {
            return ToolDescriptor.Approval.none();
        }
        return new ToolDescriptor.Approval("ALWAYS", "", tool.approvalReason(), List.of(), 0);
    }

    /**
     * 可用域：方法级 {@code @Tool#domains()} → 类级 {@code @ToolDomains} → 留空。
     *
     * <p>这里<b>不</b>在留空时回填根域：留空本身有语义（挂根域、全树可见），由
     * {@link ToolDescriptor#declaredDomains()} 统一解释，避免两处判断各说各话。</p>
     *
     * <p>声明了就必须是<b>合法完整路径</b>：非法直接中断启动。以前只告警跳过，
     * 结果是这个字符串照样进"已声明域"集合、让入口放行，等于凭空造出一个可用的域。</p>
     */
    private static List<String> resolveDomains(Class<?> declaringClass, Tool tool) {
        String[] raw = tool.domains().length > 0 ? tool.domains() : null;
        if ((raw == null || raw.length == 0) && declaringClass != null) {
            ToolDomains classLevel = declaringClass.getAnnotation(ToolDomains.class);
            if (classLevel != null && classLevel.value().length > 0) {
                raw = classLevel.value();
            }
        }
        if (raw == null || raw.length == 0) {
            return List.of();
        }
        List<String> domains = Arrays.stream(raw)
                .filter(p -> p != null && !p.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        for (String domain : domains) {
            String reason = Domains.validatePath(domain);
            if (reason != null) {
                throw new IllegalStateException("@Tool(domains = {\"" + domain + "\"}) 不合法（" + reason
                        + "）：域标识须为从 " + Domains.DEFAULT + " 出发的完整路径，如 default.sales");
            }
        }
        return domains;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return "";
    }

    // 参数结构（含 DTO 递归展开、深度与循环防护、形参/字段两种注解载体）统一由 ParamSchemaResolver 提供，
    // 与工具实例 SDK 共用同一份实现 —— 避免同一段工具代码在两侧得到不同的 schema。

    /**
     * 由参数描述生成 JSON Schema
     */
    private static JsonObjectSchema toJsonSchema(List<ToolDescriptor.Param> params) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        List<String> required = new ArrayList<>();

        for (ToolDescriptor.Param param : params) {
            builder.addProperty(param.name(), toSchemaElement(param));
            if (param.required()) {
                required.add(param.name());
            }
        }

        if (!required.isEmpty()) {
            builder.required(required.toArray(new String[0]));
        }
        return builder.build();
    }

    private static JsonSchemaElement toSchemaElement(ToolDescriptor.Param param) {
        String description = param.description();
        return switch (param.type()) {
            case "integer" -> JsonIntegerSchema.builder().description(description).build();
            case "number" -> JsonNumberSchema.builder().description(description).build();
            case "boolean" -> JsonBooleanSchema.builder().description(description).build();
            // DTO / record：把子字段递归展开成嵌套 object，模型才知道该构造什么
            case "object" -> objectSchema(param);
            case "enum" -> JsonEnumSchema.builder()
                    .description(description)
                    .enumValues(param.allowValues())
                    .build();
            case "array" -> {
                // 数组元素结构：本地工具与远端工具必须用同一套口径展开，否则同样是 List<DTO>，
                // 本地给模型看"数组套字符串"、远端给"数组套对象" —— 同一段工具代码两种形态不一致
                JsonSchemaElement item = param.items().isEmpty()
                        ? JsonStringSchema.builder().build()
                        : toSchemaElement(param.items().get(0));
                yield JsonArraySchema.builder().description(description).items(item).build();
            }
            default -> JsonStringSchema.builder().description(description).build();
        };
    }

    /**
     * 嵌套对象：子字段递归展开；没有子字段时给一个"空对象"而不是退化成字符串 ——
     * 退化成字符串会让模型产出字符串，反序列化到 DTO 必然失败。
     */
    private static JsonSchemaElement objectSchema(ToolDescriptor.Param param) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder()
                .description(param.description());
        List<String> required = new ArrayList<>();
        for (ToolDescriptor.Param child : param.properties()) {
            builder.addProperty(child.name(), toSchemaElement(child));
            if (child.required()) {
                required.add(child.name());
            }
        }
        if (!required.isEmpty()) {
            builder.required(required.toArray(new String[0]));
        }
        return builder.build();
    }
}
