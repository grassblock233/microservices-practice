package com.zjgsu.lht.service;
import com.zjgsu.lht.dto.*;
import com.zjgsu.lht.exception.ApiException;
import com.zjgsu.lht.client.*;
import com.zjgsu.lht.model.*;
import com.zjgsu.lht.repository.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class OrderService {
    private static final Logger log=LoggerFactory.getLogger(OrderService.class);
    private final UserClient users; private final DishClient dishes;
    private final AttemptService attempts; private final OrderWriter writer;
    private final OrderRepository orderRepository; private final OrderItemRepository orderItemRepository;
    public OrderService(UserClient users,DishClient dishes,AttemptService attempts,OrderWriter writer,
                        OrderRepository orders,OrderItemRepository items) {
        this.users=users;this.dishes=dishes;this.attempts=attempts;this.writer=writer;
        this.orderRepository=orders;this.orderItemRepository=items;
    }
    // No transaction spans HTTP. Writer and attempts commit through separate Spring proxies.
    public OrderDtos.Response createOrder(OrderDtos.Request r) {
        var stock=new StockDtos.Request(r.merchantId(),r.items().stream()
                .map(i -> new StockDtos.Item(i.dishId(),i.quantity())).toList()).normalized();
        users.requireActive(r.userId()); dishes.validate(stock);
        var id=UUID.randomUUID().toString();
        try { attempts.start(id,r,stock); }
        catch (RuntimeException e) { throw new ApiException(500,"ORDER_SAVE_FAILED","无法创建下单记录，未扣库存",id,e); }
        StockDtos.Operation deducted;
        try { deducted=dishes.deduct(id,stock); }
        catch (ApiException e) {
            log.warn("event=stock_deduction_failed orderNo={} code={}",id,e.code,e);
            if (e.status==404 || e.status==409) mark(id,"FAILED",e.code,e.code);
            else compensate(id,e);
            throw e.forOrder(id);
        } catch (RuntimeException e) {
            var failure=new ApiException(502,"DEPENDENCY_BAD_RESPONSE","库存操作结果未知",id,e);
            compensate(id,failure); throw failure;
        }
        try {
            var result=writer.save(id,r,deducted.items());
            log.info("event=order_created orderNo={}",id); return result;
        } catch (RuntimeException failure) {
            log.error("event=order_save_failed orderNo={}",id,failure);
            // Commit failure can be ambiguous. Check the local DB before releasing remote stock.
            try {
                var committed=writer.committed(id);
                if (committed.isPresent()) {
                    log.warn("event=order_commit_confirmed orderNo={}",id); return committed.get();
                }
            } catch (RuntimeException lookupFailure) {
                mark(id,"RECOVERY_REQUIRED","ORDER_COMMIT_UNKNOWN","original="+failure.getClass().getSimpleName()+
                        "; lookup="+lookupFailure.getClass().getSimpleName());
                throw new ApiException(503,"ORDER_RECOVERY_REQUIRED","订单提交结果待核对，未释放库存",id,failure);
            }
            var error=new ApiException(500,"ORDER_SAVE_FAILED","订单保存失败，库存已释放",id,failure);
            compensate(id,error); throw error;
        }
    }
    private void compensate(String id,ApiException original) {
        try { dishes.release(id); }
        catch (RuntimeException releaseFailure) {
            log.error("event=compensation_failed orderNo={} originalCode={}",id,original.code,releaseFailure);
            mark(id,"RECOVERY_REQUIRED",original.code,"original="+original.code+"; cause="+causeType(original)+"; compensation="+
                    (releaseFailure instanceof ApiException a ? a.code : releaseFailure.getClass().getSimpleName()));
            throw new ApiException(503,"ORDER_RECOVERY_REQUIRED","库存恢复待处理，请按订单号核对",id,original);
        }
        mark(id,"FAILED",original.code,"original="+original.code+"; cause="+causeType(original)+"; stock=RELEASED");
    }
    private String causeType(Throwable error) {
        return error.getCause()==null ? error.getClass().getSimpleName() : error.getCause().getClass().getSimpleName();
    }
    private void mark(String id,String status,String code,String detail) {
        try { attempts.finish(id,status,code,detail); }
        catch (RuntimeException e) {
            log.error("event=recovery_record_failed orderNo={} intendedStatus={} originalCode={} detail={}",id,status,code,detail,e);
            throw new ApiException(503,"ORDER_RECOVERY_REQUIRED","故障记录写入失败，请按订单号人工核对",id,e);
        }
    }
    /**
     * 按创建时间倒序查询学生订单，同一时间的订单按内部编号倒序排列
     */
    @Transactional(readOnly=true)
    public List<OrderDtos.SummaryResponse> getOrders(String userId) {
        return orderRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId).stream()
                .map(order -> new OrderDtos.SummaryResponse(order.getOrderNo(), order.getTotalAmount(),
                        order.getStatus(), order.getCreatedAt()))
                .toList();
    }

    /**
     * 仅使用订单及明细中的历史字段生成详情，不读取菜品的当前名称或价格
     */
    @Transactional(readOnly=true)
    public OrderDtos.DetailResponse getOrder(String orderNo) {
        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new ApiException(404,"ORDER_NOT_FOUND","订单不存在"));
        List<OrderDtos.ItemResponse> items = orderItemRepository
                .findByOrderIdOrderByDishIdAsc(order.getId()).stream()
                .map(item -> new OrderDtos.ItemResponse(item.getDishId(), item.getDishName(),
                        item.getUnitPrice(), item.getQuantity(), item.getSubtotal()))
                .toList();
        return new OrderDtos.DetailResponse(order.getOrderNo(), order.getUserId(), order.getMerchantId(),
                order.getTotalAmount(), order.getStatus(), order.getDeliveryAddress(), order.getRemark(),
                order.getCreatedAt(), items);
    }


}
