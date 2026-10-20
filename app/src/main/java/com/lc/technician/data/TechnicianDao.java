package com.lc.technician.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.lc.technician.model.TechnicianEntity;
import java.util.List;

@Dao
public interface TechnicianDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(TechnicianEntity technician);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<TechnicianEntity> technicians);

    @Query("SELECT * FROM technicians WHERE rol IN ('ADMIN','TECNICO','DISPATCHER') ORDER BY nombre ASC")
    List<TechnicianEntity> getAll();

    @Query("SELECT * FROM technicians WHERE activo = 1 AND rol IN ('ADMIN','TECNICO','DISPATCHER') ORDER BY nombre ASC")
    List<TechnicianEntity> getActive();

    @Query("SELECT * FROM technicians WHERE id = :id LIMIT 1")
    TechnicianEntity getById(String id);

    @Query("SELECT * FROM technicians WHERE nombre = :nombre LIMIT 1")
    TechnicianEntity getByNombre(String nombre);

    @Query("UPDATE technicians SET activo = :activo, updatedAt = :ts WHERE id = :id")
    void setActivo(String id, boolean activo, long ts);

    @Query("UPDATE technicians SET nombre = :nombre, email = :email, telefono = :telefono, updatedAt = :ts WHERE id = :id")
    void updateInfo(String id, String nombre, String email, String telefono, long ts);

    @Query("SELECT COUNT(*) FROM technicians")
    int count();

    @Query("UPDATE technicians SET currentJobId = :jobId, status = 'ON_ROUTE', updatedAt = :ts WHERE id = :techId")
    void assignJob(String techId, Long jobId, long ts);

    @Query("UPDATE technicians SET status = :status, updatedAt = :ts WHERE id = :techId")
    void updateStatus(String techId, String status, long ts);

    @Query("SELECT * FROM technicians WHERE synced = 0 AND rol IN ('ADMIN','TECNICO','DISPATCHER') ORDER BY updatedAt ASC")
    List<TechnicianEntity> getUnsynced();

    @Query("UPDATE technicians SET synced = 1, lastSyncedAt = :ts WHERE id = :id")
    void markSynced(String id, long ts);

    @Query("SELECT COUNT(*) FROM technicians WHERE synced = 0 AND rol IN ('ADMIN','TECNICO','DISPATCHER')")
    int countUnsynced();
}
