package uy.plomo.gateway.sequence;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class SequenceService {

    private final SequenceRepository repo;

    public List<Sequence> findAll() {
        return repo.findAll();
    }

    public Optional<Sequence> findById(String id) {
        return repo.findById(id);
    }

    @Transactional
    public Sequence save(Sequence sequence) {
        if (sequence.getId() == null || sequence.getId().isBlank()) {
            sequence.setId(UUID.randomUUID().toString());
        }
        return repo.save(sequence);
    }

    @Transactional
    public void deleteById(String id) {
        repo.deleteById(id);
    }

    @Transactional
    public void deleteByIds(List<String> ids) {
        repo.deleteAllById(ids);
    }
}
