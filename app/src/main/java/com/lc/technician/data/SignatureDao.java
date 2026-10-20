package com.lc.technician.data;
import androidx.room.*;
import com.lc.technician.model.SignatureEntity;

@Dao
public interface SignatureDao {
    @Query("SELECT * FROM signatures WHERE jobId = :jobId ORDER BY timestamp DESC LIMIT 1")
    SignatureEntity getForJob(long jobId);

    @Query("SELECT * FROM signatures WHERE proposalId = :proposalId ORDER BY timestamp DESC LIMIT 1")
    SignatureEntity getForProposal(long proposalId);

    @Insert
    long insert(SignatureEntity s);

    @Delete
    void delete(SignatureEntity s);

    // ── Sync v2 ──
    @Query("SELECT * FROM signatures WHERE syncState = 0")
    java.util.List<SignatureEntity> pendientes();

    @Query("UPDATE signatures SET syncState = 2 WHERE id IN (:ids) AND syncState = 0")
    int marcarEnTransito(java.util.List<Long> ids);

    @Query("UPDATE signatures SET syncState = 0 WHERE syncState = 2")
    int liberarEnTransito();

    @Query("SELECT COUNT(*) FROM signatures WHERE syncState = 0")
    int countPendientes();

    @Query("SELECT * FROM signatures WHERE id = :id")
    SignatureEntity getById(long id);

    @androidx.room.Update
    void update(SignatureEntity s);
}
