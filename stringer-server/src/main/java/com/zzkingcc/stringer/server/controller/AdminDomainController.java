package com.zzkingcc.stringer.server.controller;

import com.zzkingcc.stringer.api.agent.Domains;
import com.zzkingcc.stringer.api.code.ErrorCode;
import com.zzkingcc.stringer.common.exception.BaseException;
import com.zzkingcc.stringer.common.exception.KnowledgeBaseException;
import com.zzkingcc.stringer.runtime.domain.DomainRegistry;
import com.zzkingcc.stringer.server.knowledge.KnowledgeBaseService;
import com.zzkingcc.stringer.server.settings.DomainStore;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理面：域的创建与删除。
 *
 * <p>为什么需要它：域一直是由工具声明"顺带"产生的，于是"先建域、再启动应用"这件事做不到 ——
 * 一个还没有任何工具的新场景没法先把域建出来。有了创建入口，域成为可独立存在的实体。</p>
 *
 * <p>删除的边界：<b>只有根域不可删</b>。删除是<b>递归</b>的 —— 该域的全部子孙一并带走，
 * 不向上提升层级。三种来源（人工 / 工具派生）在删除规则上同级，不区分对待；
 * 派生域删后重启会随工具声明重建，那是它生命周期的固有结果，不另加限制。</p>
 *
 * <p>域标识是<b>从根域出发的完整路径</b>（{@code default.sales.order}）；链上缺失的祖先会一并建出，
 * 因此不会留下悬空节点。</p>
 *
 * <p>删除前会<b>先清知识库</b>：挂在待删域及其子孙上的文档一并删掉并逐个校验；
 * 没清干净就拒绝删域。顺序不能反 —— 域先没了，那些文档就成了检索不到的孤儿。</p>
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

    public AdminDomainController(DomainRegistry domainRegistry, DomainStore domainStore,
                                 KnowledgeBaseService knowledgeBase) {
        this.domainRegistry = domainRegistry;
        this.domainStore = domainStore;
        this.knowledgeBase = knowledgeBase;
    }

    /**
     * 创建域（完整路径；链上缺失的祖先一并建出）。
     *
     * @param body {@code id} 域标识，须为从 {@code default} 出发的完整路径，
     *             单段只允许字母 / 数字 / 下划线 / 连字符，自身链上不得重复段
     */
    @PostMapping("/admin/domains")
    public Map<String, Object> create(@RequestBody CreateBody body) {
        if (body == null || body.getId() == null || body.getId().isBlank()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "域标识不能为空");
        }
        DomainRegistry.CreateResult created = domainRegistry.create(body.getId());
        if (!created.created()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, created.reason());
        }
        domainStore.saveManualDomains(domainRegistry.manualIds());
        log.info("[域管理] 已创建域 {}（来源 MANUAL）", body.getId().trim());
        return view("created", body.getId().trim(), List.of());
    }

    /**
     * 删除域及其全部子孙（递归）。只有根域拒绝删除。
     *
     * <p>顺序是硬要求：<b>先清知识库并校验成功，再删域</b>。域删掉后，挂在它上面的文档
     * 就成了孤儿（检索不到、也没人认领）；反过来若先删域、后删文档失败，那些文档会永久失联。
     * 因此文档没清干净就直接拒绝删域 —— 域一个没动，不存在回滚问题。</p>
     */
    @DeleteMapping("/admin/domains/{id}")
    public Map<String, Object> delete(@PathVariable("id") String id) {
        // 1) 先确认这个域存在且可删，避免白清一轮知识库
        String normalized = Domains.normalize(id);
        if (!domainRegistry.contains(normalized)) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "域不存在：" + normalized);
        }
        if (domainRegistry.isBuiltin(normalized)) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "根域不可删除：" + normalized + "（它是整棵域树的起点）");
        }

        // 2) 本次会牵连的域 = 自身 + 全部子孙，知识库按这个集合清
        List<String> affected = new ArrayList<>(domainRegistry.descendantsOf(normalized));
        affected.add(normalized);

        List<String> removedDocs;
        try {
            removedDocs = knowledgeBase.deleteByDomains(affected);
        } catch (KnowledgeBaseException e) {
            throw new BaseException(ErrorCode.KNOWLEDGE_BASE_ERROR,
                    "删除域 " + normalized + " 前清理知识库失败，已中止、域未删除：" + e.getMessage());
        }

        // 3) 知识库清干净了才动域
        DomainRegistry.DeleteResult deleted = domainRegistry.delete(normalized);
        if (!deleted.deleted()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, deleted.reason());
        }
        domainStore.saveManualDomains(domainRegistry.manualIds());
        log.info("[域管理] 已删除域 {} 及其子孙 {}；连带清理知识库文档 {} 个",
                normalized, deleted.removed(), removedDocs.size());
        return view("deleted", normalized, deleted.removed(), removedDocs);
    }

    /**
     * 统一返回体：动作 + 当前域全貌，便于管控台一次刷新。
     */
    private Map<String, Object> view(String action, String domainId, List<String> removed,
                                     List<String> removedDocuments) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("action", action);
        result.put("domain", domainId);
        result.put("removed", removed);
        result.put("removedDocuments", removedDocuments);
        result.put("manualDomains", domainRegistry.manualIds());
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
    }
}
