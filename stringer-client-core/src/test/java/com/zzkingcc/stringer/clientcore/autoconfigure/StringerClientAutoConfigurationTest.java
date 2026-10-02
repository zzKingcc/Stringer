package com.zzkingcc.stringer.clientcore.autoconfigure;

import com.zzkingcc.stringer.api.code.ErrorCode;
import com.zzkingcc.stringer.clientcore.exception.StringerStartupException;
import com.zzkingcc.stringer.clientcore.http.ClientCredential;
import com.zzkingcc.stringer.clientcore.properties.ClientProperties;
import com.zzkingcc.stringer.sdkcore.config.StringerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 客户端底座：WebClient / 凭证 / 启动期探测。
 *
 * <p>这三样与"引了哪个 SDK"无关 —— 对话 SDK 与知识库 SDK 拿到的是同一份，
 * 因此它单独成模块、单独验证：少了它两个 SDK 都起不来，多了它也不该替谁做业务决定。</p>
 */
class StringerClientAutoConfigurationTest {

    private final StringerClientAutoConfiguration configuration = new StringerClientAutoConfiguration();
    private final ClientProperties properties = new ClientProperties();

    private static StringerProperties server(String url, String username, String password) {
        StringerProperties server = new StringerProperties();
        server.setServer(url);
        server.setUsername(username);
        server.setPassword(password);
        return server;
    }

    @Test
    void 底座按同一份配置装配出WebClient与凭证持有者() {
        StringerProperties server = server("http://localhost:9527", "stringer", "stringer");

        WebClient webClient = configuration.stringerWebClient(server, properties);
        ClientCredential credential = configuration.stringerClientCredential(webClient, server, properties);

        assertNotNull(webClient);
        assertNotNull(credential);
        assertEquals(Duration.ofSeconds(5), properties.getConnectTimeout());
        assertEquals(Duration.ofMinutes(10), properties.getReadTimeout(), "SSE 是长连接，读超时必须放宽");
    }

    @Test
    void 未配置账号时启动探测中断启动并给出码() {
        StringerProperties server = server("http://localhost:9527", "", "");
        WebClient webClient = configuration.stringerWebClient(server, properties);
        ClientCredential credential = configuration.stringerClientCredential(webClient, server, properties);
        SmartInitializingSingleton probe =
                configuration.stringerConnectivityCheck(webClient, server, properties, credential);

        StringerStartupException ex = assertThrows(StringerStartupException.class, probe::afterSingletonsInstantiated);

        assertEquals(ErrorCode.AUTH_REQUIRED, ex.getErrorCode());
        assertEquals(10002, ex.getCode(), "启动期失败也要带码，接入方才能按码分支");
    }

    @Test
    void 服务端不可达时启动探测中断启动() {
        StringerProperties server = server("http://127.0.0.1:1", "stringer", "stringer");
        WebClient webClient = configuration.stringerWebClient(server, properties);
        ClientCredential credential = configuration.stringerClientCredential(webClient, server, properties);
        SmartInitializingSingleton probe =
                configuration.stringerConnectivityCheck(webClient, server, properties, credential);

        StringerStartupException ex = assertThrows(StringerStartupException.class, probe::afterSingletonsInstantiated);

        assertEquals(ErrorCode.SERVER_UNREACHABLE, ex.getErrorCode());
    }
}
