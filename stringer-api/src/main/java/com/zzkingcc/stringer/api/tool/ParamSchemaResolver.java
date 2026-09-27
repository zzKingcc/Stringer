package com.zzkingcc.stringer.api.tool;

import com.zzkingcc.stringer.api.annotation.ToolParam;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 方法签名 + 注解 → 参数结构（含 DTO 递归展开）。
 *
 * <p>为什么单独抽出来：<b>服务端（本地 Bean 工具）与工具实例 SDK 各有一份扫描器</b>，
 * 两边如果各自实现一遍，同一段工具代码搬到另一侧就会得到不同的参数 schema ——
 * 这正好违背"工具代码在两侧搬迁不用改一个字"的承诺。这里就是那份<b>共用的真相</b>。</p>
 *
 * <p>只产出平台自己的中间结构（{@link ToolDescriptor.Param} 树），<b>不依赖任何模型框架</b>；
 * 两端各自把它转成自己需要的 schema（服务端转 langchain4j Schema，工具实例转上报 JSON）。</p>
 *
 * <p>支持两种载体：形参前的 {@code @ToolParam}，以及参数 DTO 字段上的 {@code @ToolParam}；
 * 形参优先（就近覆盖）。</p>
 *
 * @author zzkingcc
 */
public final class ParamSchemaResolver {

    /**
     * 嵌套展开的深度上限 —— 超过即停止展开。
     * 深结构会让模型难以正确构造，循环引用更是会在扫描期爆栈，两者都必须挡住。
     */
    public static final int MAX_DEPTH = 5;

    private ParamSchemaResolver() {
    }

