package com.codeway.repo;

import com.codeway.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.*;

public interface EventRepo extends JpaRepository<EventLog, Long> { List<EventLog> findByAttemptId(Long attemptId);
    List<EventLog> findByAttemptIdInAndTypeIn(Collection<Long> attemptIds, Collection<String> types);

    /** When this learner asked for hints since {@code since}, oldest first (the hourly hint allowance is counted from these). */
    @Query("select e.ts from EventLog e where e.type = 'hint' and e.ts > :since "
            + "and e.attemptId in (select a.id from Attempt a where a.userId = :userId) order by e.ts asc")
    List<Instant> hintTimesSince(@Param("userId") Long userId, @Param("since") Instant since);
}
