package com.lc.technician.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import com.lc.technician.model.CoverageTypeEntity;
import java.util.List;

@Dao
public interface CoverageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(CoverageTypeEntity c);

    @Update
    void update(CoverageTypeEntity c);

    @Query("SELECT * FROM coverage_types WHERE isActive = 1 ORDER BY id ASC")
    List<CoverageTypeEntity> getAllActive();

    @Query("SELECT * FROM coverage_types WHERE id = :id LIMIT 1")
    CoverageTypeEntity getById(long id);

    @Query("SELECT * FROM coverage_types WHERE code = :code LIMIT 1")
    CoverageTypeEntity getByCode(String code);
}
