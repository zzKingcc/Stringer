package com.zzkingcc.stringer.runtime.tool;

import com.zzkingcc.stringer.api.annotation.StringerTool;
import com.zzkingcc.stringer.api.annotation.Tool;
import com.zzkingcc.stringer.api.annotation.ToolDomains;
import com.zzkingcc.stringer.api.annotation.ToolParam;
import com.zzkingcc.stringer.api.annotation.ToolPolicy;
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
     * <p>方法上有 {@code @Tool}（新）或 {@code @StringerTool}（旧，已废弃）都算工具 ——
     * 两者语义等价，旧注解保留两个版本周期。</p>
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

            if (descriptor.requiresApproval()
                    && "CONDITIONAL".equalsIgnoreCase(descriptor.approval().mode())
                    && descriptor.approval().condition().isBlank()) {
                log.warn("[工具扫描] {} 声明 CONDITIONAL 审批但未写 condition，将按 ALWAYS 处理（保守）", name);
            }

            registered.add(ToolRegistry.Registered.local(descriptor, specification, executor));
        }

        if (registered.isEmpty()) {
            log.warn("[工具扫描] {} 未实现任何 @Tool / @StringerTool 方法，已注册 0 个工具",
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
        StringerTool legacy = method.getAnnotation(StringerTool.class);
        if (tool == null && legacy == null) {
            return null;
        }

        String source = declaringClass.getSimpleName() + "#" + method.getName();
        String name = firstNonBlank(
                tool != null ? tool.value() : "",
                legacy != null ? legacy.name() : "",
                method.getName());
        String description = tool != null ? tool.desc() : legacy.description();

        List<String> domains = resolveDomains(declaringClass, tool, legacy);
        ToolDescriptor.Approval approval = tool != null ? resolveApproval(tool) : resolveApproval(method);

        return new ToolDescriptor(
                name,
                description,
                legacy != null ? legacy.category() : "default",
                legacy != null ? legacy.version() : "1.0.0",
                tool != null ? toSideEffect(tool.effect()) : legacy.sideEffect(),
                legacy == null || legacy.idempotent(),
                legacy == null || legacy.toModel(),
                List.copyOf(ParamSchemaResolver.resolve(method)),
                List.copyOf(domains),
                approval,
                source);
    }

    /** 新枚举 → 既有的副作用枚举（两侧枚举值一一对应） */
    private static StringerTool.SideEffect toSideEffect(Tool.Effect effect) {
        return switch (effect) {
            case READ -> StringerTool.SideEffect.READ;
            case WRITE -> StringerTool.SideEffect.WRITE;
            case DESTRUCTIVE -> StringerTool.SideEffect.DESTRUCTIVE;
        };
    }

    /** {@code @Tool(approval = ALWAYS)} → Approval 记录 */
    private static ToolDescriptor.Approval resolveApproval(Tool tool) {
        if (tool.approval() != Tool.Approval.ALWAYS) {
            return ToolDescriptor.Approval.none();
        }
        return new ToolDescriptor.Approval("ALWAYS", "", tool.approvalReason(), List.of(), 0);
    }

    /**
     * 可用域：方法级 {@code @Tool#domains()} → 旧 {@code @StringerTool#domains()/profiles()}
     * → 类级 {@code @ToolDomains} → 留空（＝只属于兜底域 default）。
     *
     * <p>这里<b>不</b>在留空时回填兜底域：留空本身有语义，由
     * {@link ToolDescriptor#visibleIn(String)} 统一解释，避免两处判断各说各话。</p>
     */
    private static List<String> resolveDomains(Class<?> declaringClass, Tool tool, StringerTool legacy) {
        String[] raw = null;
        if (tool != null && tool.domains().length > 0) {
            raw = tool.domains();
        } else if (legacy != null) {
            raw = legacy.domains().length > 0 ? legacy.domains() : legacy.profiles();
        }
        if ((raw == null || raw.length == 0) && declaringClass != null) {
            ToolDomains classLevel = declaringClass.getAnnotation(ToolDomains.class);
            if (classLevel != null && classLevel.value().length > 0) {
                raw = classLevel.value();
            }
        }
        if (raw == null || raw.length == 0) {
            return List.of();
        }
        return Arrays.stream(raw)
                .filter(p -> p != null && !p.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
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
            case "array" -> JsonArraySchema.builder()
                    .description(description)
                    .items(JsonStringSchema.builder().build())
                    .build();
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

    /**
     * 读取 {@code @ToolPolicy}（旧写法）
     */
    private static ToolDescriptor.Approval resolveApproval(Method method) {
        ToolPolicy policy = method.getAnnotation(ToolPolicy.class);
        if (policy == null) {
            return ToolDescriptor.Approval.none();
        }
        ToolPolicy.Approval approval = policy.approval();
        if (approval.mode() == ToolPolicy.Approval.Mode.NONE) {
            return ToolDescriptor.Approval.none();
        }
        return new ToolDescriptor.Approval(
                approval.mode().name(),
                approval.condition(),
                approval.reason(),
                Arrays.asList(approval.approverRoles()),
                approval.timeoutSeconds());
    }

    /**
     * 读取工具的<b>可用域声明</b>（授权边界）。
     *
     * <p>优先 {@code @StringerTool#domains()}；留空时回落到已废弃的 {@code profiles()}（老写法兼容）。
     * 两者都按"去空白、去重、去空串"归一化。</p>
     *
     * <p>注意这里<b>不</b>在留空时回填兜底域：留空本身有语义（只属于 default），
     * 由 {@link com.zzkingcc.stringer.api.tool.ToolDescriptor#visibleIn(String)} 统一解释，
     * 避免两处判断各说各话。</p>
     */
    private static List<String> resolveProfiles(StringerTool annotation) {
        String[] raw = annotation.domains();
        if (raw == null || raw.length == 0) {
            raw = annotation.profiles();
        }
        if (raw == null || raw.length == 0) {
            return List.of();
        }
        return Arrays.stream(raw)
                .filter(p -> p != null && !p.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
