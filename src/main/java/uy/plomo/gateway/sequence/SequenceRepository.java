package uy.plomo.gateway.sequence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SequenceRepository extends JpaRepository<Sequence, String> {
}
