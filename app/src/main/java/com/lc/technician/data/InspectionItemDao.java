package com.lc.technician.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import com.lc.technician.model.InspectionItemEntity;
import java.util.List;

@Dao
public interface InspectionItemDao {
    @Insert
    void insertItem(InspectionItemEntity item);

    @Insert
    void insertItems(List<InspectionItemEntity> items);

    @Query("SELECT * FROM inspection_items WHERE jobId = :jobId ORDER BY area, nombre")
    List<InspectionItemEntity> getItemsForJob(long jobId);

    @Query("UPDATE inspection_items SET estado = :estado, hallazgo = :hallazgo, resolucion = :resolucion WHERE id = :id")
    void updateItemEstado(long id, String estado, String hallazgo, String resolucion);

    @Query("DELETE FROM inspection_items WHERE jobId = :jobId")
    void deleteItemsForJob(long jobId);
}
