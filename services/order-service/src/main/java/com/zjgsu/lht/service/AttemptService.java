package com.zjgsu.lht.service;
import com.zjgsu.lht.model.OrderAttempt;
import com.zjgsu.lht.dto.*;
import com.zjgsu.lht.repository.OrderAttemptRepository;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDateTime;
@Service
public class AttemptService {
    private final OrderAttemptRepository attempts; private final ObjectMapper json;
    public AttemptService(OrderAttemptRepository attempts,ObjectMapper json) { this.attempts=attempts;this.json=json; }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void start(String id,OrderDtos.Request request,StockDtos.Request stock) {
        var a=new OrderAttempt(); a.orderNo=id; a.userId=request.userId(); a.merchantId=request.merchantId();
        a.requestItems=json.writeValueAsString(stock.items()); a.status="PROCESSING";
        a.updatedAt=LocalDateTime.now(); attempts.saveAndFlush(a);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void finish(String id,String status,String code,String detail) {
        var a=attempts.findById(id).orElseThrow(); a.status=status; a.errorCode=code;
        a.failureDetail=detail==null?null:detail.substring(0,Math.min(1000,detail.length()));
        a.updatedAt=LocalDateTime.now(); attempts.saveAndFlush(a);
    }
}
