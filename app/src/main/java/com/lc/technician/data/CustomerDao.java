package com.lc.technician.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import com.lc.technician.model.CustomerEntity;
import java.util.List;

@Dao
public interface CustomerDao {

    @Query("SELECT * FROM customers ORDER BY nombre ASC")
    List<CustomerEntity> getAll();

    @Query("SELECT * FROM customers WHERE id = :id")
    CustomerEntity getById(long id);

    @Query("SELECT * FROM customers WHERE nombre = :nombre LIMIT 1")
    CustomerEntity getByNombre(String nombre);

    /** Case-insensitive search — use this when creating a new customer to avoid duplicates. */
    @Query("SELECT * FROM customers WHERE LOWER(nombre) = LOWER(:nombre) LIMIT 1")
    CustomerEntity getByNombreCaseInsensitive(String nombre);

    @Insert
    long insert(CustomerEntity c);

    @Update
    void update(CustomerEntity c);
}
