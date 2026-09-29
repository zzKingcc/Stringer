package com.zzkingcc.stringer.agentclient.client;

import com.zzkingcc.stringer.api.code.ErrorCode;
import com.zzkingcc.stringer.agentclient.exception.StringerException;
import com.zzkingcc.stringer.agentclient.exception.StringerErrors;
import com.zzkingcc.stringer.agentclient.properties.ClientProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * 知识库客户端
 *
 * @author zzkingcc
 */
public class KnowledgeBaseClient {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseClient.class);

    private final WebClient webClient;
    private final ClientProperties properties;
    private final ClientCredential credential;

    public KnowledgeBaseClient(WebClient webClient, ClientProperties properties, ClientCredential credential) {
        this.webClient = webClient;
        this.properties = properties;
        this.credential = credential;
    }

    /** 上传结果 */
    public record UploadResult(String docId, String fileName, long size, int chunks) {}

    /** 已入库文档条目 */
    public record DocumentItem(String docId, String fileName, int chunks) {}

    /**
     * 上传一个文档（不声明域 → 只属兜底域 {@code default}）。
     *
     * @param content  文件字节
     * @param fileName 文件名（含扩展名，需在服务端白名单内）
     * @param replace  {@code true} = 已存在同名文档时覆盖更新；{@code false} = 同名直接拒绝
     */
    public UploadResult upload(byte[] content, String fileName, boolean replace) {
        return upload(content, fileName, replace, List.of());
    }

    /**
     * 上传一个文档，并声明它的可用域。
     *
     * <p>域决定<b>哪些对话能检索到这份文档</b>，与 {@code @Tool(domains = {...})} 同构：
     * 含 {@code "*"} → 全域可见；留空 → 只属兜底域；否则原样。
     */
    public UploadResult upload(byte[] content, String fileName, boolean replace, List<String> domains) {
        if (content == null || content.length == 0) {
            throw new StringerException(ErrorCode.INVALID_PARAMETER, "上传内容为空");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new StringerException(ErrorCode.INVALID_PARAMETER, "文件名不能为空");
        }
        log.info("[Stringer客户端] 上传知识库文档：文件={}，大小={} 字节，replace={}",
                fileName, content.length, replace);

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });

        List<String> effectiveDomains = domains == null ? List.of() : domains.stream()
                .filter(d -> d != null && !d.isBlank())
                .map(String::trim)
                .distinct()
                .toList();

        Map<String, Object> resp = webClient.post()
                .uri(uri -> {
                    uri.path("/admin/kb/documents").queryParam("replace", replace);
                    if (!effectiveDomains.isEmpty()) {
                        uri.queryParam("domains", effectiveDomains);
                    }
                    return uri.build();
                })
                .header(ClientCredential.CREDENTIAL_HEADER, credential.get())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(Map.class)
                .onErrorMap(StringerErrors::fromTransport)
                .block(properties.getReadTimeout());

        if (resp == null) {
            throw new StringerException(ErrorCode.UNEXPECTED_ERROR, "上传知识库文档无响应");
        }
        if (Boolean.FALSE.equals(resp.get("success"))) {
            // 服务端把业务失败也包成 200 + success=false，这里还原成带码异常，
            // 接入方才能按"重名 / 类型不支持 / 超限"分别处理
            throw StringerErrors.fromResponseBody(resp);
        }
        return new UploadResult(
                str(resp.get("docId")),
                str(resp.get("fileName")),
                resp.get("size") instanceof Number n ? n.longValue() : 0L,
                resp.get("chunks") instanceof Number n ? n.intValue() : 0);
    }

    /** 已入库文档列表 */
    @SuppressWarnings("unchecked")
    public List<DocumentItem> list() {
        Map<String, Object> resp = webClient.get()
                .uri("/admin/kb/documents")
                .header(ClientCredential.CREDENTIAL_HEADER, credential.get())
                .retrieve()
                .bodyToMono(Map.class)
                .onErrorMap(StringerErrors::fromTransport)
                .block(properties.getReadTimeout());
        if (resp == null) {
            return List.of();
        }
        Object docs = resp.get("documents");
        if (!(docs instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream()
                .filter(Map.class::isInstance)
                .map(o -> (Map<String, Object>) o)
                .map(m -> new DocumentItem(str(m.get("docId")), str(m.get("fileName")),
                        m.get("chunks") instanceof Number n ? n.intValue() : 0))
                .toList();
    }

    /**
     * 删除文档并释放其文件名（删除后同名可再次上传）。
     *
     * @return 是否命中并删除
     */
    public boolean delete(String docId) {
        if (docId == null || docId.isBlank()) {
            throw new StringerException(ErrorCode.INVALID_PARAMETER, "docId 不能为空");
        }
        Map<String, Object> resp = webClient.delete()
                .uri("/admin/kb/documents/{docId}", docId)
                .header(ClientCredential.CREDENTIAL_HEADER, credential.get())
                .retrieve()
                .bodyToMono(Map.class)
                .onErrorMap(StringerErrors::fromTransport)
                .block(properties.getReadTimeout());
        return resp != null && Boolean.TRUE.equals(resp.get("deleted"));
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
