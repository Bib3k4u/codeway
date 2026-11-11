package com.codeway.repo;

import com.codeway.domain.*;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface MasteryRepo extends JpaRepository<ConceptMastery, Long> {
    Optional<ConceptMastery> findByUserIdAndLanguageAndConcept(Long userId, String language, String concept);
    List<ConceptMastery> findByUserIdAndLanguage(Long userId, String language);
}
