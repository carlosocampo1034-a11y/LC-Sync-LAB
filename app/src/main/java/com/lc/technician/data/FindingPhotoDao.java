package com.lc.technician.data;

import androidx.room.*;
import com.lc.technician.model.FindingPhotoEntity;
import java.util.List;

@Dao
public interface FindingPhotoDao {
    @Insert
    long insert(FindingPhotoEntity photo);

    @Query("SELECT * FROM finding_photos WHERE finding_id = :findingId ORDER BY timestamp DESC")
    List<FindingPhotoEntity> getPhotosForFinding(long findingId);

    @Query("DELETE FROM finding_photos WHERE finding_id = :findingId")
    void deletePhotosForFinding(long findingId);
}
