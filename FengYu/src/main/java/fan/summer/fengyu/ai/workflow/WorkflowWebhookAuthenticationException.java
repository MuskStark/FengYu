package fan.summer.fengyu.ai.workflow;

/** An unknown webhook trigger and a wrong secret deliberately share this non-enumerating error. */
public class WorkflowWebhookAuthenticationException extends RuntimeException {
    public WorkflowWebhookAuthenticationException() {
        super("Unknown webhook trigger or invalid secret");
    }
}
