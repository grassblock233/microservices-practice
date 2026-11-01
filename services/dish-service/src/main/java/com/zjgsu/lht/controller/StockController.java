package com.zjgsu.lht.controller;
import com.zjgsu.lht.dto.StockDtos.*;
import com.zjgsu.lht.service.StockService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/internal")
public class StockController {
    private final StockService stock;
    public StockController(StockService stock) { this.stock=stock; }
    @PostMapping("/dishes/validate")
    public List<Snapshot> validate(@Valid @RequestBody Request r) { return stock.validate(r); }
    @PostMapping("/stock-operations/{orderNo}/deduct")
    public ResponseEntity<Operation> deduct(@PathVariable String orderNo, @Valid @RequestBody Request r) {
        var d=stock.deduct(orderNo,r); return ResponseEntity.status(d.created()?201:200).body(d.operation());
    }
    @PutMapping("/stock-operations/{orderNo}/release")
    public Operation release(@PathVariable String orderNo) { return stock.release(orderNo); }
    @GetMapping("/stock-operations/{orderNo}")
    public Operation get(@PathVariable String orderNo) { return stock.get(orderNo); }
}
