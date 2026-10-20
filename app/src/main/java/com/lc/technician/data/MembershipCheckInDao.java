package com.lc.technician.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import com.lc.technician.model.MembershipCheckInEntity;
import java.util.List;

@Dao
public interface MembershipCheckInDao {
    @Insert
    long insert(MembershipCheckInEntity checkIn);

    @Query("SELECT * FROM membership_check_ins WHERE cardId = :cardId ORDER BY checkInDate DESC")
    List<MembershipCheckInEntity> getByCard(long cardId);

    @Query("SELECT COUNT(*) FROM membership_check_ins WHERE cardId = :cardId")
    int countByCard(long cardId);

    @Query("SELECT * FROM membership_check_ins ORDER BY checkInDate DESC")
    List<MembershipCheckInEntity> getAll();
}
