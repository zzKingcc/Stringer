package com.zzkingcc.stringer.chatclient.client;

import com.zzkingcc.stringer.api.agent.AgentRequest;
import com.zzkingcc.stringer.api.agent.AgentService;
import com.zzkingcc.stringer.api.agent.CallerContext;
import com.zzkingcc.stringer.api.event.AgentEvent;
import com.zzkingcc.stringer.clientcore.exception.StringerErrors;
import com.zzkingcc.stringer.clientcore.exception.StringerException;
import com.zzkingcc.stringer.clientcore.http.ClientCredential;
import com.zzkingcc.stringer.sdkcore.config.StringerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Objects;

/**
 * Stringer 客户端
 * @author zzkingcc
 */
public class AgentServiceClient implements AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentServiceClient.class);

    private final WebClient webClient;
    private final ClientCredential credential;

    public AgentServiceClient(WebClient webClient, StringerProperties server, ClientCredential credential) {
        this.webClient = webClient;
        this.credential = credential;
        log.info("[Stringer客户端] 初始化完成，服务端地址={}，接入账号={}",
                server.getServerUrl(), server.getUsername());
    }

    @Override
    public Flux<AgentEvent> chat(AgentRequest request) {
        log.info("[Stringer客户端] chat 会话[{}] profile={}",
                request.getSessionId(), request.getProfile());
        return webClient.post()
                .uri("/api/agent/chat")
                .header(ClientCredential.CREDENTIAL_HEADER, credential.get())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(AgentEvent.class)
                .doOnError(this::invalidateIfUnauthorized)
                // 传输层失败不会变成"流里的一项"，而是 Flux.onError 里的裸异常（Netty / Reactor 包了好几层）。
                // 统一翻译成带码的 StringerException，接入方 catch 到的就是 90001/90002/10002 这类可分支的码，
                // 不必自己去 WebClientResponseException 链里刨 HTTP 状态码。
                .onErrorMap(StringerErrors::fromTransport);
    }

    /**
     * 恢复被挂起的会话。
     */
    @Override
    public Flux<AgentEvent> resume(String sessionId, boolean approved, CallerContext caller) {
        Objects.requireNonNull(caller, "caller 不能为空：resume 必须携带 profile");
        log.info("[Stringer客户端] resume 会话[{}] approved={} profile={}",
                sessionId, approved, caller.profile());
        return webClient.post()
                .uri(uriBuilder -> uriBuilder.path("/api/agent/resume")
                        .queryParam("sessionId", sessionId)
                        .queryParam("approved", approved)
                        .build())
                .header(ClientCredential.CREDENTIAL_HEADER, credential.get())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(caller)
                .retrieve()
                .bodyToFlux(AgentEvent.class)
                .doOnError(this::invalidateIfUnauthorized)
                .onErrorMap(StringerErrors::fromTransport);
    }

    /**
     * 停止任务。
     */
    @Override
    public boolean stop(String sessionId) {
        log.info("[Stringer客户端] stop 会话[{}]", sessionId);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = webClient.post()
                    .uri("/api/agent/stop/{sessionId}", sessionId)
                    .header(ClientCredential.CREDENTIAL_HEADER, credential.get())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            StringerException businessError = StringerErrors.fromResponseBody(resp);
            if (businessError != null) {
                log.warn("[Stringer客户端] stop 会话[{}] 被服务端拒绝: code={}({}), {}",
                        sessionId, businessError.getCode(), businessError.getCodeName(),
                        businessError.getMessage());
                return false;
            }
            return resp != null && Boolean.TRUE.equals(resp.get("stopRequested"));
        } catch (Exception e) {
            invalidateIfUnauthorized(e);
            StringerException mapped = StringerErrors.fromTransport(e);
            log.error("[Stringer客户端] stop 会话[{}] 失败: code={}({}), {}",
                    sessionId, mapped.getCode(), mapped.getCodeName(), mapped.getMessage(), e);
            return false;
        }
    }

    /**
     * 凭证失效则丢弃缓存。
     */
    private void invalidateIfUnauthorized(Throwable e) {
        if (ClientCredential.isUnauthorized(e)) {
            credential.invalidate();
            log.warn("[Stringer客户端] 服务端拒绝了凭证（401/403），已清除本地缓存；"
                    + "下次调用将自动重新登录。若持续失败，通常是服务端改过密码，请同步 stringer.password");
        }
    }
}
