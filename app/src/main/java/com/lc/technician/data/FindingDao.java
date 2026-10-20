package com.lc.technician.data;

import androidx.lifecycle.LiveData;
import androidx.room.*;
import com.lc.technician.model.FindingEntity;
import java.util.List;

@Dao
public interface FindingDao {
    @Insert
    long insert(FindingEntity finding);

    @Update
    void update(FindingEntity finding);

    @Delete
    void delete(FindingEntity finding);

    @Query("SELECT * FROM findings WHERE job_id = :jobId ORDER BY created_at DESC")
    LiveData<List<FindingEntity>> getFindingsForJob(long jobId);

    @Query("SELECT * FROM findings WHERE job_id = :jobId ORDER BY created_at DESC")
    List<FindingEntity> getFindingsForJobSync(long jobId);

    @Query("SELECT * FROM findings WHERE id = :id")
    FindingEntity getFindingByIdSync(long id);

    @Query("SELECT COUNT(*) FROM findings WHERE job_id = :jobId AND status = 'OPEN'")
    int getOpenFindingCountForJob(long jobId);

    @Query("SELECT COUNT(*) FROM findings WHERE job_id = :jobId AND severity = :severity")
    int getFindingCountBySeverity(long jobId, String severity);

    @Query("DELETE FROM findings WHERE id = :id")
    void deleteById(long id);
}
