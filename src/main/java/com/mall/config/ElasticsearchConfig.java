package com.mall.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch 客户端配置（阶段 12：商品全文检索）。
 *
 * <p>说明：
 * <ul>
 *   <li><b>为什么用官方 elasticsearch-java 客户端</b>：类型安全的 fluent DSL、
 *       官方长期维护；显式固定 8.15.5 与部署的服务端版本完全一致；</li>
 *   <li><b>懒连接</b>：RestClient 创建时不会立即连接 ES，请求时才发连接。
 *       因此 ES 未启动时应用照常启动，搜索由 {@code ProductSearchService}
 *       捕获异常并降级回 MySQL LIKE（见其类注释）；</li>
 *   <li><b>可配置</b>：地址取自 application.yml 的 elasticsearch.host/port，
 *       默认 127.0.0.1:9200。</li>
 * </ul>
 */
@Configuration
public class ElasticsearchConfig {

    @Value("${elasticsearch.host:127.0.0.1}")
    private String host;

    @Value("${elasticsearch.port:9200}")
    private int port;

    @Bean
    public ElasticsearchClient elasticsearchClient() {
        // 低层 HTTP 客户端（连接池、重试由 RestClient 管理）
        RestClient restClient = RestClient.builder(new HttpHost(host, port, "http")).build();
        // Jackson 序列化器：与项目既有的 Jackson 体系一致
        ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }
}
