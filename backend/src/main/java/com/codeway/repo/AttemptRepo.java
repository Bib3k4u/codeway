package com.codeway.repo;

import com.codeway.domain.*;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface AttemptRepo extends JpaRepository<Attempt, Long> {
    List<Attempt> findByUserId(Long userId);
    List<Attempt> findByUserIdAndSubmittedAtIsNullOrderByStartedAtDesc(Long userId);
    List<Attempt> findByUserIdAndSubmittedAtIsNotNullOrderBySubmittedAtDesc(Long userId);
}
