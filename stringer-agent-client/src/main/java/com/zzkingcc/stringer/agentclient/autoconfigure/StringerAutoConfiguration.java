package com.zzkingcc.stringer.agentclient.autoconfigure;

import com.zzkingcc.stringer.api.agent.AgentService;
import com.zzkingcc.stringer.api.code.ErrorCode;
import com.zzkingcc.stringer.agentclient.client.AgentServiceClient;
import com.zzkingcc.stringer.agentclient.client.ClientCredential;
import com.zzkingcc.stringer.agentclient.client.KnowledgeBaseClient;
import com.zzkingcc.stringer.agentclient.exception.StringerErrors;
import com.zzkingcc.stringer.agentclient.exception.StringerStartupException;
import com.zzkingcc.stringer.agentclient.properties.ClientProperties;
import com.zzkingcc.stringer.sdkcore.config.ServerProperties;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Stringer 客户端自动配置
 * @author zzkingcc
 */
@AutoConfiguration
@EnableConfigurationProperties({ServerProperties.class, ClientProperties.class})
public class StringerAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(StringerAutoConfiguration.class);

    /**
     * 客户端专用 WebClient：baseUrl 指向服务端，用于发起 SSE 流式调用。
     */
    @Bean
    @ConditionalOnMissingBean(name = "stringerWebClient")
    public WebClient stringerWebClient(ServerProperties server, ClientProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        (int) properties.getConnectTimeout().toMillis())
                .responseTimeout(properties.getReadTimeout())
                .doOnConnected(conn -> {
                    long readMillis = properties.getReadTimeout().toMillis();
                    if (readMillis > 0) {
                        conn.addHandlerLast(new ReadTimeoutHandler(readMillis, TimeUnit.MILLISECONDS));
                    }
                });

        return WebClient.builder()
                .baseUrl(server.getServerUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    /**
     * 访问凭证持有者：账号密码换签名凭证并缓存（进程内单例，启动探测与业务调用共用）。
     */
    @Bean
    @ConditionalOnMissingBean
    public ClientCredential stringerClientCredential(WebClient stringerWebClient,
                                                     ServerProperties server,
                                                     ClientProperties properties) {
        return new ClientCredential(stringerWebClient, server, properties);
    }

    /**
     * Agent 服务远程实现（极薄客户端）
     */
    @Bean
    @ConditionalOnMissingBean(AgentService.class)
    public AgentService agentService(WebClient stringerWebClient,
                                     ServerProperties server,
                                     ClientCredential stringerClientCredential) {
        return new AgentServiceClient(stringerWebClient, server, stringerClientCredential);
    }

    /**
     * 知识库客户端。
     */
    @Bean
    @ConditionalOnMissingBean
    public KnowledgeBaseClient stringerKnowledgeBaseClient(WebClient stringerWebClient,
                                                           ClientProperties properties,
                                                           ClientCredential stringerClientCredential) {
        return new KnowledgeBaseClient(stringerWebClient, properties, stringerClientCredential);
    }

    /**
     * 启动期连通性校验（强制 fail-fast，不可关闭）。
     */
    @Bean
    public SmartInitializingSingleton stringerConnectivityCheck(WebClient stringerWebClient,
                                                                ServerProperties server,
                                                                ClientProperties properties,
                                                                ClientCredential clientCredential) {
        return () -> {
            // 先换凭证：失败信息比"健康探测 401"具体得多
            String credential = clientCredential.login();

            // 带凭证探测健康端点
            String baseUrl = server.getServerUrl();
            Duration timeout = properties.getHealthCheckTimeout();
            try {
                String body = stringerWebClient.get()
                        .uri("/api/agent/health")
                        .header(ClientCredential.CREDENTIAL_HEADER, credential)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(timeout);
                log.info("[Stringer客户端] 服务端连通性校验通过：{}，响应={}", baseUrl, body);
            } catch (Exception e) {
                // 按 HTTP 状态码判定，而不是对异常文案做 contains 匹配——
                // 后者依赖异常的具体措辞，换个 JDK/Netty 版本就可能失效
                ErrorCode code = ErrorCode.SERVER_UNREACHABLE;
                String httpStatus = ClientCredential.extractHttpStatus(e);
                if ("401".equals(httpStatus) || "403".equals(httpStatus)) {
                    code = ErrorCode.AUTH_REQUIRED;
                } else if (StringerErrors.isTimeout(e)) {
                    code = ErrorCode.EXTERNAL_SERVICE_TIMEOUT;
                }
                String reason = describeFailure(code, server);
                StringerStartupException failure = new StringerStartupException(code,
                        "Stringer 启动失败：无法连接服务端 " + baseUrl + "（" + reason + "）。"
                                + "Stringer 服务端必须先于本应用启动；请确认其已就绪、地址与账号密码正确。"
                                + "若服务端正在执行启动期灌库，可适当调大 stringer.client.health-check-timeout 后重试",
                        e);
                // 响应式链路的原始异常会被 Netty 包装成数十层无意义堆栈，把关键信息埋掉；
                // 这里先以 ERROR 单行输出结论，保证用户第一眼就能看到原因。
                log.error("============================================================");
                log.error("[Stringer客户端] code={}({}) {}", failure.getCode(),
                        failure.getCodeName(), failure.getMessage());
                log.error("============================================================");
                throw failure;
            }
        };
    }

    /**
     * 把失败翻译成可操作的排查提示。
     */
    private static String describeFailure(ErrorCode code, ServerProperties server) {
        // 先给出按码确定的主干提示
        String byCode = switch (code) {
            case AUTH_REQUIRED -> "服务端拒绝了访问凭证。若最近在管控台改过密码，"
                    + "请同步更新 stringer.server.password（当前账号 " + server.getUsername() + "）";
            case EXTERNAL_SERVICE_TIMEOUT -> "连接超时，请检查网络与防火墙，或确认服务端是否仍在启动中";
            case SERVER_UNREACHABLE -> "网络层不可达：连接被拒绝通常是服务端未启动或端口不对，"
                    + "域名解析失败请检查 stringer.server.host 与 DNS";
            default -> null;
        };

        if (byCode != null) {
            return byCode;
        }
        return "服务端返回了非预期响应，请确认地址指向的是 Stringer 服务端";
    }
}
