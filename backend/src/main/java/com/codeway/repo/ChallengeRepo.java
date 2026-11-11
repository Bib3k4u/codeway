package com.codeway.repo;

import com.codeway.domain.*;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface ChallengeRepo extends JpaRepository<Challenge, Long> {
    List<Challenge> findByLanguageAndConceptAndValidatedTrue(String l, String c);
    boolean existsByTitleAndOrigin(String title, String origin);
    Optional<Challenge> findByTitleAndOrigin(String title, String origin);
}
