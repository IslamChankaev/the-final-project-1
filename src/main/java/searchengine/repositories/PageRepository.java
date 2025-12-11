package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import searchengine.model.Page;
import searchengine.model.Site;

import javax.transaction.Transactional;
import java.util.List;
import java.util.Optional;

@Repository
public interface PageRepository extends JpaRepository<Page, Integer> {
    Optional<Page> findByPathAndSite(String path, Site site);
    List<Page> findBySite(Site site);
    long countBySite(Site site);

    @Modifying
    @Transactional
    @Query("DELETE FROM Page p WHERE p.site = :site")
    void deleteBySite(Site site);

    @Query("SELECT p FROM Page p WHERE p.site = :site AND p.code = 200")
    List<Page> findSuccessfulPagesBySite(Site site);
}