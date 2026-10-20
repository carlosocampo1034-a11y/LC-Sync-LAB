package com.lc.technician.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "membership_check_ins")
public class MembershipCheckInEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public long cardId;
    public Long jobId;
    public long checkInDate;
    public String technicianName;
    public String notes;
    public long createdAt;
}
