package com.lc.technician.data;

import androidx.room.*;
import com.lc.technician.model.ProposalOptionEntity;
import java.util.List;

@Dao
public interface ProposalOptionDao {
    @Query("SELECT * FROM proposal_options WHERE proposalId = :proposalId ORDER BY CASE nivel WHEN 'GOOD' THEN 1 WHEN 'BETTER' THEN 2 ELSE 3 END")
    List<ProposalOptionEntity> getForProposal(long proposalId);

    @Insert
    long insert(ProposalOptionEntity o);

    @Insert
    void insertAll(List<ProposalOptionEntity> options);

    @Query("DELETE FROM proposal_options WHERE proposalId = :proposalId")
    void deleteForProposal(long proposalId);
}
