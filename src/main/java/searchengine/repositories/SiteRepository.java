package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import searchengine.model.Site;
import searchengine.model.Status;

import javax.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SiteRepository extends JpaRepository<Site, Integer> {
    Optional<Site> findByUrl(String url);
    boolean existsByUrlAndStatus(String url, Status status);
    long countByStatus(Status status);

    @Modifying
    @Transactional
    @Query("UPDATE Site s SET s.status = :status, s.statusTime = :statusTime WHERE s.id = :id")
    void updateStatus(int id, Status status, LocalDateTime statusTime);

    @Modifying
    @Transactional
    @Query("UPDATE Site s SET s.status = :status, s.statusTime = :statusTime, s.lastError = :error WHERE s.id = :id")
    void updateStatusWithError(int id, Status status, LocalDateTime statusTime, String error);

    List<Site> findAllByUrl(String url);
}
