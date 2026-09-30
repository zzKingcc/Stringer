package com.zzkingcc.stringer.server.prompt;

import com.zzkingcc.stringer.api.tool.ToolDescriptor;
import com.zzkingcc.stringer.runtime.tool.ToolRouter;
import com.zzkingcc.stringer.server.config.PromptProperties;
import com.zzkingcc.stringer.server.settings.DomainSettings;
import com.zzkingcc.stringer.server.settings.DomainSettingsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 提示词 ↔ 工具可见性 一致性自检（启动期跑，<b>只提醒、不阻断</b>）。
 *
 * <p>要挡的是什么：域同时绑定【工具集 + 系统提示词】，而两者分处两个地方维护
 * （工具声明在 {@code @Tool}，提示词在管控台 {@code prompts.json} / yaml），
 * 于是很容易写出"提示词里点名了某个工具、但它在当前域不可见"。
 * 后果是模型被告知有能力却调不到，要么反复失败、要么拿近义工具硬凑。
 * 这类问题<b>不抛异常、不进错误日志、HTTP 依旧 200</b>，只能靠人工比对发现 ——
 * 正是自检该覆盖的那一类。</p>
 *
 * <p>边界（刻意保守）：只认"提示词里出现的<b>工具名</b>"。中文提示词通常用业务语言描述能力
 * （"查订单"）而不写 {@code queryOrder}，那种情形本自检覆盖不到 ——
 * <b>宁可漏报，也不误报</b>：误报会让人不再信任这条告警。</p>
 *
 * @author zzkingcc
 */
public class PromptToolConsistencyAudit {

    private static final Logger log = LoggerFactory.getLogger(PromptToolConsistencyAudit.class);

    /** 单次最多打印多少条明细 —— 自检是为了提醒，不是把启动日志刷成噪音 */
    private static final int MAX_DETAILS = 10;

    private final ToolRouter toolRouter;
    private final DomainSettingsStore settingsStore;
    private final DomainSystemPromptResolver resolver;

    public PromptToolConsistencyAudit(ToolRouter toolRouter,
                                      DomainSettingsStore settingsStore,
                                      PromptProperties promptProperties) {
        this.toolRouter = toolRouter;
        this.settingsStore = settingsStore;
        // 复用"管控台优先 / yaml 兜底"那套解析；preview 只拼文本，不发任何日志
        this.resolver = new DomainSystemPromptResolver(settingsStore, promptProperties);
    }

    /**
     * 跑一次自检并输出结论。
     *
     * <p>任何异常都只降级为一条 WARN —— 自检绝不能把服务端拉不起来。</p>
     */
    public void audit() {
        try {
            List<ToolDescriptor> tools = toolRouter.getToolDescriptors();
            // 域排序后再比较：消息可复现，日志 diff 才有意义
            Set<String> domains = new TreeSet<>(toolRouter.getKnownProfiles());

            if (tools.isEmpty() || domains.isEmpty()) {
                log.info("[启动自检] 提示词与工具可见性检查跳过：工具 {} 个、域 {} 个。"
                                + "（服务端不自带示例工具，未注册任何工具时属正常初始态）",
                        tools.size(), domains.size());
                return;
            }

            DomainSettings settings = settingsStore.load();
            String base = resolver.preview(null, settings);
            Map<String, String> promptByDomain = new LinkedHashMap<>();
            for (String domain : domains) {
                promptByDomain.put(domain, resolver.preview(domain, settings));
            }

            List<String> mismatches = findMismatches(tools, domains, promptByDomain, base);
            if (mismatches.isEmpty()) {
                log.info("[启动自检] 提示词与工具可见性一致（域 {} 个、工具 {} 个）",
                        domains.size(), tools.size());
            } else {
                log.warn("[启动自检] 提示词点名的工具在对应域不可见，共 {} 项 —— "
                                + "模型会被告知有能力却调不到。修法二选一：把工具声明到这些域"
                                + "（@Tool(domains = {...}) 里加域名，或显式写 \"*\"），"
                                + "或从提示词里删掉该工具名",
                        mismatches.size());
                for (int i = 0; i < Math.min(mismatches.size(), MAX_DETAILS); i++) {
                    log.warn("[启动自检]   {}. {}", i + 1, mismatches.get(i));
                }
                if (mismatches.size() > MAX_DETAILS) {
                    log.warn("[启动自检]   …另有 {} 项（可在管控台「域空间」逐个核对域归属与提示词）",
                            mismatches.size() - MAX_DETAILS);
                }
            }

            debugUnmentioned(tools, domains, promptByDomain);
        } catch (Exception e) {
            log.warn("[启动自检] 提示词与工具可见性检查失败（不影响启动）：{}", e.getMessage());
        }
    }

