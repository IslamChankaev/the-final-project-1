package searchengine.model;

import lombok.Data;
import javax.persistence.*;
import javax.persistence.Index;

@Entity
@Table(name = "page",
        indexes = {@Index(name = "path_index", columnList = "path")})
@Data
public class Page {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "site_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_page_site"))
    private Site site;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String path;

    @Column(nullable = false)
    private int code;

    @Column(columnDefinition = "MEDIUMTEXT", nullable = false)
    private String content;
}