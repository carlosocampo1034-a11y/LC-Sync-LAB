package com.lc.technician.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import com.lc.technician.model.EquipmentEntity;
import com.lc.technician.model.MarcaCount;
import java.util.List;

@Dao
public interface EquipmentDao {

    @Query("SELECT * FROM equipment WHERE propertyId = :propertyId ORDER BY nombre ASC")
    List<EquipmentEntity> getByProperty(long propertyId);

    @Query("SELECT * FROM equipment WHERE id = :id")
    EquipmentEntity getById(long id);

    @Query("SELECT * FROM equipment WHERE propertyId = :propertyId AND serial = :serial LIMIT 1")
    EquipmentEntity getByPropertyAndSerial(long propertyId, String serial);

    @Query("SELECT * FROM equipment WHERE propertyId = :propertyId AND nombre = :nombre LIMIT 1")
    EquipmentEntity getByPropertyAndNombre(long propertyId, String nombre);

    @Query("SELECT * FROM equipment WHERE serial = :serial LIMIT 1")
    EquipmentEntity getBySerial(String serial);

    @Query("SELECT * FROM equipment ORDER BY marca ASC, modelo ASC")
    List<EquipmentEntity> getAll();

    @Query("SELECT marca, COUNT(*) as total FROM equipment GROUP BY marca ORDER BY total DESC")
    List<MarcaCount> getEquiposPorMarca();

    @Query("DELETE FROM equipment WHERE id = :id")
    void deleteById(long id);

    @Insert
    long insert(EquipmentEntity e);

    @Update
    void update(EquipmentEntity e);
}