    /**
     * 找出"提示词点名了、但该域不可见"的工具。
     *
     * <p>分两类，因为修法不同：</p>
     * <ol>
     *   <li><b>公共基线</b>点名：基线所有域共享，所以要把它在哪些域不可见<b>逐个列全</b>；</li>
     *   <li><b>域差异片段</b>点名：只影响该域，属自相矛盾（一边给这个域加提示、一边不给它工具），更要紧。</li>
     * </ol>
     */
    static List<String> findMismatches(List<ToolDescriptor> tools,
                                       Set<String> domains,
                                       Map<String, String> promptByDomain,
                                       String base) {
        List<String> out = new ArrayList<>();

        for (ToolDescriptor tool : tools) {
            if (!mentions(base, tool.name())) {
                continue;
            }
            List<String> invisible = new ArrayList<>();
            for (String domain : domains) {
                if (!tool.visibleIn(domain)) {
                    invisible.add(domain);
                }
            }
            if (!invisible.isEmpty()) {
                out.add("公共基线点名了工具 " + tool.name() + "，但它在域 " + invisible + " 不可见");
            }
        }

        for (String domain : domains) {
            String prompt = promptByDomain.get(domain);
            for (ToolDescriptor tool : tools) {
                // 基线已经处理过的不重复报；域提示词 = 基线（+ 域差异），
                // 所以"基线没有、域提示词有"才说明它出自该域的差异片段
                if (tool.visibleIn(domain)
                        || mentions(base, tool.name())
                        || !mentions(prompt, tool.name())) {
                    continue;
                }
                out.add("域 " + domain + " 的提示词点名了工具 " + tool.name() + "，但它在该域不可见");
            }
        }
        return out;
    }

    /**
     * 反向情形只记 DEBUG：可见但提示词没点名，通常不是问题（工具自带的 {@code desc}
     * 已经告诉模型"何时调用"），只在排查"模型不知道有这个能力"时才有用。
     */
    private static void debugUnmentioned(List<ToolDescriptor> tools,
                                         Set<String> domains,
                                         Map<String, String> promptByDomain) {
        if (!log.isDebugEnabled()) {
            return;
        }
        for (String domain : domains) {
            String prompt = promptByDomain.get(domain);
            List<String> silent = new ArrayList<>();
            for (ToolDescriptor tool : tools) {
                if (tool.visibleIn(domain) && !mentions(prompt, tool.name())) {
                    silent.add(tool.name());
                }
            }
            if (!silent.isEmpty()) {
                log.debug("[启动自检] 域 {} 有 {} 个可见工具未在提示词里点名：{}",
                        domain, silent.size(), silent);
            }
        }
    }

    /**
     * 提示词里是否<b>点名</b>了该工具。
     *
     * <p>按词边界匹配，不用 {@code contains}：工具名是标识符，
     * {@code queryOrderDetail} 里出现 {@code queryOrder} 不算点名。</p>
     */
    static boolean mentions(String text, String name) {
        if (text == null || text.isBlank() || name == null || name.isBlank()) {
            return false;
        }
        int from = 0;
        while (true) {
            int at = text.indexOf(name, from);
            if (at < 0) {
                return false;
            }
            int end = at + name.length();
            boolean leftOk = at == 0 || !isWordChar(text.charAt(at - 1));
            boolean rightOk = end >= text.length() || !isWordChar(text.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            from = at + 1;
        }
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
