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
    id              INTEGER PRIMARY KEY AUTO_INCREMENT,
    task_id         VARCHAR(255) NOT NULL UNIQUE,
    to_tag          VARCHAR(255),
    cc_tag          VARCHAR(255),
    is_sent_att     INTEGER      NOT NULL DEFAULT 0,
    att_folder_path VARCHAR(255)
);

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


