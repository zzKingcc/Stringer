package com.zzkingcc.stringer.server.controller;

import com.zzkingcc.stringer.api.code.ErrorCode;
import com.zzkingcc.stringer.common.exception.BaseException;
import com.zzkingcc.stringer.runtime.domain.DomainRegistry;
import com.zzkingcc.stringer.server.settings.DomainStore;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理面：域的创建与删除。
 *
 * <p>为什么需要它：域一直是由工具声明"顺带"产生的，于是"先建域、再启动应用"这件事做不到 ——
 * 一个还没有任何工具的新场景没法先把域建出来。有了创建入口，域成为可独立存在的实体。</p>
 *
 * <p>删除的边界很窄：<b>只有人工创建的域可删</b>。内置域是兜底（删了兜底无处可落），
 * 派生域的生命周期归工具（删了下次扫描又会回来）。</p>
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

    public AdminDomainController(DomainRegistry domainRegistry, DomainStore domainStore) {
        this.domainRegistry = domainRegistry;
        this.domainStore = domainStore;
    }

    /**
     * 创建域。
     *
     * @param body {@code id} 域标识，非空、无空白、长度 ≤ 64、不得为通配符 {@code *}
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
        return view("created", body.getId().trim());
    }

    /**
     * 删除人工创建的域。
     */
    @DeleteMapping("/admin/domains/{id}")
    public Map<String, Object> delete(@PathVariable("id") String id) {
        DomainRegistry.DeleteResult deleted = domainRegistry.delete(id);
        if (!deleted.deleted()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, deleted.reason());
        }
        domainStore.saveManualDomains(domainRegistry.manualIds());
        log.info("[域管理] 已删除域 {}", id);
        return view("deleted", id);
    }

    /**
     * 统一返回体：动作 + 当前域全貌，便于管控台一次刷新。
     */
    private Map<String, Object> view(String action, String domainId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("action", action);
        result.put("domain", domainId);
        result.put("manualDomains", domainRegistry.manualIds());
        result.put("settingsFile", domainStore.filePath());
        return result;
    }

    /** 创建请求体 */
    @Data
    public static class CreateBody {
        /** 域标识 */
        private String id;
    }
}
