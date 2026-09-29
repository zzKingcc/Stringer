package com.zzkingcc.stringer.toolprovider.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.stringer.api.annotation.Tool;
import com.zzkingcc.stringer.api.annotation.ToolDomains;
import com.zzkingcc.stringer.api.annotation.ToolParam;
import com.zzkingcc.stringer.api.tool.ParamSchemaResolver;
import com.zzkingcc.stringer.toolprovider.ToolHandler;
import com.zzkingcc.stringer.toolprovider.ToolRegistrar;
import com.zzkingcc.stringer.toolprovider.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 注解式工具扫描器 —— 在客户进程里把"方法"直接变成"工具"
 *
 * <h2>解决什么</h2>
 * <p>编程式注册要写 {@code registrar.register(ToolSpec.of(...), this::method)}，
 * 参数 schema、副作用等级、审批策略全都堆在一段与业务方法分离的代码里。
 * 本扫描器让这些治理信息回到方法本身：</p>
 * <pre>
 * &#64;Tool(desc = "退款", value = "refundOrder", domains = {"admin"},
 *           effect = Tool.Effect.WRITE, approval = Tool.Approval.ALWAYS,
 *           approvalReason = "退款需人工确认")
 * public String refundOrder(&#64;ToolParam("订单号") String orderNo,
 *                           &#64;ToolParam("退款金额，单位：元") BigDecimal amount) { ... }
 * </pre>
 * <p>方法签名即参数 schema，注解即治理策略，方法体即执行逻辑 —— 三者不再分离。</p>
 *
 * <h2>什么时候扫</h2>
 * <p>在 {@code ToolInstanceClient} 装配时执行（见 {@link ToolInstanceAutoConfiguration}），
 * 早于心跳启动。扫描只取"类型"做筛选，只对命中的 Bean 触发实例化，不做全容器提前初始化。</p>
 *
 * <h2>失败策略</h2>
 * <p>参数名解析不出、工具名重复、{@code desc} 缺失 —— 全部<b>启动期直接抛异常</b>。
 * 工具声明错了却等到模型来调用才发现，排查成本远高于启动失败。</p>
 *
 * @author zzkingcc
 */
public final class AnnotatedToolScanner {

    private static final Logger log = LoggerFactory.getLogger(AnnotatedToolScanner.class);

    /** 嵌套对象展开的最大层数（防循环引用与巨型 schema） */
    private static final int MAX_DEPTH = 4;

    private final ListableBeanFactory beanFactory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ParameterNameDiscoverer parameterNames = new DefaultParameterNameDiscoverer();

    public AnnotatedToolScanner(ListableBeanFactory beanFactory) {
        this.beanFactory = Objects.requireNonNull(beanFactory, "beanFactory 不能为空");
    }

    /**
     * 扫描容器内所有 Bean，把标注了 {@link Tool} 的方法注册给 registrar。
     *
     * @return 注册成功的工具数量
     */
    public int registerTo(ToolRegistrar registrar) {
        Map<String, String> owners = new LinkedHashMap<>();     // 工具名 → 声明位置（重名检测用）
        List<String> registered = new ArrayList<>();

        for (String beanName : beanFactory.getBeanNamesForType(Object.class, true, false)) {
            Class<?> type = beanFactory.getType(beanName, false);
            if (type == null || isInfrastructure(type)) {
                continue;
            }
            Class<?> userClass = ClassUtils.getUserClass(type);

            List<Method> methods = annotatedMethods(userClass);
            if (methods.isEmpty()) {
                continue;
            }
            // 只有命中注解才取实例：避免为扫描而提前初始化整个容器
            Object bean = beanFactory.getBean(beanName);

            for (Method method : methods) {
                Tool annotation = method.getAnnotation(Tool.class);
                Method target = ClassUtils.getMostSpecificMethod(method, userClass);
                String toolName = annotation.value().isBlank() ? method.getName() : annotation.value().trim();
                String where = userClass.getName() + "#" + method.getName();

                String previous = owners.put(toolName, where);
                if (previous != null) {
                    throw new IllegalStateException("工具名重复：" + toolName
                            + " 同时声明于 " + previous + " 与 " + where
                            + "。工具名全局唯一（同名即同一工具的多个副本），请修改 @Tool 的 value");
                }

                Binding binding = binding(target, where);
                registrar.register(spec(annotation, userClass, target, binding, where), handler(bean, binding));
                registered.add(toolName);
            }
        }

        if (!registered.isEmpty()) {
            log.info("[工具实例] 注解扫描注册 {} 个工具 {}", registered.size(), registered);
        }
        return registered.size();
    }

    // ==================== 扫描 ====================

    /**
     * 收集该类（及其接口）上带 {@link Tool} 的方法。
     *
     * <p>接口也要扫：JDK 动态代理下注解只留在接口方法上，实现类的方法拿不到。</p>
     */
    private List<Method> annotatedMethods(Class<?> userClass) {
        List<Method> raw = new ArrayList<>(List.of(userClass.getMethods()));
        for (Class<?> itf : ClassUtils.getAllInterfacesForClass(userClass)) {
            raw.addAll(List.of(itf.getMethods()));
        }

        Set<String> seen = new LinkedHashSet<>();
        List<Method> methods = new ArrayList<>();
        for (Method method : raw) {
            if (method.isSynthetic() || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            if (method.getAnnotation(Tool.class) == null) {
                continue;
            }
            if (seen.add(signature(method))) {
                methods.add(method);
            }
        }
        return methods;
    }

    private static String signature(Method method) {
        StringBuilder sb = new StringBuilder(method.getName());
        for (Class<?> paramType : method.getParameterTypes()) {
            sb.append('|').append(paramType.getName());
        }
        return sb.toString();
    }

    /**
     * 跳过 Spring 自身的基础设施 Bean：扫它们只会带来噪音，且可能触发不该触发的初始化。
     */
    private static boolean isInfrastructure(Class<?> type) {
        String name = type.getName();
        // 只排除框架自身：扫它们只会带来噪音，且可能触发不该触发的初始化。
        // 不排除 Stringer 的包 —— SDK 自己的 Bean 上不会有 @Tool，按注解命中即可。
        return name.startsWith("org.springframework.") || name.startsWith("java.");
    }

    // ==================== 声明（ToolSpec） ====================

    private ToolSpec spec(Tool annotation, Class<?> userClass, Method method, Binding binding, String where) {
        if (annotation.desc().isBlank()) {
            throw new IllegalStateException("@Tool.desc 必填（它是模型判断何时调用的唯一依据）：" + where);
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (ParamMeta param : binding.params()) {
            properties.put(param.name(), schema(param.type(), param.annotation(), 0,
                    param.name(), binding.overrides()));
            if (param.required()) {
                required.add(param.name());
            }
        }

        boolean requiresApproval = annotation.approval() == Tool.Approval.ALWAYS;

        if (annotation.effect() == Tool.Effect.DESTRUCTIVE && !requiresApproval) {
            log.warn("[工具实例] {} 声明为 DESTRUCTIVE 但未配置审批：破坏性操作应当在被执行前中断。"
                            + "请补 @Tool(approval = Tool.Approval.ALWAYS, approvalReason = ...)",
                    where);
        }

        // 可用域：方法级 @Tool(domains=...) 优先；留空时回落到类级 @ToolDomains
        List<String> raw = new ArrayList<>();
        if (annotation.domains().length > 0) {
            raw.addAll(List.of(annotation.domains()));
        } else {
            ToolDomains classLevel = userClass.getAnnotation(ToolDomains.class);
            if (classLevel != null) {
                raw.addAll(List.of(classLevel.value()));
            }
        }
        List<String> profiles = raw.stream()
                .map(String::trim)
                .filter(p -> !p.isBlank())
                .distinct()
                .toList();

        return new ToolSpec(
                annotation.value().isBlank() ? method.getName() : annotation.value().trim(),
                annotation.desc(),
                "default",
                "1.0.0",
                profiles,
                annotation.effect().name(),
                true,
                true,
                requiresApproval,
                requiresApproval ? "ALWAYS" : "",
                annotation.approvalReason(),
                ToolSpec.schema(properties, required.toArray(new String[0])));
    }

    // ==================== 参数绑定 ====================

    /**
     * 解析方法参数：名字、类型、是否必填。
     *
     * <p>参数名优先取 {@code @ToolParam.name}，其次取编译期元数据 / 调试信息。
     * 取不到就直接失败 —— 用 {@code arg0} 当参数名注册出去，模型会拿着错误的 key 调参，
     * 这种错在联调时极难定位。</p>
     */
    private Binding binding(Method method, String where) {
        Parameter[] parameters = method.getParameters();
        String[] discovered = parameterNames.getParameterNames(method);

        List<ParamMeta> params = new ArrayList<>(parameters.length);
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            ToolParam annotation = parameter.getAnnotation(ToolParam.class);
            String name = annotation != null && !annotation.name().isBlank()
                    ? annotation.name().trim()
                    : (discovered != null && i < discovered.length ? discovered[i] : null);

            if (name == null || name.isBlank()) {
                throw new IllegalStateException("无法解析参数名：" + where + " 的第 " + (i + 1) + " 个参数（"
                        + parameter.getType().getSimpleName() + "）。"
                        + "请用 @ToolParam(name = \"...\") 显式命名，或给编译器加 -parameters"
                        + "（Spring Boot 父 pom 默认已开启，普通 Maven 工程需自行配置）");
            }
            params.add(new ParamMeta(name, parameter.getParameterizedType(), annotation == null || annotation.required(), annotation));
        }
        return new Binding(method, params, ParamSchemaResolver.ParamOverrides.of(method));
    }

    // ==================== 执行 ====================

    /**
     * 生成一个执行体：参数 JSON → 反射调用 → 结果文本。
     */
    private ToolHandler handler(Object bean, Binding binding) {
        Method method = binding.method();
        ReflectionUtils.makeAccessible(method);

        return argumentsJson -> {
            JsonNode root = parse(argumentsJson);
            Object[] args = new Object[binding.params().size()];
            for (int i = 0; i < args.length; i++) {
                ParamMeta meta = binding.params().get(i);
                JsonNode value = root.get(meta.name());
                if (value == null || value.isNull()) {
                    if (meta.required()) {
                        // 参数缺失本身就是一次正常的工具返回，抛异常会被 SDK 包装成失败原因回喂模型
                        throw new IllegalArgumentException("缺少必填参数: " + meta.name());
                    }
                    args[i] = defaultValue(meta.type());
                } else {
                    args[i] = convert(value, meta.type());
                }
            }

            try {
                return toText(method.invoke(bean, args));
            } catch (InvocationTargetException e) {
                Throwable cause = e.getTargetException();
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                throw new IllegalStateException(cause);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("工具方法不可访问: " + method, e);
            }
        };
    }

    private JsonNode parse(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(argumentsJson);
        } catch (Exception e) {
            // 模型偶尔会吐出非法 JSON：按空参处理，让业务方法自己在缺失参数上给出结果
            log.warn("[工具实例] 工具参数不是合法 JSON，按空参数处理: {}", argumentsJson);
            return mapper.createObjectNode();
        }
    }

    private Object convert(JsonNode value, Type type) {
        if (type == String.class) {
            return value.isValueNode() ? value.asText() : value.toString();
        }
        return mapper.convertValue(value, mapper.getTypeFactory().constructType(type));
    }

    private static Object defaultValue(Type type) {
        if (!(type instanceof Class<?> c) || !c.isPrimitive()) {
            return null;
        }
        if (c == boolean.class) {
            return false;
        }
        if (c == char.class) {
            return '\0';
        }
        if (c == float.class) {
            return 0F;
        }
        if (c == double.class) {
            return 0D;
        }
        if (c == long.class) {
            return 0L;
        }
        if (c == int.class) {
            return 0;
        }
        if (c == short.class) {
            return (short) 0;
        }
        if (c == byte.class) {
            return (byte) 0;
        }
        return null;
    }

    /**
     * 返回值转文本：字符串原样返回（绝大多数工具返回的是自然语言结果），其余序列化成 JSON。
     */
    private String toText(Object result) {
        if (result == null) {
            return "";
        }
        if (result instanceof CharSequence text) {
            return text.toString();
        }
        try {
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            return String.valueOf(result);
        }
    }

    // ==================== JSON Schema 推断 ====================

    /**
     * 由方法签名 / 字段类型推断 JSON Schema。
     *
     * <p>语义分两处取：{@code @ToolParam} 给说明（唯一来源），{@code @ToolAdvanced} 给
     * 示例 / 枚举白名单 / 敏感 —— 后者的解析与说明拼装<b>整体复用服务端同一份实现</b>
     * （{@link ParamSchemaResolver.ParamOverrides}），保证同一段工具代码在两侧得到同一个 schema。</p>
     */
    private Map<String, Object> schema(Type type, ToolParam annotation, int depth,
                                       String name, ParamSchemaResolver.ParamOverrides overrides) {
        Map<String, Object> node = new LinkedHashMap<>();

        if (type instanceof ParameterizedType parameterized) {
            Type raw = parameterized.getRawType();
            Type[] arguments = parameterized.getActualTypeArguments();
            if (raw instanceof Class<?> rawClass && Collection.class.isAssignableFrom(rawClass)) {
                node.put("type", "array");
                node.put("items", arguments.length > 0
                        ? schema(arguments[0], null, depth + 1, null, overrides)
                        : Map.of("type", "string"));
            } else if (raw instanceof Class<?> rawClass) {
                node.put("type", "object");
                if (depth < MAX_DEPTH && !isSimple(rawClass)) {
                    node.putAll(objectOf(rawClass, depth, overrides));
                }
            }
        } else if (type instanceof Class<?> c) {
            String jsonType = jsonTypeOf(c);
            if (jsonType != null) {
                node.put("type", jsonType);
            } else if (c.isEnum()) {
                node.put("type", "string");
                node.put("enum", enumValues(c));
            } else if (c.isArray()) {
                node.put("type", "array");
                node.put("items", schema(c.getComponentType(), null, depth + 1, null, overrides));
            } else if (Collection.class.isAssignableFrom(c)) {
                node.put("type", "array");
                node.put("items", Map.of("type", "string"));
            } else {
                node.put("type", "object");
                if (depth < MAX_DEPTH) {
                    node.putAll(objectOf(c, depth, overrides));
                }
            }
        } else {
            node.put("type", "object");
        }

        node.put("description", overrides.describe(name, annotation == null ? null : annotation.value()));

        String example = overrides.exampleOf(name);
        if (!example.isBlank()) {
            node.put("example", example);
        }
        List<String> allowValues = overrides.allowValuesOf(name);
        if (!allowValues.isEmpty()) {
            node.put("type", "string");
            node.put("enum", allowValues);
        }
        if (overrides.isSensitive(name)) {
            // 服务端 ToolParamSchema 认这个键，脱敏清单必须跟着 schema 一起上报
            node.put("x-sensitive", true);
        }
        return node;
    }

    /** 展开一个 POJO 的字段（含父类），递归受 {@link #MAX_DEPTH} 限制 */
    private Map<String, Object> objectOf(Class<?> type, int depth, ParamSchemaResolver.ParamOverrides overrides) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (field.isSynthetic() || Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) {
                    continue;
                }
                ToolParam annotation = field.getAnnotation(ToolParam.class);
                String name = annotation != null && !annotation.name().isBlank()
                        ? annotation.name().trim()
                        : field.getName();
                properties.put(name, schema(field.getGenericType(), annotation, depth + 1, name, overrides));
                // 嵌套字段只有"显式标注必填且是原始类型"才进 required：默认值策略交给业务方法，schema 不该越权
                if (annotation != null && annotation.required() && field.getType().isPrimitive()) {
                    required.add(name);
                }
            }
        }

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("properties", properties);
        if (!required.isEmpty()) {
            node.put("required", required);
        }
        return node;
    }

    private static boolean isSimple(Class<?> type) {
        return jsonTypeOf(type) != null || type.isEnum();
    }

    private static String jsonTypeOf(Class<?> type) {
        if (type == String.class || type == Character.class || type == char.class
                || CharSequence.class.isAssignableFrom(type)
                || type == UUID.class || Temporal.class.isAssignableFrom(type) || Date.class.isAssignableFrom(type)) {
            return "string";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class
                || type == short.class || type == Short.class || type == byte.class || type == Byte.class
                || type == BigInteger.class) {
            return "integer";
        }
        if (type == float.class || type == Float.class || type == double.class || type == Double.class
                || type == BigDecimal.class || Number.class.isAssignableFrom(type)) {
            return "number";
        }
        return null;
    }

    private static List<String> enumValues(Class<?> type) {
        Object[] constants = type.getEnumConstants();
        List<String> values = new ArrayList<>(constants.length);
        for (Object constant : constants) {
            values.add(constant.toString());
        }
        return values;
    }

    // ==================== 内部数据 ====================

    /** 一个方法参数：名字、泛型类型、是否必填、语义注解 */
    private record ParamMeta(String name, Type type, boolean required, ToolParam annotation) {
    }

    /** 一个工具方法：可调用的 Method + 解析好的参数列表 */
    /**
     * 一个工具方法：可调用的 Method + 解析好的参数列表 + 方法级 {@code @ToolAdvanced} 的索引视图
     * （示例 / 白名单 / 敏感；与服务端共用同一份解析）
     */
    private record Binding(Method method, List<ParamMeta> params,
                           ParamSchemaResolver.ParamOverrides overrides) {
    }
}
