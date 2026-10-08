package com.mall;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 启动上下文冒烟测试：验证 Bean 装配、配置加载是否正常。
 *
 * <p>注意：本测试会真实连接本地 MySQL 与 Redis（见 application.yml），
 * 运行前请确保两者已启动并已执行 sql/schema.sql 建表。
 */
@SpringBootTest
class MallApplicationTests {

    @Test
    void contextLoads() {
    }
}
