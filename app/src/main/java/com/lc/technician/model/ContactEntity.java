package com.lc.technician.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "contacts")
public class ContactEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public long jobId;

    /** Customer this contact belongs to (scoped at customer level for reuse) */
    public Long customerId;

    /** Property this contact is specific to (nullable — null means all properties) */
    public Long propertyId;

    public String nombre;
    public String telefono;
    public String email;

    /** DUEÑO, INQUILINO, PAGADOR, FIRMANTE, PRINCIPAL */
    public String rol;

    public boolean esPrincipal;

    /** Soft delete — contact is hidden but preserved for audit history */
    public boolean isDeleted;
}
