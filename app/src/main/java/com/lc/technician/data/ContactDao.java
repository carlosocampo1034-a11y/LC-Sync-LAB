package com.lc.technician.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import com.lc.technician.model.ContactEntity;
import java.util.List;

@Dao
public interface ContactDao {

    @Insert
    long insertContact(ContactEntity contact);

    /** Get active contacts for a specific job */
    @Query("SELECT * FROM contacts WHERE jobId = :jobId AND (isDeleted = 0 OR isDeleted IS NULL) ORDER BY esPrincipal DESC, rol ASC")
    List<ContactEntity> getContactsForJob(long jobId);

    /** Get all active contacts for a customer (across all their jobs — for reuse) */
    @Query("SELECT * FROM contacts WHERE customerId = :customerId AND (isDeleted = 0 OR isDeleted IS NULL) ORDER BY esPrincipal DESC, nombre ASC")
    List<ContactEntity> getContactsForCustomer(long customerId);

    /** Get contacts specific to a property */
    @Query("SELECT * FROM contacts WHERE customerId = :customerId AND propertyId = :propertyId AND (isDeleted = 0 OR isDeleted IS NULL)")
    List<ContactEntity> getContactsForProperty(long customerId, long propertyId);

    /** Soft delete — preserves contact for audit history */
    @Query("UPDATE contacts SET isDeleted = 1 WHERE id = :contactId")
    void softDelete(long contactId);

    /** Hard delete (legacy — kept for backward compat, prefer softDelete) */
    @Query("DELETE FROM contacts WHERE id = :contactId")
    void deleteContact(long contactId);

    /** Ensure only one primary per customer scope */
    @Query("UPDATE contacts SET esPrincipal = 0 WHERE customerId = :customerId AND id != :exceptId")
    void clearPrimaryForCustomer(long customerId, long exceptId);
}
