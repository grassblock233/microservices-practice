package com.zjgsu.lht.repository;
import com.zjgsu.lht.model.StockOperation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
public interface StockOperationRepository extends JpaRepository<StockOperation,String> {
    @Modifying
    @Query(value="insert into stock_operations (order_no,request_digest,status,updated_at) values (:id,:digest,:status,:time)",nativeQuery=true)
    int insertClaim(@Param("id") String id, @Param("digest") String digest,
                    @Param("status") String status, @Param("time") java.time.LocalDateTime time);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from StockOperation o where o.orderNo = :id")
    Optional<StockOperation> lockByOrderNo(@Param("id") String id);
}
