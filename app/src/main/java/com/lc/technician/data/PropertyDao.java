package com.lc.technician.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import com.lc.technician.model.PropertyEntity;
import java.util.List;

@Dao
public interface PropertyDao {

    @Query("SELECT * FROM properties WHERE customerId = :customerId ORDER BY direccion ASC")
    List<PropertyEntity> getByCustomer(long customerId);

    @Query("SELECT * FROM properties WHERE id = :id")
    PropertyEntity getById(long id);

    @Query("SELECT * FROM properties WHERE customerId = :customerId AND direccion = :dir LIMIT 1")
    PropertyEntity getByCustomerAndDir(long customerId, String dir);

    /** Case-insensitive, whitespace-normalized lookup — prevents duplicate properties. */
    @Query("SELECT * FROM properties WHERE customerId = :customerId AND LOWER(TRIM(direccion)) = LOWER(TRIM(:dir)) LIMIT 1")
    PropertyEntity getByCustomerAndDirNormalized(long customerId, String dir);

    @Insert
    long insert(PropertyEntity p);

    @Update
    void update(PropertyEntity p);
}
