package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import searchengine.model.Index;
import searchengine.model.Lemma;
import searchengine.model.Page;

import javax.transaction.Transactional;
import java.util.List;
import java.util.Optional;

@Repository
public interface IndexRepository extends JpaRepository<Index, Integer> {

    List<Index> findByPage(Page page);

    List<Index> findByPageIn(List<Page> pages);

    List<Index> findByLemma(Lemma lemma);

    @Query("SELECT i FROM Index i WHERE i.lemma IN :lemmas")
    List<Index> findByLemmas(List<Lemma> lemmas);

    @Query("SELECT i FROM Index i WHERE i.lemma = :lemma AND i.page = :page")
    Optional<Index> findByLemmaAndPage(Lemma lemma, Page page);

    @Modifying
    @Transactional
    @Query("DELETE FROM Index i WHERE i.page = :page")
    void deleteByPage(Page page);

    @Modifying
    @Transactional
    @Query("DELETE FROM Index i WHERE i.lemma = :lemma")
    void deleteByLemma(Lemma lemma);

    @Query("SELECT SUM(i.rank) FROM Index i WHERE i.page = :page AND i.lemma IN :lemmas")
    Float calculateRelevanceForPage(Page page, List<Lemma> lemmas);

    @Query("SELECT i FROM Index i JOIN i.page p WHERE i.lemma = :lemma AND p.site = :site")
    List<Index> findByLemmaAndSite(Lemma lemma, searchengine.model.Site site);

    @Query("SELECT i.rank FROM Index i WHERE i.page = :page AND i.lemma = :lemma")
    Float findRankByPageAndLemma(Page page, Lemma lemma);
}