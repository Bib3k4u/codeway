package com.codeway.repo;

import com.codeway.domain.*;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface LessonRepo extends JpaRepository<Lesson, Long> { Optional<Lesson> findByLanguageAndConcept(String l, String c); }
