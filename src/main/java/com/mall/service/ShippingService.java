package com.mall.service;

import com.mall.common.BusinessException;
import com.mall.common.PageResult;
import com.mall.dto.ShippingCreateDTO;
import com.mall.dto.ShippingVO;
import com.mall.dto.TrackingVO;
import com.mall.entity.Order;
import com.mall.entity.Shipping;
import com.mall.entity.ShippingTrace;
import com.mall.repository.OrderRepository;
import com.mall.repository.ShippingRepository;
import com.mall.repository.ShippingTraceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 物流跟踪核心服务（阶段 9）。
 *
 * <p>职责四件套：<b>发货</b>（创建运单 + 初始轨迹 + 订单状态流转 1→2）、
 * <b>查询</b>（用户端归属校验 / 管理端分页）、<b>同步轨迹</b>
 * （快递100 / 快递鸟真实查询，未配置密钥时 mock 模拟推进）、
 * <b>状态推进</b>（0 已发货 → 1 运输中 → 2 已签收，条件 UPDATE 只进不退）。
 *
 * <p>物流状态约定（shipping.status）：0 已发货 / 1 运输中 / 2 已签收；
 * 订单状态约定沿用阶段 5：1 待发货 → 2 待收货。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShippingService {

    /** 物流状态中文文案 */
    private static final String[] STATUS_TEXT = {"已发货", "运输中", "已签收"};

    /** 快递公司编码映射：中文名 → 快递100 编码 / 快递鸟编码（真实对接时用） */
    private static final Map<String, String[]> COMPANY_CODES = Map.ofEntries(
            Map.entry("顺丰", new String[]{"shunfeng", "SF"}),
            Map.entry("中通", new String[]{"zhongtong", "ZTO"}),
            Map.entry("圆通", new String[]{"yuantong", "YTO"}),
            Map.entry("韵达", new String[]{"yunda", "YD"}),
            Map.entry("申通", new String[]{"shentong", "STO"}),
            Map.entry("京东", new String[]{"jd", "JD"}),
            Map.entry("邮政EMS", new String[]{"ems", "EMS"})
    );

    /** mock 轨迹模拟用城市池（演示数据） */
    private static final String[] MOCK_CITIES = {"杭州", "南京", "上海", "苏州", "无锡"};

    private final ShippingRepository shippingRepository;
    private final ShippingTraceRepository shippingTraceRepository;
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final Kuaidi100Service kuaidi100Service;
    private final KuaidiBirdService kuaidiBirdService;

    /** 物流查询供应商（application.yml：kuaidi.provider；mock 本地模拟） */
    @Value("${kuaidi.provider:mock}")
    private String provider;

    /**
     * 发货：创建运单 + 初始轨迹 + 订单状态流转（待发货 1 → 待收货 2）。
     *
     * <p>同一事务：运单创建失败 / 订单流转失败都会整体回滚，不会出现
     * "订单已发货但没运单"或"有运单但订单状态没变"的中间态。
     * 重复发货由 shipping.order_id 唯一键 + 前置校验双重拦截。
     */
    @Transactional
    public ShippingVO ship(Long adminId, ShippingCreateDTO dto) {
        Order order = orderRepository.selectById(dto.getOrderId());
        if (order == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "订单不存在");
        }
        if (order.getStatus() != 1) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "仅待发货订单可发货");
        }
        // 幂等：同订单已有运单则拒绝（唯一键兜底）
        if (shippingRepository.findByOrderId(order.getId()) != null) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "该订单已发货，请勿重复操作");
        }

        // 1. 创建运单（初始状态：已发货）
        Shipping shipping = new Shipping();
        shipping.setOrderId(order.getId());
        shipping.setOrderNo(order.getOrderNo());
        shipping.setShippingCompany(dto.getShippingCompany());
        shipping.setTrackingNo(dto.getTrackingNo().trim());
        shipping.setStatus(0);
        if (shippingRepository.insert(shipping) == 0) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "该订单已发货，请勿重复操作");
        }

        // 2. 写入初始轨迹（揽收节点）
        ShippingTrace trace = new ShippingTrace();
        trace.setShippingId(shipping.getId());
        trace.setTrackTime(LocalDateTime.now());
        trace.setTrackInfo("您的包裹已由【" + dto.getShippingCompany() + "】揽收");
        trace.setLocation("发货仓");
        shippingTraceRepository.insert(trace);

        // 3. 订单状态流转：待发货(1) → 待收货(2)，并回写物流单号到 orders.shipping_no
        //   （OrderService.ship 内部走状态机 SHIP 事件 + 条件 UPDATE，阶段 5 已实现）
        orderService.ship(order.getId(), shipping.getTrackingNo());

        log.info("发货成功: adminId={}, orderId={}, company={}, trackingNo={}",
                adminId, order.getId(), dto.getShippingCompany(), shipping.getTrackingNo());
        return toVO(shipping, null);
    }

    /**
     * 用户端查物流：校验订单归属（只能查自己的订单），返回运单 + 轨迹时间轴。
     */
    public ShippingVO getByOrder(Long userId, Long orderId) {
        Order order = orderRepository.selectByIdAndUserId(orderId, userId);
        if (order == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "订单不存在");
        }
        Shipping shipping = shippingRepository.findByOrderId(orderId);
        if (shipping == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "该订单尚未发货，暂无物流信息");
        }
        return toVO(shipping, shippingTraceRepository.findByShippingId(shipping.getId()));
    }

    /** 管理端运单分页（?status=&page=&pageSize=） */
    public PageResult<ShippingVO> adminList(Integer status, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        List<Shipping> list = shippingRepository.adminPage(status, offset, pageSize);
        long total = shippingRepository.adminCount(status);
        List<ShippingVO> vos = new ArrayList<>();
        for (Shipping s : list) {
            vos.add(toVO(s, null));
        }
        return new PageResult<>(total, vos);
    }

    /** 管理端运单详情（含轨迹时间轴与收货人信息） */
    public ShippingVO adminDetail(Long id) {
        Shipping shipping = shippingRepository.selectByIdWithOrder(id);
        if (shipping == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "运单不存在");
        }
        return toVO(shipping, shippingTraceRepository.findByShippingId(id));
    }

    /**
     * 同步物流轨迹（管理端手动触发 / LogisticsSyncTask 定时触发共用）。
     *
     * <p>优先走真实供应商（按 kuaidi.provider 配置选快递100 或快递鸟）：
     * 返回非空则<b>清旧轨迹 + 全量写入</b>（第三方返回的是截至当前的全部轨迹，
     * 全量覆盖保证与第三方一致），并按最后一条是否含"签收"推进状态；
     * 未配置密钥 / 查询失败则走 <b>mock 模拟推进</b>：按现有节点数生成下一条
     * 演示轨迹，第 5 条到达"已签收"。
     */
    @Transactional
    public void syncTrace(Long shippingId) {
        Shipping shipping = shippingRepository.selectById(shippingId);
        if (shipping == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "运单不存在");
        }
        if (shipping.getStatus() == 2) {
            return; // 已签收无需再同步
        }

        // 1. 真实供应商查询（未配置密钥时返回 null）
        String[] codes = COMPANY_CODES.getOrDefault(shipping.getShippingCompany(), new String[]{null, null});
        List<ShippingTrace> real = "kuaidi100".equals(provider)
                ? kuaidi100Service.query(shipping.getTrackingNo(), codes[0])
                : "kuaidibird".equals(provider)
                ? kuaidiBirdService.query(shipping.getTrackingNo(), codes[1])
                : null;

        if (real != null && !real.isEmpty()) {
            // 真实轨迹：清旧 + 全量写入 + 状态推进
            shippingTraceRepository.deleteByShippingId(shippingId);
            for (ShippingTrace t : real) {
                t.setShippingId(shippingId);
            }
            shippingTraceRepository.insertBatch(real);
            boolean signed = real.get(real.size() - 1).getTrackInfo().contains("签收");
            if (signed) {
                shippingRepository.advanceStatus(shippingId, 2);
            } else if (real.size() > 1) {
                shippingRepository.advanceStatus(shippingId, 1);
            }
            log.info("物流轨迹同步成功(真实): shippingId={}, 节点数={}", shippingId, real.size());
            return;
        }

        // 2. mock 模拟推进（本地演示）：按现有节点数生成下一条演示轨迹
        long count = shippingTraceRepository.countByShippingId(shippingId);
        ShippingTrace mock = mockNextTrace(shipping, count);
        shippingTraceRepository.insert(mock);
        if (count + 1 >= 5) {
            // 第 5 条轨迹 = 签收，运单状态推进为已签收
            shippingRepository.advanceStatus(shippingId, 2);
        } else if (count >= 1) {
            shippingRepository.advanceStatus(shippingId, 1);
        }
        log.info("物流轨迹同步成功(mock演示): shippingId={}, 节点数={}", shippingId, count + 1);
    }

    /**
     * mock 模式生成下一条演示轨迹（本地无快递密钥也能展示完整物流时间轴）。
     */
    private ShippingTrace mockNextTrace(Shipping shipping, long count) {
        ShippingTrace t = new ShippingTrace();
        t.setShippingId(shipping.getId());
        LocalDateTime base = shipping.getCreatedAt();
        String city = MOCK_CITIES[(int) (count % MOCK_CITIES.length)];
        switch ((int) count) {
            case 0 -> {
                t.setTrackTime(base.plusHours(1));
                t.setTrackInfo("您的包裹已由【" + shipping.getShippingCompany() + "】揽收");
                t.setLocation("发货仓");
            }
            case 1 -> {
                t.setTrackTime(base.plusHours(6));
                t.setTrackInfo("运输中，包裹已到达" + city + "转运中心");
                t.setLocation(city + "转运中心");
            }
            case 2 -> {
                t.setTrackTime(base.plusHours(12));
                t.setTrackInfo("运输中，包裹已离开" + city + "，正在发往下一站");
                t.setLocation(city);
            }
            case 3 -> {
                t.setTrackTime(base.plusHours(20));
                t.setTrackInfo("派送中，快递员正在为您派送，请保持电话畅通");
                t.setLocation("收件区域");
            }
            default -> {
                t.setTrackTime(base.plusHours(26));
                t.setTrackInfo("您的包裹已签收，感谢使用" + shipping.getShippingCompany() + "，期待再次为您服务");
                t.setLocation("收件地址");
            }
        }
        return t;
    }

    /** 实体 → 展示对象 */
    private ShippingVO toVO(Shipping s, List<ShippingTrace> traces) {
        ShippingVO vo = new ShippingVO();
        vo.setId(s.getId());
        vo.setOrderId(s.getOrderId());
        vo.setOrderNo(s.getOrderNo());
        vo.setShippingCompany(s.getShippingCompany());
        vo.setTrackingNo(s.getTrackingNo());
        vo.setStatus(s.getStatus());
        vo.setStatusText(s.getStatus() != null && s.getStatus() < STATUS_TEXT.length
                ? STATUS_TEXT[s.getStatus()] : "未知");
        vo.setEstimatedTime(s.getEstimatedTime());
        vo.setCreatedAt(s.getCreatedAt());
        vo.setReceiverName(s.getReceiverName());
        vo.setReceiverPhone(s.getReceiverPhone());
        vo.setReceiverAddress(s.getReceiverAddress());
        if (traces != null) {
            List<TrackingVO> trackVOs = new ArrayList<>();
            for (ShippingTrace t : traces) {
                TrackingVO tv = new TrackingVO();
                tv.setId(t.getId());
                tv.setTrackTime(t.getTrackTime());
                tv.setTrackInfo(t.getTrackInfo());
                tv.setLocation(t.getLocation());
                trackVOs.add(tv);
            }
            vo.setTraces(trackVOs);
        }
        return vo;
    }
}
