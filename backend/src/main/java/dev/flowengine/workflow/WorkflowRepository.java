package dev.flowengine.workflow;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface WorkflowRepository extends JpaRepository<Workflow, UUID> {
    @Query("""
        select w.id as id, w.name as name, w.revision as revision, w.updatedAt as updatedAt
        from Workflow w where w.owner = :owner order by w.updatedAt desc, w.id
        """)
    List<WorkflowService.Summary> list(String owner);

    Optional<Workflow> findByIdAndOwner(UUID id, String owner);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select w from Workflow w where w.id = :id and w.owner = :owner")
    Optional<Workflow> findForRun(UUID id, String owner);
}
