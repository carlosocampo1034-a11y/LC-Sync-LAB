package com.lc.technician.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.lc.technician.model.StockMovementEntity;

import java.util.List;

@Dao
public interface StockMovementDao {
    @Insert
    long insert(StockMovementEntity movement);

    @Query("SELECT * FROM stock_movements ORDER BY timestamp DESC LIMIT :limit")
    List<StockMovementEntity> getRecent(int limit);

    @Query("SELECT * FROM stock_movements WHERE partId = :partId ORDER BY timestamp DESC")
    List<StockMovementEntity> getByPart(long partId);

    @Query("SELECT * FROM stock_movements WHERE locationId = :locationId ORDER BY timestamp DESC")
    List<StockMovementEntity> getByLocation(long locationId);

    @Query("SELECT * FROM stock_movements WHERE referenceId = :jobId AND referenceType = 'JOB' ORDER BY timestamp DESC")
    List<StockMovementEntity> getByJob(long jobId);

    @Query("SELECT COUNT(*) FROM stock_movements")
    int count();
}
