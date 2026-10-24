package com.zjgsu.lht.controller;

import com.zjgsu.lht.dto.OrderDtos;
import com.zjgsu.lht.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 提交订单，创建待支付订单并扣减库存
     *
     * <p>请求示例：</p>
     * <pre>{@code
     * curl -i -X POST 'http://localhost:8080/api/orders' \
     *   -H 'Content-Type: application/json' \
     *   -d '{"userId":"student-1","merchantId":10,
     *        "items":[{"dishId":1,"quantity":2}],
     *        "deliveryAddress":"学生宿舍 1 幢","remark":"少辣"}'
     * }</pre>
     * <p>参数要求：userId 非空白且最多 64 字符；merchantId 为正整数；items 非空，
     * 每项 dishId、quantity 均为正整数；deliveryAddress 非空白且最多 255 字符；
     * remark 可省略或为 null，最多 500 字符。明细只需提供菜品编号和数量，
     * 名称、单价、小计及总金额由服务端确定；重复菜品会合并数量。</p>
     * <p>失败时以 ProblemDetail 返回原因：参数或 JSON 格式错误为 HTTP 400；
     * 菜品不存在为 HTTP 404；跨商家、已下架或库存不足为 HTTP 409。
     * 任一菜品校验或持久化失败，订单、明细和库存变更会回滚</p>
     *
     * @param request JSON 下单请求，包含学生、商家、菜品明细、配送地址及可选备注
     * @return HTTP 201，下单结果及指向订单详情的 Location
     */
    @PostMapping
    public ResponseEntity<OrderDtos.Response> createOrder(@Valid @RequestBody OrderDtos.Request request) {
        OrderDtos.Response response = orderService.createOrder(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{orderNo}").buildAndExpand(response.orderNo()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    /**
     * 按学生编号查询订单
     *
     * <p>请求示例：</p>
     * <pre>{@code
     * curl 'http://localhost:8080/api/orders?userId=student-1'
     * }</pre>
     * <p>userId 必填、非空白且最多 64 字符；不符合要求时返回 HTTP 400 和 ProblemDetail。
     * 仅返回该学生的订单，按创建时间倒序、同一时间按内部编号倒序排列；
     * 无订单时返回 HTTP 200 和空数组 []，订单明细需通过详情接口获取</p>
     *
     * @param userId 学生编号，通过 URL 查询参数传入
     * @return 包含订单号、总金额、状态和创建时间的订单摘要列表
     */
    @GetMapping
    public List<OrderDtos.SummaryResponse> getOrders(
            @RequestParam("userId") @NotBlank @Size(max = 64) String userId) {
        return orderService.getOrders(userId);
    }

    /**
     * 按订单号UUID查询订单详情及下单时保存的菜品明细快照
     *
     * <p>请求示例：</p>
     * <pre>{@code
     * curl 'http://localhost:8080/api/orders/123e4567-e89b-12d3-a456-426614174000'
     * }</pre>
     * <p>订单不存在时返回 HTTP 404 和 ProblemDetail</p>
     *
     * @param orderNo 对外订单号，通过 URL 路径传入
     * @return 订单基本信息及菜品明细快照
     */
    @GetMapping("/{orderNo}")
    public OrderDtos.DetailResponse getOrder(@PathVariable("orderNo") String orderNo) {
        return orderService.getOrder(orderNo);
    }
}
