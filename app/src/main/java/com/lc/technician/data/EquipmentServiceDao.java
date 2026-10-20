package com.lc.technician.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import com.lc.technician.model.EquipmentServiceEntity;
import java.util.List;

@Dao
public interface EquipmentServiceDao {

    @Query("SELECT * FROM equipment_services WHERE equipmentId = :eqId ORDER BY timestamp DESC")
    List<EquipmentServiceEntity> getForEquipment(long eqId);

    @Query("SELECT * FROM equipment_services ORDER BY timestamp DESC LIMIT :limit")
    List<EquipmentServiceEntity> getRecent(int limit);

    @Query("SELECT * FROM equipment_services WHERE jobId = :jobId")
    List<EquipmentServiceEntity> getForJob(long jobId);

    @Insert
    long insert(EquipmentServiceEntity entity);
}
