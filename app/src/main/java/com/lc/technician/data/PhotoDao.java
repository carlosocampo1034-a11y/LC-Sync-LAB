package com.lc.technician.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import com.lc.technician.model.PhotoEntity;
import java.util.List;

@Dao
public interface PhotoDao {

    @Insert
    void insertPhoto(PhotoEntity photo);

    @Query("SELECT * FROM photos WHERE jobId = :jobId ORDER BY timestamp ASC")
    List<PhotoEntity> getPhotosForJob(long jobId);

    @Query("SELECT * FROM photos ORDER BY timestamp ASC")
    List<PhotoEntity> getAll();

    /** Cuarentena: jobId negativo = la foto se conserva en el teléfono pero ya no cuelga de ninguna orden. */
    @Query("UPDATE photos SET jobId = :nuevo WHERE id = :id")
    void moverAJob(long id, long nuevo);

    @Query("DELETE FROM photos WHERE id = :id")
    void deletePhoto(long id);
}
