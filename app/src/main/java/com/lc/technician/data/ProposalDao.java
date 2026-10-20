package com.lc.technician.data;

import androidx.room.*;
import com.lc.technician.model.ProposalEntity;
import java.util.List;

@Dao
public interface ProposalDao {
    @Query("SELECT * FROM proposals WHERE jobId = :jobId LIMIT 1")
    ProposalEntity getForJob(long jobId);

    @Query("SELECT * FROM proposals WHERE id = :id")
    ProposalEntity getById(long id);

    @Insert
    long insert(ProposalEntity p);

    @Update
    void update(ProposalEntity p);

    @Query("DELETE FROM proposals WHERE jobId = :jobId")
    void deleteForJob(long jobId);

    @Query("UPDATE proposals SET isLocked = 1, lockedAt = :ts WHERE id = :proposalId")
    void lockProposal(long proposalId, long ts);

    // ── Sync v2 (Fase 6) ──
    @Query("SELECT * FROM proposals WHERE syncState = 0")
    java.util.List<ProposalEntity> pendientesDeEnvio();

    @Query("UPDATE proposals SET syncState = 2 WHERE id IN (:ids) AND syncState = 0")
    int marcarEnTransito(java.util.List<Long> ids);

    @Query("UPDATE proposals SET syncState = 0 WHERE syncState = 2")
    int liberarEnTransito();

    @Query("SELECT COUNT(*) FROM proposals WHERE syncState = 0")
    int countPendientes();

    @Query("SELECT * FROM proposals WHERE serverId = :serverId LIMIT 1")
    ProposalEntity getByServerId(long serverId);

    @Query("SELECT isLocked FROM proposals WHERE id = :proposalId")
    boolean isLocked(long proposalId);
}
