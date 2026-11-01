package com.zjgsu.lht.service;

import com.zjgsu.lht.dto.StockDtos.*;
import com.zjgsu.lht.exception.ApiException;
import com.zjgsu.lht.model.*;
import com.zjgsu.lht.repository.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;

@Service
public class StockService {
    private static final Logger log = LoggerFactory.getLogger(StockService.class);
    private final DishRepository dishes;
    private final StockOperationRepository operations;
    private final StockOperationItemRepository items;
    private final TransactionTemplate tx;
    public StockService(DishRepository dishes, StockOperationRepository operations,
                        StockOperationItemRepository items, PlatformTransactionManager manager) {
        this.dishes=dishes; this.operations=operations; this.items=items;
        this.tx=new TransactionTemplate(manager);
        // A fresh transaction after a uniqueness/deadlock conflict must see the winner's committed row.
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public List<Snapshot> validate(Request raw) {
        var request=raw.normalized();
        return tx.execute(s -> checked(request, false).stream().map(d -> snapshot(d, request)).toList());
    }
    public record Deduction(Operation operation, boolean created) {}
    public Deduction deduct(String orderNo, Request raw) {
        checkId(orderNo); var request=raw.normalized(); var digest=digest(request);
        var result=coordinated(() -> {
            var existing=operations.lockByOrderNo(orderNo);
            if (existing.isPresent()) {
                var op=existing.get();
                if (op.requestDigest != null && !digest.equals(op.requestDigest))
                    throw new ApiException(409,"OPERATION_CONFLICT","相同操作号的请求内容不一致");
                if (op.status.equals("RELEASED"))
                    throw new ApiException(409,"OPERATION_RELEASED","该操作已释放，禁止迟到扣减");
                return new Deduction(response(op),false);
            }
            var op=new StockOperation(); op.orderNo=orderNo; op.requestDigest=digest;
            op.status="DEDUCTED"; op.updatedAt=LocalDateTime.now();
            claim(op); // Claim the unique key before touching any inventory.
            var locked=checked(request,true); // Sorted locks avoid inverse dish lock ordering.
            for (int i=0;i<locked.size();i++) {
                var d=locked.get(i); var q=request.items().get(i).quantity();
                d.setStock(d.getStock()-q);
                var item=new StockOperationItem(); item.orderNo=orderNo; item.dishId=d.getId();
                item.dishName=d.getName(); item.unitPrice=d.getPrice(); item.quantity=q; items.save(item);
            }
            items.flush(); return new Deduction(response(op),true);
        });
        log.info("event=stock_deducted orderNo={} created={}",orderNo,result.created()); return result;
    }
    public Operation release(String orderNo) {
        checkId(orderNo);
        var result=coordinated(() -> {
            var existing=operations.lockByOrderNo(orderNo);
            if (existing.isEmpty()) {
                // Tombstone prevents a timed-out, late deduction from succeeding later.
                var op=new StockOperation(); op.orderNo=orderNo; op.status="RELEASED";
                op.updatedAt=LocalDateTime.now(); claim(op); return response(op);
            }
            var op=existing.get();
            if (!op.status.equals("RELEASED")) {
                for (var item:items.findByOrderNoOrderByDishIdAsc(orderNo)) {
                    var dish=dishes.lockById(item.dishId).orElseThrow();
                    dish.setStock(Math.addExact(dish.getStock(), item.quantity));
                }
                op.status="RELEASED"; op.updatedAt=LocalDateTime.now();
            }
            return response(op);
        });
        log.info("event=stock_released orderNo={}",orderNo); return result;
    }
    public Operation get(String orderNo) {
        checkId(orderNo);
        return tx.execute(s -> response(operations.findById(orderNo).orElseThrow(
                () -> new ApiException(404,"STOCK_OPERATION_NOT_FOUND","库存操作不存在"))));
    }
    private <T> T coordinated(Supplier<T> action) {
        // A concurrent insert can lose the unique-key race; its entire transaction is retried.
        // This is local DB coordination only, never an HTTP write retry.
        for (int i=0;;i++) {
            try { return tx.execute(s -> action.get()); }
            catch (DataIntegrityViolationException | PessimisticLockingFailureException e) {
                if (i>=2) throw e;
            }
        }
    }
    private void claim(StockOperation op) {
        // Assigned IDs must use INSERT, never merge: a winner may have committed since the lookup.
        // A unique-key conflict rolls back this transaction and is coordinated in a new one.
        operations.insertClaim(op.orderNo,op.requestDigest,op.status,op.updatedAt);
    }
    private List<Dish> checked(Request request, boolean lock) {
        List<Dish> result=new ArrayList<>();
        for (var item:request.items()) {
            var d=(lock ? dishes.lockById(item.dishId()) : dishes.findById(item.dishId()))
                    .orElseThrow(() -> new ApiException(404,"DISH_NOT_FOUND","菜品不存在"));
            if (!d.getMerchantId().equals(request.merchantId())) throw new ApiException(409,"MERCHANT_MISMATCH","菜品不属于该商家");
            if (d.getStatus()!=DishStatus.ON_SALE) throw new ApiException(409,"DISH_OFF_SALE","菜品已下架");
            if (d.getStock()<item.quantity()) throw new ApiException(409,"INSUFFICIENT_STOCK","菜品库存不足");
            result.add(d);
        }
        return result;
    }
    private Snapshot snapshot(Dish d, Request r) {
        var q=r.items().stream().filter(i -> i.dishId().equals(d.getId())).findFirst().orElseThrow().quantity();
        return new Snapshot(d.getId(),d.getName(),d.getPrice(),q);
    }
    private Operation response(StockOperation op) {
        return new Operation(op.orderNo,op.status,items.findByOrderNoOrderByDishIdAsc(op.orderNo).stream()
                .map(i -> new Snapshot(i.dishId,i.dishName,i.unitPrice,i.quantity)).toList());
    }
    private String digest(Request r) {
        String canonical=r.merchantId()+":"+r.items().stream().map(i -> i.dishId()+"="+i.quantity()).toList();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private void checkId(String id) {
        if (id==null || !id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new ApiException(400,"INVALID_REQUEST","操作号必须为 UUID");
    }
}
