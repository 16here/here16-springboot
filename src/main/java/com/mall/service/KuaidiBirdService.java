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
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 快递鸟 物流查询服务（阶段 9 物流跟踪）。
 *
 * <p>对接快递鸟「即时查询」接口（api.kdniao.com，RequestType=1002），
 * 代码结构与真实对接完全一致；本地开发未配置 eBusinessId/appKey 时 query 返回 null，
 * 由 ShippingService 走 mock 模拟轨迹。真实上线：填好 kuaidi.kuaidibird 配置即可切换。
 */
@Service
public class KuaidiBirdService {

    /** 快递鸟即时查询接口地址 */
    private static final String QUERY_URL = "https://api.kdniao.com/Ebusiness/EbusinessOrderHandle.aspx";

    /** 商户 ID（application.yml：kuaidi.kuaidibird.e-business-id） */
    @Value("${kuaidi.kuaidibird.e-business-id:}")
    private String eBusinessId;

    /** 商户密钥（application.yml：kuaidi.kuaidibird.app-key） */
    @Value("${kuaidi.kuaidibird.app-key:}")
    private String appKey;

    /**
     * 查询快递轨迹。
     *
     * @param trackingNo 快递单号
     * @param company    快递公司编码（快递鸟公司编码，如 SF/ZTO/YTO）
     * @return 轨迹列表（时间正序）；未配置密钥或查询失败返回 null（调用方走 mock 兜底）
     */
    public List<ShippingTrace> query(String trackingNo, String company) {
        // 本地开发未配置密钥：不发起真实请求，返回 null 交由 mock 兜底
        if (eBusinessId == null || eBusinessId.isBlank() || appKey == null || appKey.isBlank()) {
            return null;
        }
        try {
            // 请求体：{"LogisticCode":"单号","ShipperCode":"公司编码"}
            String requestData = "{\"LogisticCode\":\"" + trackingNo + "\",\"ShipperCode\":\"" + company + "\"}";
            // 签名：DataSign = Base64(MD5(RequestData + AppKey))，字符集 UTF-8
            String dataSign = Base64.getEncoder().encodeToString(md5(requestData + appKey));

            String body = "RequestData=" + java.net.URLEncoder.encode(requestData, java.nio.charset.StandardCharsets.UTF_8)
                    + "&EBusinessID=" + eBusinessId
                    + "&RequestType=1002"
                    + "&DataSign=" + java.net.URLEncoder.encode(dataSign, java.nio.charset.StandardCharsets.UTF_8)
                    + "&DataType=2";

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(QUERY_URL))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8))
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
     * 解析快递鸟响应 JSON。
     * 响应形如：{"Success":true,"State":"3","Traces":[{"AcceptStation":"已签收","AcceptTime":"2026-09-30 10:00:00","Location":"杭州"}]}
     */
    private List<ShippingTrace> parse(String body) {
        List<ShippingTrace> traces = new ArrayList<>();
        if (!body.contains("\"Success\":true")) {
            return traces;
        }
        Pattern item = Pattern.compile("\\{\"AcceptStation\":\"(.*?)\",\"AcceptTime\":\"(.*?)\"(?:,\"Location\":\"(.*?)\")?\\}");
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
        traces.sort((a, b) -> a.getTrackTime().compareTo(b.getTrackTime()));
        return traces;
    }

    private byte[] md5(String src) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        return md.digest(src.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
