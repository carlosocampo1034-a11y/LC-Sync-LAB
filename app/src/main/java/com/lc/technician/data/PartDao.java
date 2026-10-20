package com.lc.technician.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import com.lc.technician.model.PartEntity;
import java.util.List;

@Dao
public interface PartDao {

    @Query("SELECT * FROM parts ORDER BY nombre ASC")
    List<PartEntity> getAll();

    @Query("SELECT * FROM parts WHERE id = :id LIMIT 1")
    PartEntity getById(long id);

    @Query("SELECT * FROM parts WHERE sku = :sku LIMIT 1")
    PartEntity getBySku(String sku);

    // BUG-003 FIX: old condition (stockActual <= stockMinimo) produced 38 false
    // positives when both stockActual=0 and stockMinimo=0 (unset parts).
    // New condition requires stockMinimo > 0 so only intentionally-monitored
    // parts trigger a low-stock alert, and uses strict < to exclude equal values.
    @Query("SELECT * FROM parts WHERE stockMinimo > 0 AND stockActual < stockMinimo ORDER BY nombre ASC")
    List<PartEntity> getStockBajo();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(PartEntity part);

    @Update
    void update(PartEntity part);

    @Delete
    void delete(PartEntity part);

    @Query("UPDATE parts SET stockActual = stockActual - :qty, updatedAt = :ts WHERE id = :partId")
    void decrementStock(long partId, int qty, long ts);

    @Query("UPDATE parts SET stockActual = stockActual + :qty, updatedAt = :ts WHERE id = :partId")
    void incrementStock(long partId, int qty, long ts);
}
