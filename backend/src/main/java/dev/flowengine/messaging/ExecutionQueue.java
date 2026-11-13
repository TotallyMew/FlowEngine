package dev.flowengine.messaging;

import dev.flowengine.execution.ExecutionEngine;
import dev.flowengine.execution.ExecutionService;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Configuration
class QueueConfiguration {
    static final String RUNS = "flowengine.executions.v1";

    @Bean
    Queue durableExecutionQueue() {
        return QueueBuilder.durable(RUNS).build();
    }
}

@Component
class ExecutionQueue {
    private static final Logger log = LoggerFactory.getLogger(ExecutionQueue.class);
    private final JdbcClient jdbc;
    private final RabbitTemplate rabbit;
    private final ExecutionService runs;
    private final ExecutionEngine engine;

    ExecutionQueue(JdbcClient jdbc, RabbitTemplate rabbit, ExecutionService runs, ExecutionEngine engine) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.runs = runs;
        this.engine = engine;
    }

    @Scheduled(fixedDelay = 1000)
    void dispatch() {
        var ids = jdbc.sql("""
            WITH batch AS (
                SELECT id FROM executions
                WHERE status = 'PENDING' AND next_dispatch_at <= now()
                ORDER BY next_dispatch_at, id LIMIT 32 FOR UPDATE SKIP LOCKED
            )
            UPDATE executions AS e SET next_dispatch_at = now() + interval '10 seconds'
            FROM batch WHERE e.id = batch.id RETURNING e.id
            """).query(UUID.class).list();
        for (var id : ids) {
            try {
                rabbit.convertAndSend(QueueConfiguration.RUNS, id.toString());
            } catch (AmqpException ex) {
                log.warn("Execution dispatch unavailable; {} pending run(s) will be retried", ids.size());
                break;
            }
        }
    }

    @Scheduled(fixedDelay = 5000)
    void expire() {
        int expired = runs.expire();
        if (expired > 0) log.warn("Marked {} overdue execution(s) failed", expired);
    }

    @RabbitListener(queues = QueueConfiguration.RUNS)
    void consume(String executionId) {
        final UUID id;
        try {
            id = UUID.fromString(executionId);
        } catch (IllegalArgumentException ex) {
            log.warn("Discarding an invalid execution message");
            return;
        }
        runs.claim(id).ifPresent(claim -> {
            ExecutionEngine.Outcome outcome;
            try {
                outcome = engine.run(claim.graph(), claim.input(), claim.deadline());
            } catch (RuntimeException ex) {
                log.error("Engine could not process execution {} ({})", id, ex.getClass().getSimpleName());
                outcome = new ExecutionEngine.Outcome(List.of(), "Worker could not process the saved workflow.");
            }
            runs.finish(id, outcome);
        });
    }
}
