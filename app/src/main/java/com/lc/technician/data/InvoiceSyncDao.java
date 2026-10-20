package com.lc.technician.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.lc.technician.model.InvoiceSyncEntity;

import java.util.List;

@Dao
public interface InvoiceSyncDao {
    @Query("SELECT * FROM invoice_sync WHERE jobId = :jobId")
    InvoiceSyncEntity get(long jobId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(InvoiceSyncEntity e);

    @Query("SELECT * FROM invoice_sync WHERE syncState = 0")
    List<InvoiceSyncEntity> pendientes();

    @Query("UPDATE invoice_sync SET syncState = 2 WHERE jobId IN (:ids) AND syncState = 0")
    int marcarEnTransito(List<Long> ids);

    @Query("UPDATE invoice_sync SET syncState = 0 WHERE syncState = 2")
    int liberarEnTransito();

    @Query("SELECT COUNT(*) FROM invoice_sync WHERE syncState = 0 OR pdfSyncState = 0")
    int countPendientes();

    @Query("SELECT * FROM invoice_sync WHERE pdfSyncState = 0")
    List<InvoiceSyncEntity> pdfPendientes();

    @Query("UPDATE invoice_sync SET pdfSyncState = 2 WHERE jobId IN (:ids) AND pdfSyncState = 0")
    int marcarPdfEnTransito(List<Long> ids);

    @Query("UPDATE invoice_sync SET pdfSyncState = 0 WHERE pdfSyncState = 2")
    int liberarPdfEnTransito();
}
