package com.mall;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 电商系统启动类。
 *
 * <p>注解说明：
 * <ul>
 *   <li>{@code @SpringBootApplication}：Spring Boot 主入口，自动扫描
 *       com.mall 包及其子包下的组件（Controller/Service/Config 等）</li>
 *   <li>{@code @MapperScan}：扫描 Mapper 接口（UserRepository 等），
 *       免去为每个 Mapper 单独标注 @Mapper 的重复劳动</li>
 *   <li>{@code @EnableScheduling}：开启定时任务（后续阶段的订单超时
 *       关闭、库存释放等任务会用到）</li>
 * </ul>
 */
@SpringBootApplication
@MapperScan("com.mall.repository")
@EnableScheduling
public class MallApplication {

    public static void main(String[] args) {
        SpringApplication.run(MallApplication.class, args);
    }
}
