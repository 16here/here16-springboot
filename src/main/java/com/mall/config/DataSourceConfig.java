package com.mall.config;

import com.mall.common.DataSourceContextHolder;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * 数据源配置（Phase 3 读写分离）。
 *
 * <p>装配三个 Bean：
 * <ul>
 *   <li><b>主库连接池</b>：application.yml 的 spring.datasource 段（MySQL master 3307，
 *       binlog ROW + GTID），承载所有写操作 + 强一致性读；</li>
 *   <li><b>从库连接池</b>：application.yml 的 mall.datasource.slave 段（MySQL slave 3308，
 *       read_only=1），承载绝大多数读请求（商品浏览/订单列表等）；</li>
 *   <li><b>动态路由池</b>（{@link DynamicDataSource}）：唯一对外暴露的 @Primary 数据源，
 *       MyBatis / 事务管理器 / JdbcTemplate 都只认它，由它在内部按线程路由到主/从库。</li>
 * </ul>
 *
 * <p>为什么手动构建 HikariDataSource：本工程是定制版 Spring Boot 4.0.8，
 * 未提供 {@code DataSourceProperties / DataSourceAutoConfiguration}，
 * 因此用 @Value 读取 yml 属性 + 手动 new HikariDataSource 最稳妥（不依赖
 * 自动配置模块是否存在）。连接池参数（maximum-pool-size 等）同理手动设置。
 *
 * <p>为什么主库仍叫 {@code spring.datasource}：MyBatis 等三方库会读取标准属性，
 * 主库保留标准前缀兼容性最好；从库放到自定义 {@code mall.datasource.slave} 段互不干扰。
 */
@Configuration
public class DataSourceConfig {

    // ---------- 主库属性（spring.datasource 段） ----------
    @Value("${spring.datasource.url}")
    private String masterUrl;
    @Value("${spring.datasource.username}")
    private String masterUsername;
    @Value("${spring.datasource.password}")
    private String masterPassword;
    @Value("${spring.datasource.driver-class-name:com.mysql.cj.jdbc.Driver}")
    private String masterDriver;

    // ---------- 从库属性（mall.datasource.slave 段） ----------
    @Value("${mall.datasource.slave.url}")
    private String slaveUrl;
    @Value("${mall.datasource.slave.username}")
    private String slaveUsername;
    @Value("${mall.datasource.slave.password}")
    private String slavePassword;
    @Value("${mall.datasource.slave.driver-class-name:com.mysql.cj.jdbc.Driver}")
    private String slaveDriver;

    /**
     * 动态路由数据源（唯一对外的数据源）。
     *
     * <p>内部持有 master/slave 两个 Hikari 连接池，key 分别为 "master" / "slave"
     * （与 {@link DataSourceContextHolder} 常量一致）；默认路由主库（写操作安全兜底）。
     */
    @Bean
    @Primary
    public DataSource dynamicDataSource() {
        HikariDataSource master = buildPool(masterUrl, masterUsername, masterPassword, masterDriver, 10);
        HikariDataSource slave = buildPool(slaveUrl, slaveUsername, slavePassword, slaveDriver, 20);

        Map<Object, Object> target = new HashMap<>();
        target.put(DataSourceContextHolder.MASTER, master);
        target.put(DataSourceContextHolder.SLAVE, slave);

        DynamicDataSource dynamic = new DynamicDataSource();
        dynamic.setDefaultTargetDataSource(master); // 未命中路由时走主库
        dynamic.setTargetDataSources(target);
        return dynamic;
    }

    /**
     * 事务管理器绑定动态数据源。
     *
     * <p>事务开启时通过 DynamicDataSource 获取连接（determineCurrentLookupKey
     * 判断当前路由），这样"事务内强制主库"规则才能在事务层生效。
     */
    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(DataSource dynamicDataSource) {
        return new DataSourceTransactionManager(dynamicDataSource);
    }

    /**
     * JdbcTemplate（对账体系用，Phase 3）。
     *
     * <p>对账 SQL 直查数据库（不走 MyBatis 拦截器），绑定 @Primary 动态数据源，
     * 配合 {@link com.mall.annotation.DS @DS("master")} 保证对账读主库（防从库延迟误报）。
     */
    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dynamicDataSource) {
        return new JdbcTemplate(dynamicDataSource);
    }

    /** 构建一个 Hikari 连接池（主/从共用，仅连接数与角色不同） */
    private HikariDataSource buildPool(String url, String user, String pwd, String driver, int maxPoolSize) {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(url);
        pool.setUsername(user);
        pool.setPassword(pwd);
        pool.setDriverClassName(driver);
        pool.setMaximumPoolSize(maxPoolSize);
        pool.setMinimumIdle(Math.max(1, maxPoolSize / 5));
        pool.setConnectionTimeout(30_000);
        pool.setPoolName("mall-ds-" + (url.contains("3307") ? "master" : "slave"));
        return pool;
    }
}
