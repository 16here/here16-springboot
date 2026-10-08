package com.mall.service;

import com.mall.entity.ShippingTrace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 快递100 物流查询服务（阶段 9 物流跟踪）。
 *
 * <p>对接快递100「即时查询」接口（poll.kuaidi100.com），代码结构与真实对接完全一致；
 * 本地开发未配置 key/customer 时 query 返回 null，由 ShippingService 走 mock 模拟轨迹，
 * 保证无密钥也能跑通闭环。真实上线：application.yml 填好 kuaidi.kuaidi100.key/customer 即可切换。
 */
@Service
public class Kuaidi100Service {

    /** 快递100 即时查询接口地址 */
    private static final String QUERY_URL = "https://poll.kuaidi100.com/poll/query.do";

    /** 商户 key（application.yml：kuaidi.kuaidi100.key） */
    @Value("${kuaidi.kuaidi100.key:}")
    private String key;

    /** 商户 customer（application.yml：kuaidi.kuaidi100.customer） */
    @Value("${kuaidi.kuaidi100.customer:}")
    private String customer;

    /**
     * 查询快递轨迹。
     *
     * @param trackingNo 快递单号
     * @param company    快递公司编码（快递100 公司编码，如 shunfeng/zto/yuantong）
     * @return 轨迹列表（时间正序）；未配置密钥或查询失败返回 null（调用方走 mock 兜底）
     */
    public List<ShippingTrace> query(String trackingNo, String company) {
        // 本地开发未配置密钥：不发起真实请求，返回 null 交由 mock 兜底
        if (key == null || key.isBlank() || customer == null || customer.isBlank()) {
            return null;
        }
        try {
            // 签名：sign = MD5(key + num + com + customer).toUpperCase()
            String signSrc = key + trackingNo + company + customer;
            String sign = md5(signSrc).toUpperCase();
            String url = QUERY_URL + "?customer=" + customer
                    + "&sign=" + sign
                    + "&com=" + company
                    + "&num=" + trackingNo;

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return null;
            }
            return parse(resp.body());
        } catch (Exception e) {
            // 真实环境网络异常：返回 null 走 mock，避免定时任务抛异常中断
            return null;
        }
    }

    /**
     * 解析快递100 响应 JSON。
     * 响应形如：{"status":"200","message":"ok","data":[{"context":"已签收","time":"2026-09-30 10:00:00","location":"杭州"}]}
     */
    private List<ShippingTrace> parse(String body) {
        List<ShippingTrace> traces = new ArrayList<>();
        Pattern item = Pattern.compile("\\{\"context\":\"(.*?)\",\"time\":\"(.*?)\"(?:,\"location\":\"(.*?)\")?\\}");
        Matcher m = item.matcher(body);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        while (m.find()) {
            ShippingTrace t = new ShippingTrace();
            t.setTrackInfo(m.group(1));
            try {
                t.setTrackTime(LocalDateTime.parse(m.group(2), fmt));
            } catch (Exception e) {
                t.setTrackTime(LocalDateTime.now());
            }
            t.setLocation(m.group(3) == null ? null : m.group(3));
            traces.add(t);
        }
        // 时间正序（快递100 返回倒序，最新在前）
        traces.sort((a, b) -> a.getTrackTime().compareTo(b.getTrackTime()));
        return traces;
    }

    private String md5(String src) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] bytes = md.digest(src.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
