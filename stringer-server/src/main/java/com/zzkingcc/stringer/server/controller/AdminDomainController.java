package com.zzkingcc.stringer.server.controller;

import com.zzkingcc.stringer.api.agent.Domains;
import com.zzkingcc.stringer.api.code.ErrorCode;
import com.zzkingcc.stringer.api.support.KbIndexes;
import com.zzkingcc.stringer.common.exception.BaseException;
import com.zzkingcc.stringer.common.exception.KnowledgeBaseException;
import com.zzkingcc.stringer.runtime.domain.DomainRegistry;
import com.zzkingcc.stringer.runtime.tool.ToolRegistry;
import com.zzkingcc.stringer.server.knowledge.DomainChannelProvider;
import com.zzkingcc.stringer.server.knowledge.KnowledgeBaseService;
import com.zzkingcc.stringer.server.model.ModelProfileRegistry;
import com.zzkingcc.stringer.server.settings.DomainSettingsStore;
import com.zzkingcc.stringer.server.settings.DomainStore;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理面：域的创建、可调用性切换与删除。
 *
 * <p>域有两种角色，由 {@code callable} 显式区分：</p>
 * <ul>
 *   <li><b>可调用单元</b>（{@code callable=true}）：能作为入口被调用，是"这一个 AI 切片"的入口；</li>
 *   <li><b>装配节点</b>（{@code callable=false}）：只负责把工具 / 提示词 / 模型绑定 / 知识传给后代，不能直接调。</li>
 * </ul>
 *
 * <p>为什么必须显式：可调用性曾经由"是否登记过"隐式决定，于是父域也能当入口；而"有没有子域"
 * 是随时会变的派生事实 —— 拿它当判据会在新增子域时静默改变调用方的可用性。</p>
 *
 * <p>删除的边界：<b>只有根域不可删</b>。删除是<b>递归</b>的 —— 该域的全部子孙一并带走，
 * 不向上提升层级；删除前会先删知识库索引，删不干净就拒绝删域。</p>
 *
 * <p>路径在 {@code /admin/**} 之下，鉴权由凭证拦截器统一处理。</p>
 *
 * @author zzkingcc
 */
@RestController
public class AdminDomainController {

    private static final Logger log = LoggerFactory.getLogger(AdminDomainController.class);

    private final DomainRegistry domainRegistry;
    private final DomainStore domainStore;
    private final KnowledgeBaseService knowledgeBase;
    private final DomainSettingsStore domainSettingsStore;
    private final ModelProfileRegistry modelProfileRegistry;
    private final ToolRegistry toolRegistry;
    private final DomainChannelProvider domainChannelProvider;

    public AdminDomainController(DomainRegistry domainRegistry,
                                 DomainStore domainStore,
                                 KnowledgeBaseService knowledgeBase,
                                 DomainSettingsStore domainSettingsStore,
                                 ModelProfileRegistry modelProfileRegistry,
                                 ToolRegistry toolRegistry,
                                 DomainChannelProvider domainChannelProvider) {
        this.domainRegistry = domainRegistry;
        this.domainStore = domainStore;
        this.knowledgeBase = knowledgeBase;
        this.domainSettingsStore = domainSettingsStore;
        this.modelProfileRegistry = modelProfileRegistry;
        this.toolRegistry = toolRegistry;
        this.domainChannelProvider = domainChannelProvider;
    }

    /**
     * 创建域（完整路径；链上缺失的祖先一并建出，祖先一律是装配节点）。
     *
     * @param body {@code id} 域标识，须为从 {@code default} 出发的完整路径，
     *             单段只允许字母 / 数字 / 下划线 / 连字符，自身链上不得重复段；
     *             {@code callable} 省略时按"可调用单元"创建
     */
    @PostMapping("/admin/domains")
    public Map<String, Object> create(@RequestBody CreateBody body) {
        if (body == null || body.getId() == null || body.getId().isBlank()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "域标识不能为空");
        }
        boolean callable = body.getCallable() == null || body.getCallable();
        DomainRegistry.CreateResult created = domainRegistry.create(body.getId(), callable);
        if (!created.created()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, created.reason());
        }
        domainStore.saveDeclarations(domainRegistry.manualConfig());
        log.info("[域管理] 已创建域 {}（来源 MANUAL，可调用={}）", body.getId().trim(), callable);
        return view("created", body.getId().trim(), List.of());
    }

    /**
     * 切换某个域的可调用性。
     *
     * <p>只影响这一个域，不向下传播；切回可调用后，以它为入口的调用立即恢复。</p>
     *
     * @param body {@code callable} 目标值
     */
    @PutMapping("/admin/domains/{id}/callable")
    public Map<String, Object> setCallable(@PathVariable("id") String id,
                                           @RequestBody(required = false) CallableBody body) {
        if (body == null || body.getCallable() == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "缺少 callable（true=可调用单元，false=装配节点）");
        }
        String normalized = Domains.normalize(id);
        if (!domainRegistry.setCallable(normalized, body.getCallable())) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "域不存在：" + normalized);
        }
        domainStore.saveDeclarations(domainRegistry.manualConfig());
        return view("callable", normalized, List.of());
    }

    /**
     * 删除域及其全部子孙（递归）。只有根域拒绝删除。
     *
     * <p>顺序是硬要求：<b>先删知识库索引与切片预览文件，再清理域属性，最后删域</b>。
     * 一域一索引，索引就是该域知识的唯一载体；域先没了，那些索引就再也没人认领
     * （检索不到、上传也无从指定）。索引没删干净就直接拒绝删域 —— 域一个没动。</p>
     *
     * <p>域属性（提示词 / 模型绑定 / 工具声明派生记录 / 检索器缓存）也一并清理：
     * 否则同路径域将来重建时会<b>静默复活</b>旧配置。</p>
     */
    @DeleteMapping("/admin/domains/{id}")
    public Map<String, Object> delete(@PathVariable("id") String id) {
        // 1) 先确认这个域存在且可删，避免白删一轮索引
        String normalized = Domains.normalize(id);
        if (!domainRegistry.contains(normalized)) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "域不存在：" + normalized);
        }
        if (domainRegistry.isBuiltin(normalized)) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "根域不可删除：" + normalized + "（它是整棵域树的起点）");
        }

        // 2) 本次会牵连的域 = 自身 + 全部子孙，索引按这个集合删
        List<String> affected = new ArrayList<>(domainRegistry.descendantsOf(normalized));
        affected.add(normalized);

        List<String> removedIndices;
        try {
            // 删索引前会先把这些域的切片预览文件（含文档全文）一并清掉
            removedIndices = knowledgeBase.deleteIndices(affected);
        } catch (KnowledgeBaseException e) {
            throw new BaseException(ErrorCode.KNOWLEDGE_BASE_ERROR,
                    "删除域 " + normalized + " 前清理知识库失败，已中止、域未删除：" + e.getMessage());
        }
        // 索引检索器缓存一并失效：删除的索引还在缓存里会被旧检索器继续命中
        List<String> affectedIndices = affected.stream().map(KbIndexes::nameOf).toList();
        domainChannelProvider.evictIndices(affectedIndices);

        // 3) 域属性一并清理：提示词 / 模型绑定 / 工具声明派生记录。
        //    不清理的话，同路径域将来重建时会"静默复活"旧配置。
        domainSettingsStore.removePrompts(affected);
        modelProfileRegistry.unbindDomains(affected);
        toolRegistry.forgetProfiles(affected);

        // 4) 以上都成功才动域
        DomainRegistry.DeleteResult deleted = domainRegistry.delete(normalized);
        if (!deleted.deleted()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, deleted.reason());
        }
        domainStore.saveDeclarations(domainRegistry.manualConfig());
        log.info("[域管理] 已删除域 {} 及其子孙 {}；连带删除知识库索引 {} 个（域 {}）",
                normalized, deleted.removed(), removedIndices.size(), removedIndices);
        return view("deleted", normalized, deleted.removed(), removedIndices);
    }

    /**
     * 统一返回体：动作 + 当前域全貌，便于管控台一次刷新。
     */
    private Map<String, Object> view(String action, String domainId, List<String> removed,
                                     List<String> removedIndices) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("action", action);
        result.put("domain", domainId);
        result.put("removed", removed);
        result.put("removedIndices", removedIndices);
        result.put("manualDomains", domainRegistry.manualIds());
        result.put("callableDomains", domainRegistry.callableIds());
        result.put("settingsFile", domainStore.filePath());
        return result;
    }

    private Map<String, Object> view(String action, String domainId, List<String> removed) {
        return view(action, domainId, removed, List.of());
    }

    /** 创建请求体 */
    @Data
    public static class CreateBody {
        /** 域标识 */
        private String id;

        /** 是否建为可调用单元；省略 = true */
        private Boolean callable;
    }

    /** 可调用性切换请求体 */
    @Data
    public static class CallableBody {
        /** true=可调用单元，false=装配节点 */
        private Boolean callable;
    }
}
