// model/Index.java
package searchengine.model;

import lombok.Data;
import javax.persistence.*;

@Entity
@Table(name = "search_index",
        indexes = {@javax.persistence.Index(name = "index_page_lemma", columnList = "page_id, lemma_id")})
@Data
public class Index {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "page_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_index_page"))
    private Page page;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lemma_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_index_lemma"))
    private Lemma lemma;

    @Column(name = "relevance", nullable = false)
    private float relevance;
}
