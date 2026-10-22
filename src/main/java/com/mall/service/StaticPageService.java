package com.mall.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.PageResult;
import com.mall.dto.ProductListVO;
import com.mall.dto.ProductQueryDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 静态页生成服务（Phase 1 静态化）。
 *
 * <p>把"首页热门商品列表"这类读多写少、几乎不个性化的数据，
 * 提前渲染成静态 JSON 文件写入 {@code ./static-page/} 目录，
 * 由 Nginx/CDN 直接托管——大促流量命中静态文件即可，完全不打到 Java 与数据库。
 *
 * <p>本服务当前生成 <b>home.json</b>（首页商品列表快照）；
 * 未来可扩展为详情页静态 HTML、秒杀页快照等（同一种"预渲染 + 落盘"模式）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaticPageService {

    /** 静态页根目录（相对后端运行目录，与 StaticPageConfig 映射一致） */
    private static final String STATIC_PAGE_DIR = "./static-page";
    /** 首页商品快照文件名 */
    private static final String HOME_FILE = "home.json";
    /** 列表快照大小：与首页前端展示数量一致 */
    private static final int HOME_PAGE_SIZE = 12;

    private final ProductService productService;
    private final ObjectMapper objectMapper;

    /**
     * 生成首页商品列表静态 JSON（home.json）。
     *
     * <p>内容结构：{ generatedAt, size, products: [...] }——
     * products 复用商品列表 VO（含主图/价格/库存），前端静态模式下可直接渲染。
     * 生成失败仅记日志，不影响主流程（定时任务下一次会重试）。
     */
    public void generateHomeJson() {
        try {
            // 1. 查询首页热销商品（空关键词 = 默认排序的最新上架商品）
            ProductQueryDTO query = new ProductQueryDTO();
            query.setPage(1);
            query.setPageSize(HOME_PAGE_SIZE);
            PageResult<ProductListVO> page = productService.page(query);

            // 2. 组装快照结构（generatedAt 让消费方知道数据新鲜度）
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            snapshot.put("size", page.getTotal());
            snapshot.put("products", page.getList());

            // 3. 序列化并落盘（先写临时文件再原子替换，避免读到半截文件）
            String json = objectMapper.writeValueAsString(snapshot);
            Path dir = Paths.get(STATIC_PAGE_DIR).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path target = dir.resolve(HOME_FILE);
            Path tmp = dir.resolve(HOME_FILE + ".tmp");
            Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            log.info("首页静态页已生成: {}，共 {} 件商品", target, page.getTotal());
        } catch (IOException e) {
            log.error("首页静态页生成失败（下次定时任务自动重试）", e);
        }
    }
}
