package com.lc.technician.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import com.lc.technician.model.TruckStockEntity;
import java.util.List;

@Dao
public interface TruckStockDao {

    @Query("SELECT * FROM truck_stock WHERE ubicacion = :loc")
    List<TruckStockEntity> getByUbicacion(String loc);

    @Query("SELECT * FROM truck_stock WHERE partId = :partId")
    List<TruckStockEntity> getByPart(long partId);

    @Query("SELECT SUM(cantidad) FROM truck_stock WHERE partId = :partId")
    double getCantidadTotal(long partId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long upsert(TruckStockEntity entity);

    @Update
    void update(TruckStockEntity entity);

    @Delete
    void delete(TruckStockEntity entity);
}
