package fan.summer.fengyu.ai.workflow;

/** A valid trigger that cannot run until its safety/runtime posture is restored. */
public class WorkflowWebhookUnavailableException extends RuntimeException {
    public WorkflowWebhookUnavailableException(String message) {
        super(message);
    }
}
