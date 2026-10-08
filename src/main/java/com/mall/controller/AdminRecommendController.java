package com.mall.controller;

import com.mall.common.Result;
import com.mall.service.RecommendService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端推荐接口（阶段 10，/api/admin/recommend/**，仅 ADMIN）。
 *
 * <p>支撑两个管理端页面：
 * <ul>
 *   <li><b>推荐效果统计</b>（Dashboard 卡片）：近 N 天总行为数 / 今日行为数 /
 *       各类行为数（浏览/收藏/加购/购买）；</li>
 *   <li><b>热门商品管理</b>（RecommendManage）：每个商品的行为构成明细
 *       （为什么热、谁在看/买）；</li>
 *   <li><b>推荐位配置</b>：首页推荐位数量 / 热度窗口天数 / 缓存 TTL 热更新
 *       （写入 Redis 运行时配置，无需重启）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/recommend")
@RequiredArgsConstructor
public class AdminRecommendController {

    private final RecommendService recommendService;

    /** 推荐效果统计（?days=，默认按配置窗口） */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats(@RequestParam(defaultValue = "0") int days) {
        return Result.success(recommendService.stats(days));
    }

    /** 热门商品管理：近 N 天行为构成明细（?days=&limit=） */
    @GetMapping("/hot")
    public Result<List<Map<String, Object>>> hot(@RequestParam(defaultValue = "0") int days,
                                                 @RequestParam(defaultValue = "0") int limit) {
        return Result.success(recommendService.hotList(days, limit));
    }

    /** 当前生效的推荐位配置（表单回显） */
    @GetMapping("/config")
    public Result<Map<String, Object>> config() {
        return Result.success(recommendService.currentConfig());
    }

    /** 保存推荐位配置（热更新：写 Redis + 清缓存） */
    @PutMapping("/config")
    public Result<Void> saveConfig(@RequestBody Map<String, Object> config) {
        recommendService.saveConfig(config);
        return Result.success(null);
    }
}
