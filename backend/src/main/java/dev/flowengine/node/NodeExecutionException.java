package dev.flowengine.node;

public final class NodeExecutionException extends RuntimeException {
    NodeExecutionException(String safeMessage) {
        super(safeMessage);
    }
}
