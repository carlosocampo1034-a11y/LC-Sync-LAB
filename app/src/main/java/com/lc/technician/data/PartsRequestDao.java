package com.lc.technician.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.lc.technician.model.PartsRequestEntity;

import java.util.List;

@Dao
public interface PartsRequestDao {
    @Insert
    long insert(PartsRequestEntity request);

    @Update
    void update(PartsRequestEntity request);

    @Query("SELECT * FROM parts_requests ORDER BY createdAt DESC")
    List<PartsRequestEntity> getAll();

    @Query("SELECT * FROM parts_requests WHERE status = :status ORDER BY createdAt DESC")
    List<PartsRequestEntity> getByStatus(String status);

    @Query("SELECT * FROM parts_requests WHERE jobId = :jobId ORDER BY createdAt DESC")
    List<PartsRequestEntity> getByJob(long jobId);

    @Query("SELECT * FROM parts_requests WHERE id = :id")
    PartsRequestEntity getById(long id);

    @Query("SELECT COUNT(*) FROM parts_requests WHERE status = 'PENDING'")
    int countPending();

    @Query("SELECT COUNT(*) FROM parts_requests")
    int count();
}
