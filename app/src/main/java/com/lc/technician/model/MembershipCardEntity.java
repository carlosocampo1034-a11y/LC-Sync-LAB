package com.lc.technician.model;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "membership_cards",
        indices = {@Index(value = "cardNumber", unique = true)})
public class MembershipCardEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public String cardNumber;      // LC-GOLD-000123
    public long customerId;
    public Long coverageTypeId;    // nullable FK
    public String status;          // ACTIVE / EXPIRED / SUSPENDED
    public String paymentStatus;   // PAID / PENDING / OVERDUE
    public long issueDate;
    public long expiryDate;
    public Long lastServiceDate;   // nullable
    public Long nextServiceDate;   // nullable
    public String notes;
    public long createdAt;
    public long updatedAt;

    public static final String STATUS_ACTIVE    = "ACTIVE";
    public static final String STATUS_EXPIRED   = "EXPIRED";
    public static final String STATUS_SUSPENDED = "SUSPENDED";
    public static final String PAYMENT_PAID     = "PAID";
    public static final String PAYMENT_PENDING  = "PENDING";
    public static final String PAYMENT_OVERDUE  = "OVERDUE";
}
