package com.codeway.repo;

import com.codeway.domain.*;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface EventRepo extends JpaRepository<EventLog, Long> { List<EventLog> findByAttemptId(Long attemptId);
    List<EventLog> findByAttemptIdInAndTypeIn(Collection<Long> attemptIds, Collection<String> types); }
