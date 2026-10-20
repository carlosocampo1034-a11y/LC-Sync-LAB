package com.lc.technician.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "parts_requests")
public class PartsRequestEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public long jobId;
    public long technicianId;
    public String status;

    public String itemsJson;
    public double totalEstimated;

    public String notes;

    public Long approvedBy;
    public Long approvedAt;
    public String rejectionReason;

    public long createdAt;
    public long updatedAt;

    public PartsRequestEntity() {
        this.status = STATUS_PENDING;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
    }

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_FULFILLED = "FULFILLED";
    public static final String STATUS_CANCELLED = "CANCELLED";
}
