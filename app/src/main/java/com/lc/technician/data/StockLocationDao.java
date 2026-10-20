package com.lc.technician.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.lc.technician.model.StockLocationEntity;

import java.util.List;

@Dao
public interface StockLocationDao {
    @Insert
    long insert(StockLocationEntity location);

    @Update
    void update(StockLocationEntity location);

    @Query("SELECT * FROM stock_locations WHERE isActive = 1 ORDER BY type, name")
    List<StockLocationEntity> getAllActive();

    @Query("SELECT * FROM stock_locations WHERE type = :type AND isActive = 1")
    List<StockLocationEntity> getByType(String type);

    @Query("SELECT * FROM stock_locations WHERE technicianId = :techId AND isActive = 1 LIMIT 1")
    StockLocationEntity getByTechnician(long techId);

    @Query("SELECT * FROM stock_locations WHERE id = :id")
    StockLocationEntity getById(long id);

    @Query("SELECT COUNT(*) FROM stock_locations")
    int count();
}
