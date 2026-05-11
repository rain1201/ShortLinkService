package com.test.shortlink.repository;
import com.test.shortlink.entity.Shortlink;


import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
@Repository
public interface ShortlinkRepository extends JpaRepository<Shortlink, Long> {
    @Modifying
    @Transactional
    @Query("update Shortlink s set s.viewCount = :count where s.id = :id")
    void updateViewCount(@Param("id") long id, @Param("count") long count);
}
