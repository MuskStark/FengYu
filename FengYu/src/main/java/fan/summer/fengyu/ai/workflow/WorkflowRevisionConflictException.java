package fan.summer.fengyu.ai.workflow;

/** Raised when an editor tries to overwrite a workflow revision it did not load. */
public class WorkflowRevisionConflictException extends RuntimeException {
    private final String workflowId;
    private final int expectedRevision;
    private final int actualRevision;

    public WorkflowRevisionConflictException(String workflowId, int expectedRevision,
                                              int actualRevision) {
        super("Workflow revision conflict: expected " + expectedRevision
                + " but found " + actualRevision + ". Reload before saving.");
        this.workflowId = workflowId;
        this.expectedRevision = expectedRevision;
        this.actualRevision = actualRevision;
    }

    public String workflowId() {
        return workflowId;
    }

    public int expectedRevision() {
        return expectedRevision;
    }

    public int actualRevision() {
        return actualRevision;
    }
}
