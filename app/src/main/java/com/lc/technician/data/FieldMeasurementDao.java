package com.lc.technician.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import com.lc.technician.model.FieldMeasurementEntity;
import java.util.List;

@Dao
public interface FieldMeasurementDao {
    @Insert
    long insertMeasurement(FieldMeasurementEntity m);

    @Query("SELECT * FROM field_measurements WHERE jobId = :jobId ORDER BY id")
    List<FieldMeasurementEntity> getMeasurementsForJob(long jobId);

    @Query("DELETE FROM field_measurements WHERE id = :id")
    void deleteMeasurement(long id);
}
