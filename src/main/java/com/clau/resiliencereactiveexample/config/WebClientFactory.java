package com.clau.resiliencereactiveexample.config;

import com.clau.resiliencereactiveexample.observability.TracePropagationExchangeFilter;
import io.netty.channel.ChannelOption;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebClientFactory {

    private final WebClient.Builder builder;
    private final IntegrationProperties properties;
    private final Map<String, WebClient> clients = new ConcurrentHashMap<>();

    public WebClientFactory(WebClient.Builder builder, IntegrationProperties properties) {
        this.builder = builder;
        this.properties = properties;
    }

    public WebClient client(String serviceName) {
        return clients.computeIfAbsent(serviceName, this::build);
    }

    private WebClient build(String serviceName) {
        IntegrationProperties.Endpoint endpoint = properties.endpoint(serviceName);

        ConnectionProvider connectionProvider = ConnectionProvider.builder("pool-" + serviceName)
                .maxConnections(endpoint.maxConnections())
                .pendingAcquireMaxCount(endpoint.maxConnections() * 4)
                .pendingAcquireTimeout(endpoint.pendingAcquireTimeout())
                .metrics(true)
                .build();

        HttpClient httpClient = HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) endpoint.connectTimeout().toMillis())
                .responseTimeout(endpoint.responseTimeout())
                .metrics(true, uri -> serviceName)
                .compress(true);

        return builder.clone()
                .baseUrl(endpoint.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .filter(new TracePropagationExchangeFilter(serviceName))
                .build();
    }
}
