package dev.flowengine.execution;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface ExecutionRepository extends JpaRepository<Execution, UUID> {
    @Query("""
        select e.id as id, e.status as status, e.workflowRevision as workflowRevision,
               e.requestedAt as requestedAt, e.startedAt as startedAt, e.finishedAt as finishedAt
        from Execution e where e.workflowId = :workflowId order by e.requestedAt desc, e.id
        """)
    List<ExecutionService.Summary> list(UUID workflowId, Pageable page);

    Optional<Execution> findByIdAndWorkflowId(UUID id, UUID workflowId);

    @Modifying
    @Query(value = """
        UPDATE executions SET status = 'RUNNING', started_at = :now, deadline_at = :deadline
        WHERE id = :id AND status = 'PENDING'
        """, nativeQuery = true)
    int claim(UUID id, Instant now, Instant deadline);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Execution e where e.id = :id and e.status = dev.flowengine.execution.Execution.Status.RUNNING")
    Optional<Execution> findRunningForUpdate(UUID id);

    @Modifying
    @Query(value = """
        UPDATE executions SET status = 'FAILED', finished_at = :now, error = 'Worker did not finish before the deadline.'
        WHERE status = 'RUNNING' AND deadline_at <= :now
        """, nativeQuery = true)
    int expire(Instant now);
}
