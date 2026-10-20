package com.lc.technician.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.lc.technician.model.PaymentEntity;
import java.util.List;

@Dao
public interface PaymentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(PaymentEntity payment);

    @Query("SELECT * FROM payments WHERE jobId = :jobId ORDER BY timestamp DESC")
    List<PaymentEntity> getPaymentsForJob(long jobId);

    @Query("SELECT * FROM payments ORDER BY timestamp DESC")
    List<PaymentEntity> getAllPayments();

    @Query("SELECT * FROM payments WHERE status = 'PAID' ORDER BY timestamp DESC")
    List<PaymentEntity> getPaidPayments();

    @Query("SELECT SUM(amount) FROM payments WHERE jobId = :jobId AND status = 'PAID'")
    Double getTotalPaidForJob(long jobId);

    @Query("SELECT * FROM payments WHERE jobId = :jobId AND status = 'PAID' LIMIT 1")
    PaymentEntity getPaidForJob(long jobId);

    // ── Sync v2 ──
    @Query("SELECT * FROM payments WHERE syncState = 0")
    java.util.List<PaymentEntity> pendientesSync();

    @Query("UPDATE payments SET syncState = 2 WHERE id IN (:ids) AND syncState = 0")
    int marcarEnTransito(java.util.List<String> ids);

    @Query("UPDATE payments SET syncState = 0 WHERE syncState = 2")
    int liberarEnTransito();

    @Query("SELECT COUNT(*) FROM payments WHERE syncState = 0")
    int countPendientesSync();

    @Query("SELECT * FROM payments WHERE syncLocalId = :lid LIMIT 1")
    PaymentEntity getBySyncLocalId(long lid);

    @Query("SELECT * FROM payments WHERE jobId = :jobId ORDER BY timestamp ASC")
    java.util.List<PaymentEntity> porOrden(long jobId);

    @androidx.room.Update
    void actualizar(PaymentEntity p);
}
