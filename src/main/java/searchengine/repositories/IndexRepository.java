package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import searchengine.model.Index;
import searchengine.model.Lemma;
import searchengine.model.Page;

import javax.transaction.Transactional;
import java.util.Arrays;
import java.util.List;

@Repository
public interface IndexRepository extends JpaRepository<Index, Integer> {
    List<Index> findByLemma(List<Lemma> lemmas);
    List<Index> findByPage(Page page);

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
}