package com.mall.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 发表评价请求体（阶段 7）。
 *
 * <p>只传"评价对象 + 内容"，商品名/规格名等展示字段由后端从订单明细快照读取，
 * 防止前端伪造展示信息；订单归属、订单状态（必须已完成）、是否已评价
 * 由 {@link com.mall.service.ReviewService} 校验。
 */
@Data
public class ReviewCreateDTO {

    /** 订单 ID（必须属于当前用户且状态=已完成） */
    @NotNull(message = "订单不能为空")
    private Long orderId;

    /** 订单明细 ID（一个明细一条评价；决定评价的是哪个商品/规格） */
    @NotNull(message = "请选择要评价的商品")
    private Long orderItemId;

    /** 评分 1-5 星 */
    @NotNull(message = "请打分")
    @Min(value = 1, message = "评分最低 1 星")
    @Max(value = 5, message = "评分最高 5 星")
    private Integer rating;

    /** 评价内容（非空，最长 1000 字） */
    @NotBlank(message = "评价内容不能为空")
    private String content;

    /** 评价图片（URL 列表；阶段 7 无文件上传接口，由前端填写图片地址） */
    private List<String> images;
}
