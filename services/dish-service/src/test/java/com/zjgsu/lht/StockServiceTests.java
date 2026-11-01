package com.zjgsu.lht;
import com.zjgsu.lht.dto.StockDtos.*;
import com.zjgsu.lht.exception.ApiException;
import com.zjgsu.lht.model.*;
import com.zjgsu.lht.repository.*;
import com.zjgsu.lht.service.StockService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest @ActiveProfiles("test")
class StockServiceTests {
    @Autowired StockService stock;
    @Autowired DishRepository dishes;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean StockOperationRepository operations;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired StockOperationItemRepository items;
    Long first,second;
    @BeforeEach void setup() {
        org.mockito.Mockito.reset(operations);
        items.deleteAll();operations.deleteAll();dishes.deleteAll();
        first=seed(10,"鸡腿饭",10,DishStatus.ON_SALE);second=seed(10,"牛肉面",10,DishStatus.ON_SALE);
    }
    Long seed(long merchant,String name,int quantity,DishStatus status) {
        var d=new Dish();d.setMerchantId(merchant);d.setName(name);d.setPrice(new BigDecimal("12.50"));
        d.setStock(quantity);d.setStatus(status);return dishes.saveAndFlush(d).getId();
    }
    Request request(long id,int q) { return new Request(10L,List.of(new Item(id,q))); }
    String id() { return UUID.randomUUID().toString(); }
    int quantity(Long id) { return dishes.findById(id).orElseThrow().getStock(); }
    void code(String code,Runnable action) { assertEquals(code,assertThrows(ApiException.class,action::run).code); }
    @Test void mergesAndReturnsOriginalSnapshotsForRepeatedDeduction() {
        var op=id(); var r=new Request(10L,List.of(new Item(first,1),new Item(first,2)));
        var a=stock.deduct(op,r); assertTrue(a.created());assertEquals(7,quantity(first));
        var dish=dishes.findById(first).orElseThrow();dish.setPrice(new BigDecimal("99.00"));dishes.saveAndFlush(dish);
        var repeated=stock.deduct(op,request(first,3));assertFalse(repeated.created());assertEquals(a.operation(),repeated.operation());
        assertEquals(7,quantity(first));assertEquals(1,operations.count());assertEquals(1,items.count());
    }
    @Test void failedBatchRollsBackAllItemsAndOperation() {
        var r=new Request(10L,List.of(new Item(first,2),new Item(second,11)));
        code("INSUFFICIENT_STOCK",() -> stock.deduct(id(),r));
        assertEquals(10,quantity(first));assertEquals(10,quantity(second));assertEquals(0,operations.count());assertEquals(0,items.count());
    }
    @Test void rejectsMissingOffSaleAndWrongMerchant() {
        code("DISH_NOT_FOUND",() -> stock.validate(request(Long.MAX_VALUE,1)));
        var off=seed(10,"下架",10,DishStatus.OFF_SALE);
        code("DISH_OFF_SALE",() -> stock.deduct(id(),request(off,1)));
        var foreign=seed(20,"其他商家",10,DishStatus.ON_SALE);
        code("MERCHANT_MISMATCH",() -> stock.deduct(id(),request(foreign,1)));
        assertEquals(0,operations.count());
    }
    @Test void differentContentCannotReuseAnOperationNumber() {
        var op=id();stock.deduct(op,request(first,1));
        code("OPERATION_CONFLICT",() -> stock.deduct(op,request(first,2)));assertEquals(9,quantity(first));
    }
    @Test void releaseIsIdempotent() {
        var op=id();stock.deduct(op,request(first,3));
        assertEquals("RELEASED",stock.release(op).status());stock.release(op);assertEquals(10,quantity(first));
        code("OPERATION_RELEASED",() -> stock.deduct(op,request(first,3)));
    }
    @Test void releaseBeforeDeductCreatesPersistentTombstone() {
        var op=id();stock.release(op);stock.release(op);
        code("OPERATION_RELEASED",() -> stock.deduct(op,request(first,1)));
        assertEquals("RELEASED",stock.get(op).status());assertEquals(10,quantity(first));assertEquals(0,items.count());
    }
    @Test void validationDoesNotReserveStockAndDeductRechecks() {
        var r=request(first,7);stock.validate(r);assertEquals(10,quantity(first));
        stock.deduct(id(),request(first,5));code("INSUFFICIENT_STOCK",() -> stock.deduct(id(),r));assertEquals(5,quantity(first));
    }
    @Test void rejectsOverflowAndInvalidQuantities() {
        code("INVALID_REQUEST",() -> stock.validate(new Request(10L,List.of(new Item(first,Integer.MAX_VALUE),new Item(first,1)))));
        code("INVALID_REQUEST",() -> stock.validate(request(first,0)));
        code("INVALID_REQUEST",() -> stock.validate(new Request(10L,List.of())));
    }
    @Test void releaseCommittedBetweenLookupAndClaimCannotBeOverwritten() {
        var op=id();var firstLookup=new java.util.concurrent.atomic.AtomicBoolean(true);
        var independent=new org.springframework.transaction.support.TransactionTemplate(manager);
        independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        org.mockito.Mockito.doAnswer(invocation -> {
            if (firstLookup.getAndSet(false)) {
                independent.executeWithoutResult(s -> jdbc.update(
                    "insert into stock_operations (order_no,status,updated_at) values (?,'RELEASED',CURRENT_TIMESTAMP)",op));
                return Optional.empty(); // Return the stale, pre-commit lookup result.
            }
            return operations.findById(op);
        }).when(operations).lockByOrderNo(op);
        code("OPERATION_RELEASED",() -> stock.deduct(op,request(first,3)));
        assertEquals("RELEASED",stock.get(op).status());assertEquals(10,quantity(first));assertEquals(0,items.count());
    }
    @Test void concurrentOrdersNeverOversell() throws Exception {
        try (var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1); var work=(Callable<Boolean>) () -> {
                gate.await();try { stock.deduct(id(),request(first,7));return true; }
                catch (ApiException e) { assertEquals("INSUFFICIENT_STOCK",e.code);return false; }
            };
            var a=pool.submit(work);var b=pool.submit(work);gate.countDown();
            assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));assertEquals(3,quantity(first));assertEquals(1,operations.count());
        }
    }
    @Test void concurrentSameOperationDeductsOnce() throws Exception {
        var op=id();
        try (var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);Callable<Operation> work=() -> { gate.await();return stock.deduct(op,request(first,3)).operation(); };
            var a=pool.submit(work);var b=pool.submit(work);gate.countDown();
            assertEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));assertEquals(7,quantity(first));assertEquals(1,items.count());
        }
    }
    @Test void concurrentReleaseAndDeductEndReleasedWithFullStock() throws Exception {
        var op=id();
        try (var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var a=pool.submit(() -> { gate.await(); try { stock.deduct(op,request(first,3)); }
                catch (ApiException e) { assertEquals("OPERATION_RELEASED",e.code); } return true; });
            var b=pool.submit(() -> { gate.await();stock.release(op);return true; });gate.countDown();
            a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);
            assertEquals("RELEASED",stock.get(op).status());assertEquals(10,quantity(first));
        }
    }
}
