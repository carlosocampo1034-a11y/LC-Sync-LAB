package com.lc.technician.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "coverage_types")
public class CoverageTypeEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public String code;              // GOLD, SILVER, BRONZE, HOME_WARRANTY, COD, NONE
    public String displayName;
    public String description;
    public double deductibleAmount;
    public double coveragePercentage;
    public boolean requiresApproval;
    public boolean isActive;
    public long createdAt;
    public long updatedAt;
}
