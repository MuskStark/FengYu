package fan.summer.kitpage.setting.worker.second;

import fan.summer.database.entity.setting.email.EmailAddressBookEntity;

import java.util.List;

/**
 * Callback interface for email address book query operations.
 * Used by QueryAllEmailInfoWorker to return results.
 */
public interface QueryAllEmailInfoCallBack {
    /**
     * Called when email address book query succeeds.
     *
     * @param emailAddressBookEntities list of queried email address entities
     */
    void onSuccess(List<EmailAddressBookEntity> emailAddressBookEntities);

    /**
     * Called when email address book query fails.
     *
     * @param e the exception that caused the failure
     */
    void onFailure(Exception e);
}