    /**
     * 解析一个方法的全部参数。
     */
    public static List<ToolDescriptor.Param> resolve(Method method) {
        Parameter[] parameters = method.getParameters();
        List<ToolDescriptor.Param> out = new ArrayList<>(parameters.length);
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            ToolParam annotation = parameter.getAnnotation(ToolParam.class);
            String name = annotation != null && !annotation.name().isBlank()
                    ? annotation.name().trim()
                    : fallbackParamName(parameter, i);
            // Optional<T> 天然可选；其余按注解，缺省必填
            boolean required = (annotation == null || annotation.required())
                    && parameter.getType() != Optional.class;
            out.add(resolveOne(name, parameter.getParameterizedType(), annotation,
                    required, 0, new HashSet<>()));
        }
        return List.copyOf(out);
    }

    /** 递归解析一个参数（可能是 DTO） */
    private static ToolDescriptor.Param resolveOne(String name,
                                                   Type generic,
                                                   ToolParam annotation,
                                                   boolean required,
                                                   int depth,
                                                   Set<Class<?>> visiting) {
        Class<?> raw = rawTypeOf(generic);

        List<String> allowValues = new ArrayList<>();
        if (annotation != null && annotation.allowValues().length > 0) {
            allowValues.addAll(Arrays.asList(annotation.allowValues()));
        } else if (raw != null && raw.isEnum()) {
            allowValues.addAll(enumValues(raw));
        }

        String type = allowValues.isEmpty() ? jsonType(raw) : "enum";
        String description = descriptionOf(annotation);
        String example = annotation == null ? "" : annotation.example();
        boolean sensitive = annotation != null && annotation.sensitive();

        List<ToolDescriptor.Param> children = List.of();
        if ("object".equals(type)) {
            children = propertiesOf(raw, depth, visiting);
            if (children.isEmpty()) {
                // 展开不出来只有两种原因：到深度上限了，或撞上循环引用。
                // 与其让模型以为这是个自由对象，不如把原因写进说明里。
                description = description + "（" + (depth >= MAX_DEPTH
                        ? "嵌套超过 " + MAX_DEPTH + " 层，未展开"
                        : "未展开出字段（可能是循环引用或字段全被跳过）") + "）";
            }
        }

        return new ToolDescriptor.Param(name, type, description, required,
                List.copyOf(allowValues), example, sensitive, children);
    }

    /**
     * DTO 的子字段：record 走组件（名字可靠），普通类走声明字段。
     *
     * @return 子字段列表；空列表表示"未展开"
     */
    private static List<ToolDescriptor.Param> propertiesOf(Class<?> raw, int depth, Set<Class<?>> visiting) {
        if (raw == null || depth >= MAX_DEPTH || !visiting.add(raw)) {
            return List.of();
        }
        try {
            if (raw.isRecord()) {
                List<ToolDescriptor.Param> out = new ArrayList<>();
                for (RecordComponent component : raw.getRecordComponents()) {
                    out.add(fieldParam(component.getName(), component.getGenericType(),
                            component.getAnnotation(ToolParam.class), depth, visiting));
                }
                return List.copyOf(out);
            }

            List<ToolDescriptor.Param> out = new ArrayList<>();
            for (Field field : raw.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (field.isSynthetic() || Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) {
                    continue;
                }
                out.add(fieldParam(field.getName(), field.getGenericType(),
                        field.getAnnotation(ToolParam.class), depth, visiting));
            }
            return List.copyOf(out);
        } finally {
            visiting.remove(raw);
        }
    }

    private static ToolDescriptor.Param fieldParam(String fallbackName,
                                                   Type generic,
                                                   ToolParam annotation,
                                                   int depth,
                                                   Set<Class<?>> visiting) {
        String name = annotation != null && !annotation.name().isBlank()
                ? annotation.name().trim()
                : fallbackName;
        boolean required = annotation == null || annotation.required();
        return resolveOne(name, generic, annotation, required, depth + 1, visiting);
    }

    /** 参数说明：新写法 {@code value} 优先，回落到旧的 {@code description} */
    private static String descriptionOf(ToolParam annotation) {
        if (annotation == null) {
            return "（未描述）";
        }
        if (!annotation.value().isBlank()) {
            return annotation.value().trim();
        }
        if (!annotation.description().isBlank()) {
            return annotation.description().trim();
        }
        return "（未描述）";
    }

    private static String fallbackParamName(Parameter parameter, int index) {
        String reflected = parameter.getName();
        // 未开启 -parameters 编译参数时形参名会是 arg0 / arg1
        if (reflected == null || reflected.isBlank() || reflected.startsWith("arg")) {
            return "param" + (index + 1);
        }
        return reflected;
    }

    private static List<String> enumValues(Class<?> type) {
        return Arrays.stream(type.getEnumConstants()).map(Object::toString).toList();
    }

    /**
     * Java 类型 → JSON Schema 类型名。
     *
     * <p>注意与旧实现的差异：<b>复杂对象现在返回 {@code object}</b> 而不是 {@code string} ——
     * 旧实现把 DTO 当字符串，模型产出字符串后反序列化必然失败，等于工具拿不到参数。</p>
     */
    private static String jsonType(Class<?> type) {
        if (type == null) {
            return "object";
        }
        if (CharSequence.class.isAssignableFrom(type) || type == Character.class || type == char.class
                || type == UUID.class || Temporal.class.isAssignableFrom(type)
                || Date.class.isAssignableFrom(type) || type == URI.class || type == URL.class) {
            return "string";
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class
                || type == short.class || type == Short.class || type == byte.class || type == Byte.class
                || type == BigInteger.class) {
            return "integer";
        }
        if (type == double.class || type == Double.class || type == float.class || type == Float.class
                || type == BigDecimal.class || Number.class.isAssignableFrom(type)) {
            return "number";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (type.isEnum()) {
            return "enum";
        }
        if (type.isArray() || Collection.class.isAssignableFrom(type)) {
            return "array";
        }
        // DTO / Map / 其它复合类型
        return "object";
    }

    /** 拿裸类型：Class 直接返回；ParameterizedType 取 rawType；数组取元素类型 */
    private static Class<?> rawTypeOf(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized) {
            Type raw = parameterized.getRawType();
            return raw instanceof Class<?> clazz ? clazz : null;
        }
        if (type instanceof GenericArrayType array) {
            Type component = array.getGenericComponentType();
            return component instanceof Class<?> clazz ? clazz : null;
        }
        return null;
    }

    /** 参数说明缺失的提示（给管控台与启动日志用） */
    public static boolean hasDescription(ToolDescriptor.Param param) {
        return param != null
                && param.description() != null
                && !param.description().isBlank()
                && !param.description().startsWith("（未描述）");
    }

    /** 递归统计"缺说明的参数"数量（含嵌套字段） */
    public static int countMissingDescription(List<ToolDescriptor.Param> params) {
        int missing = 0;
        for (ToolDescriptor.Param param : params) {
            if (!hasDescription(param)) {
                missing++;
            }
            missing += countMissingDescription(param.properties());
        }
        return missing;
    }
}
