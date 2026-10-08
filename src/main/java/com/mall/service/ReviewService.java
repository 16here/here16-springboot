package com.mall.service;

import com.mall.common.BusinessException;
import com.mall.common.PageResult;
import com.mall.dto.ReviewCreateDTO;
import com.mall.dto.ReviewStatsVO;
import com.mall.dto.ReviewVO;
import com.mall.entity.Order;
import com.mall.entity.OrderItem;
import com.mall.entity.Review;
import com.mall.enums.OrderStatusEnum;
import com.mall.repository.OrderItemRepository;
import com.mall.repository.OrderRepository;
import com.mall.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 评价服务（阶段 7）：发表评价、商品评价列表、评分统计、管理端审核与回复。
 *
 * <p>关键设计：
 * <ul>
 *   <li><b>只有已完成（status=3）的订单才能评价</b>——交易闭环的最后一环；
 *       评价挂在订单明细上，同一明细只能评价一次（数据库唯一键 + Service 校验双保险）；</li>
 *   <li><b>评价默认待审核（status=0）</b>，管理端审核通过（1）后才在商品详情公开展示——
 *       满足需求中"评价审核"的管控要求；</li>
 *   <li>商品评价列表与评分统计只统计已发布评价，口径一致。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    /** 评价状态 */
    public static final int STATUS_PENDING = 0;  // 待审核
    public static final int STATUS_PUBLISHED = 1; // 已发布
    public static final int STATUS_HIDDEN = 2;   // 已隐藏

    private final ReviewRepository reviewRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;

    // ==================== 用户端 ====================

    /**
     * 发表评价。
     * 校验链：订单归属 → 订单已完成 → 明细属于该订单 → 未评价过 → 落库（待审核）。
     */
    @Transactional
    public ReviewVO create(Long userId, ReviewCreateDTO dto) {
        // 1. 订单归属校验
        Order order = orderRepository.selectByIdAndUserId(dto.getOrderId(), userId);
        if (order == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "订单不存在");
        }
        // 2. 只有已完成订单可评价
        if (order.getStatus() == null || order.getStatus() != OrderStatusEnum.COMPLETED.getCode()) {
            throw new BusinessException(BusinessException.PARAM_ERROR,
                    "订单完成后才能评价（当前状态：" + OrderStatusEnum.textOf(order.getStatus()) + "）");
        }
        // 3. 明细属于该订单
        OrderItem item = orderItemRepository.selectByIdAndOrderId(dto.getOrderItemId(), dto.getOrderId());
        if (item == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "订单商品不存在");
        }
        // 4. 防重复评价（数据库唯一键兜底）
        if (reviewRepository.selectByOrderItemId(dto.getOrderItemId()) != null) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "该商品已评价，不能重复评价");
        }
        // 5. 落库（默认待审核；图片 List → JSON 字符串）
        Review review = new Review();
        review.setOrderId(order.getId());
        review.setOrderItemId(item.getId());
        review.setUserId(userId);
        review.setProductId(item.getProductId());
        review.setSkuId(item.getSkuId() == null ? 0L : item.getSkuId());
        review.setRating(dto.getRating());
        review.setContent(dto.getContent().trim());
        review.setImages(toJson(dto.getImages()));
        review.setStatus(STATUS_PENDING);
        reviewRepository.insert(review);
        log.info("评价已提交（待审核）: reviewId={}, productId={}, userId={}, rating={}",
                review.getId(), review.getProductId(), userId, review.getRating());
        return toVO(review);
    }

    /**
     * 商品已发布评价分页（商品详情评价列表，公开接口）。
     */
    public PageResult<ReviewVO> listByProduct(Long productId, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        List<Review> reviews = reviewRepository.selectPageByProduct(productId, offset, pageSize);
        long total = reviewRepository.countByProduct(productId);
        List<ReviewVO> vos = reviews.stream().map(this::toVO).toList();
        return new PageResult<>(total, vos);
    }

    /**
     * 商品评分统计（评分概览：平均分 + 1~5 星分布 + 5 星占比）。
     * 口径：仅已发布评价。
     */
    public ReviewStatsVO stats(Long productId) {
        ReviewStatsVO vo = new ReviewStatsVO(productId);
        List<ReviewRepository.RatingCount> rows = reviewRepository.selectRatingStats(productId);
        if (rows.isEmpty()) {
            vo.empty();
            return vo;
        }
        long total = 0;
        BigDecimal sum = BigDecimal.ZERO;
        for (ReviewRepository.RatingCount row : rows) {
            long cnt = row.cnt();
            total += cnt;
            sum = sum.add(BigDecimal.valueOf(row.rating()).multiply(BigDecimal.valueOf(cnt)));
            switch (row.rating()) {
                case 5 -> vo.setFive(cnt);
                case 4 -> vo.setFour(cnt);
                case 3 -> vo.setThree(cnt);
                case 2 -> vo.setTwo(cnt);
                default -> vo.setOne(cnt);
            }
        }
        vo.setTotalCount(total);
        vo.setAvgRating(total == 0 ? BigDecimal.ZERO : sum.divide(BigDecimal.valueOf(total), 4, java.math.RoundingMode.HALF_UP));
        vo.finish();
        return vo;
    }

    // ==================== 管理端 ====================

    /** 管理端评价分页（状态筛选：全部/待审核/已发布/已隐藏） */
    public PageResult<ReviewVO> adminList(Integer status, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        List<Review> reviews = reviewRepository.adminPage(status, offset, pageSize);
        long total = reviewRepository.adminCount(status);
        List<ReviewVO> vos = reviews.stream().map(this::toVO).toList();
        return new PageResult<>(total, vos);
    }

    /**
     * 审核评价：通过（1 展示）或隐藏（2 下架）。
     * 条件 UPDATE 保证仅待审核的评价可审核，重复审核 rows=0 报错。
     */
    @Transactional
    public ReviewVO audit(Long reviewId, Integer status) {
        if (status == null || (status != STATUS_PUBLISHED && status != STATUS_HIDDEN)) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "审核结果不合法");
        }
        Review review = reviewRepository.selectById(reviewId);
        if (review == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "评价不存在");
        }
        if (reviewRepository.updateStatus(reviewId, status) == 0) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "该评价已审核过，请刷新后重试");
        }
        log.info("评价已审核: reviewId={}, status={}", reviewId, status);
        review.setStatus(status);
        return toVO(review);
    }

    /** 商家回复评价（写回复与回复时间，不影响审核状态） */
    @Transactional
    public ReviewVO reply(Long reviewId, String reply) {
        Review review = reviewRepository.selectById(reviewId);
        if (review == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "评价不存在");
        }
        if (reply == null || reply.isBlank()) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "回复内容不能为空");
        }
        reviewRepository.updateReply(reviewId, reply.trim());
        review.setReply(reply.trim());
        review.setReplyAt(java.time.LocalDateTime.now());
        return toVO(review);
    }

    // ==================== 私有工具 ====================

    /** 实体 → VO（图片 JSON → List；状态码 → 中文） */
    private ReviewVO toVO(Review r) {
        ReviewVO vo = new ReviewVO();
        vo.setId(r.getId());
        vo.setOrderId(r.getOrderId());
        vo.setOrderItemId(r.getOrderItemId());
        vo.setUserId(r.getUserId());
        vo.setProductId(r.getProductId());
        vo.setSkuId(r.getSkuId());
        vo.setRating(r.getRating());
        vo.setContent(r.getContent());
        vo.setImages(fromJson(r.getImages()));
        vo.setStatus(r.getStatus());
        vo.setStatusText(statusText(r.getStatus()));
        vo.setUsername(r.getUsername());
        vo.setProductName(r.getProductName());
        vo.setSkuName(r.getSkuName());
        vo.setReply(r.getReply());
        vo.setReplyAt(r.getReplyAt());
        vo.setCreatedAt(r.getCreatedAt());
        return vo;
    }

    /** 状态中文名 */
    private String statusText(Integer status) {
        return switch (status == null ? -1 : status) {
            case STATUS_PENDING -> "待审核";
            case STATUS_PUBLISHED -> "已发布";
            case STATUS_HIDDEN -> "已隐藏";
            default -> "未知";
        };
    }

    /** List&lt;String&gt; → JSON 数组字符串（null/空 → null）。轻量手写，不依赖外部 JSON 库：
     *  只转义双引号与反斜杠，图片 URL 场景足够。 */
    private String toJson(List<String> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < images.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            String url = images.get(i);
            if (url == null) {
                sb.append("\"\"");
                continue;
            }
            sb.append('"').append(url.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append("]").toString();
    }

    /** JSON 数组字符串 → List&lt;String&gt;（null/空 → 空列表） */
    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        String trimmed = json.trim();
        if (trimmed.startsWith("[")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("]")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isBlank()) {
            return new ArrayList<>();
        }
        List<String> urls = new ArrayList<>();
        for (String part : trimmed.split(",")) {
            String url = part.trim();
            if (url.startsWith("\"") && url.endsWith("\"") && url.length() >= 2) {
                url = url.substring(1, url.length() - 1);
            }
            if (!url.isEmpty()) {
                urls.add(url);
            }
        }
        return urls;
    }
}
