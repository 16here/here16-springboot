package com.mall.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 配置（Phase 1 缓存序列化 + 雪花 ID 字符串化）。
 *
 * <p>Phase 1 起，商品详情/分类树/静态页需要用 JSON 在 Caffeine 与 Redis 之间
 * 读写缓存数据。定制版 Boot 未随 webmvc 提供 Jackson（REST 序列化走内置实现），
 * 因此这里显式声明一个独立的 ObjectMapper Bean，供缓存服务注入使用。
 *
 * <p>配置要点：
 * <ul>
 *   <li>忽略未知字段（缓存结构演进时旧数据仍可反序列化）；</li>
 *   <li>不输出 null 字段（减小缓存体积）；</li>
 *   <li>不启用时间戳序列化（保持 Java 时间类型可读性）；</li>
 *   <li>Long/long → 字符串（雪花 ID 19 位超出 JS 安全整数，防前端精度丢失）。</li>
 * </ul>
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // 未知字段不报错：缓存里多出的字段忽略，保证向后兼容
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // 序列化时跳过 null 字段，压缩缓存体积
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        // 时间默认序列化为 ISO 字符串（而非时间戳）
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // 所有 Long（含 long）序列化为字符串：雪花 ID 19 位超出 JS Number 安全整数
        // 范围（2^53≈9e15），序列化成数字会被前端解析丢精度→回传查不到订单。
        SimpleModule longAsString = new SimpleModule();
        longAsString.addSerializer(Long.class, ToStringSerializer.instance);
        longAsString.addSerializer(Long.TYPE, ToStringSerializer.instance);
        mapper.registerModule(longAsString);
        return mapper;
    }
}
