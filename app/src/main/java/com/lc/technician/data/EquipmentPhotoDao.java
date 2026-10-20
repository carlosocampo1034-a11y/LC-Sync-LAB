package com.lc.technician.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;
import com.lc.technician.model.EquipmentPhotoEntity;

@Dao
public interface EquipmentPhotoDao {
    @Insert
    long insert(EquipmentPhotoEntity photo);

    @Query("SELECT * FROM equipment_photos WHERE equipment_id = :equipmentId ORDER BY timestamp DESC")
    List<EquipmentPhotoEntity> getPhotosForEquipment(long equipmentId);

    @Query("SELECT * FROM equipment_photos WHERE equipment_id = :equipmentId ORDER BY timestamp DESC LIMIT 1")
    EquipmentPhotoEntity getLatestPhotoForEquipment(long equipmentId);
}
