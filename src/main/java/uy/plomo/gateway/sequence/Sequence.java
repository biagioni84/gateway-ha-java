package uy.plomo.gateway.sequence;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A named sequence of device commands executed in order.
 * The steps are stored as opaque JSON (list of step objects).
 */
@Entity
@Table(name = "sequences")
@Data
public class Sequence {

    @Id
    private String id;

    private String name;

    /** JSON array of step objects, e.g. [{"cmd":"on","dev":"uuid1"}, ...] */
    @Column(columnDefinition = "TEXT")
    private String steps;
}
