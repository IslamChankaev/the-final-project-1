
package searchengine.model;

import lombok.Data;
import javax.persistence.*;
import javax.persistence.Index;

@Entity
@Table(name = "lemma", indexes = {@Index(name = "lemma_site_index", columnList = "lemma, site_id")})
@Data
public class Lemma {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "site_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_lemma_site"))
    private Site site;

    @Column(columnDefinition = "VARCHAR(255)", nullable = false)
    private String lemma;

    @Column(nullable = false)
    private int frequency;
}