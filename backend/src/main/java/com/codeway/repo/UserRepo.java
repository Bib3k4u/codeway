package com.codeway.repo;

import com.codeway.domain.*;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface UserRepo extends JpaRepository<User, Long> { Optional<User> findByEmail(String email); }
