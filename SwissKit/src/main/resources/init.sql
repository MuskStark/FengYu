-- SwissKit Database Initialization Script

-- Email Settings Table
CREATE TABLE IF NOT EXISTS swiss_kit_setting_email
(
    id           INTEGER PRIMARY KEY AUTO_INCREMENT,
    email        VARCHAR(255) NOT NULL,
    password     VARCHAR(255) NOT NULL,
    smtp_address VARCHAR(255) NOT NULL,
    smtp_port    INTEGER      NOT NULL,
    need_tls     INTEGER      NOT NULL DEFAULT 0,
    need_ssl     INTEGER      NOT NULL DEFAULT 0,
    from_address VARCHAR(255)
);

-- Excel Complex Split Config Table
CREATE TABLE IF NOT EXISTS complex_split_config
(
    id           INTEGER PRIMARY KEY AUTO_INCREMENT,
    task_id      VARCHAR(255) NOT NULL,
    field_name   VARCHAR(255) NOT NULL,
    sheet_name   VARCHAR(255) NOT NULL,
    header_index INTEGER      NOT NULL,
    column_index INTEGER      NOT NULL
);

-- Email Address Book Table
CREATE TABLE IF NOT EXISTS email_address_book
(
    id            INTEGER PRIMARY KEY AUTO_INCREMENT,
    email_address VARCHAR(255) NOT NULL,
    nickname      VARCHAR(255),
    tags          VARCHAR(1000)
);

-- Email Tag Table
CREATE TABLE IF NOT EXISTS email_tag
(
    id  INTEGER PRIMARY KEY AUTO_INCREMENT,
    tag VARCHAR(255) NOT NULL UNIQUE
);

-- Email Mass Sent Config Table
CREATE TABLE IF NOT EXISTS email_mass_sent_config
(
    id               INTEGER PRIMARY KEY AUTO_INCREMENT,
    task_id          VARCHAR(255) NOT NULL UNIQUE,
    to_tag           VARCHAR(255),
    cc_tag           VARCHAR(255),
    is_sent_att      INTEGER      NOT NULL DEFAULT 0,
    att_folder_path  VARCHAR(255),
    send_by_filename INTEGER      NOT NULL DEFAULT 0
);

-- Add send_by_filename column if it doesn't exist (for existing databases)
-- H2 doesn't support IF NOT EXISTS in ALTER TABLE ADD COLUMN before 2.x, so use a script-safe approach
ALTER TABLE email_mass_sent_config ADD COLUMN IF NOT EXISTS send_by_filename INTEGER NOT NULL DEFAULT 0;

-- Email Sent Log Table
CREATE TABLE IF NOT EXISTS email_sent_log
(
    id          INTEGER PRIMARY KEY AUTO_INCREMENT,
    "to"        VARCHAR(1000),
    cc          VARCHAR(1000),
    bcc         VARCHAR(1000),
    subject     VARCHAR(500),
    content     TEXT,
    attachment  VARCHAR(1000),
    send_time   TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    is_success  INTEGER     NOT NULL DEFAULT 0
);

-- Menu Order Table (for drag-and-drop reordering)
CREATE TABLE IF NOT EXISTS menu_order
(
    id         INTEGER PRIMARY KEY AUTO_INCREMENT,
    page_class VARCHAR(500) NOT NULL UNIQUE,
    menu_order INTEGER NOT NULL
);

-- App Settings Table (key-value store for application settings)
CREATE TABLE IF NOT EXISTS app_setting
(
    id           INTEGER PRIMARY KEY AUTO_INCREMENT,
    setting_key  VARCHAR(255) NOT NULL UNIQUE,
    setting_value VARCHAR(1000)
);

-- Insert default language setting only if not exists
INSERT INTO app_setting (setting_key, setting_value)
SELECT 'language', 'en'
WHERE NOT EXISTS (SELECT 1 FROM app_setting WHERE setting_key = 'language');

-- Plugin Manager Table (for external plugin management)
CREATE TABLE IF NOT EXISTS plugin_manager
(
    id              INTEGER PRIMARY KEY AUTO_INCREMENT,
    jar_name        VARCHAR(500) NOT NULL UNIQUE,
    plugin_name     VARCHAR(255) NOT NULL,
    plugin_version  VARCHAR(50)  NOT NULL,
    is_disabled     INTEGER      NOT NULL DEFAULT 0,
    update_url      VARCHAR(1000),
    last_check      TIMESTAMP,
    installed_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- Add IMAP fields to email settings (unified send/receive)
ALTER TABLE swiss_kit_setting_email ADD COLUMN IF NOT EXISTS imap_address VARCHAR(255);
ALTER TABLE swiss_kit_setting_email ADD COLUMN IF NOT EXISTS imap_port INTEGER DEFAULT 993;
ALTER TABLE swiss_kit_setting_email ADD COLUMN IF NOT EXISTS imap_ssl INTEGER NOT NULL DEFAULT 1;

-- Email Archive Table
CREATE TABLE IF NOT EXISTS email_archive
(
    id             INTEGER PRIMARY KEY AUTO_INCREMENT,
    account_email  VARCHAR(255) NOT NULL,
    folder         VARCHAR(255) NOT NULL DEFAULT 'INBOX',
    message_uid    VARCHAR(255) NOT NULL,
    subject        VARCHAR(500),
    from_address   VARCHAR(500),
    to_address     VARCHAR(1000),
    cc_address     VARCHAR(1000),
    send_date      TIMESTAMP,
    has_attachment INTEGER      DEFAULT 0,
    eml_path       VARCHAR(1000),
    body_preview   VARCHAR(500),
    archived_at    TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(account_email, folder, message_uid)
);

-- Plugin Favorites Table (tool bookmarks)
CREATE TABLE IF NOT EXISTS plugin_favorites
(
    id         INTEGER PRIMARY KEY AUTO_INCREMENT,
    plugin_id  VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
);

