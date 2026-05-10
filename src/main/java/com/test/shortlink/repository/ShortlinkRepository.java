package com.test.shortlink.repository;
import com.test.shortlink.entity.Shortlink;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
@Repository
public interface ShortlinkRepository extends JpaRepository<Shortlink, Long> {
}
