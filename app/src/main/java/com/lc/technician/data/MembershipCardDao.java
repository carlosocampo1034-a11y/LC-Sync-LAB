package com.lc.technician.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import com.lc.technician.model.MembershipCardEntity;
import java.util.List;

@Dao
public interface MembershipCardDao {
    @Insert
    long insert(MembershipCardEntity card);

    @Update
    void update(MembershipCardEntity card);

    @Delete
    void delete(MembershipCardEntity card);

    @Query("SELECT * FROM membership_cards WHERE cardNumber = :cardNumber LIMIT 1")
    MembershipCardEntity getByCardNumber(String cardNumber);

    @Query("SELECT * FROM membership_cards WHERE customerId = :customerId ORDER BY createdAt DESC")
    List<MembershipCardEntity> getByCustomer(long customerId);

    @Query("SELECT * FROM membership_cards ORDER BY createdAt DESC")
    List<MembershipCardEntity> getAll();

    @Query("UPDATE membership_cards SET lastServiceDate = :ts, updatedAt = :ts WHERE id = :cardId")
    void marcarServicio(long cardId, long ts);
}
