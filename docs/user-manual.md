# SwissKitJ User Manual

> **SwissKitJ** is a modular desktop toolbox that provides various productivity tools including Excel file processing,
> email management, and plugin support.

---

## Table of Contents

1. [Getting Started](#getting-started)
2. [Excel Tool](#excel-tool)
3. [Email Tool](#email-tool)
4. [HappyLearning Plugin](#happylearning-plugin)
5. [Settings](#settings)
6. [Plugin System](#plugin-system)

---

---

## Excel Tool

The Excel Tool provides comprehensive Excel file processing capabilities including file analysis, splitting by sheets or
columns, and advanced complex split mode.

### Opening the Excel Tool

Click **"Excel Tool"** in the sidebar menu.

> **Screenshot placeholder**: Excel Tool main interface

### Simple Split Mode

Simple split mode allows you to split Excel files by sheets or by column values.

#### Split by Sheet

Splits each sheet in an Excel file into a separate file.

**Steps:**

1. Click **"Choice Excel"** to select your Excel file (`.xlsx` or `.xls`)
2. Click **"Choice OutFile"** to select the output directory
3. Select **"SplitBySheet"** radio button
4. Click **"Start"** to begin splitting

> **Screenshot placeholder**: Excel file selected for splitting by sheet

#### Split by Column

Groups rows by unique values in a specified column and creates separate files for each group.

**Steps:**

1. Click **"Choice Excel"** to select your Excel file
2. Click **"Choice OutFile"** to select the output directory
3. Select **"SplitByColumn"** radio button
4. Select the **column to split by** from the dropdown
5. Click **"Start"** to begin splitting

> **Screenshot placeholder**: Column selection dropdown

### Complex Split Mode

Complex split mode provides advanced configuration options for splitting Excel files with precise control.

**Features:**

- Select which sheet to work on
- Specify header row location
- Choose which column to split by
- Save and reuse configurations

#### Using Complex Split Mode

1. Select **"ComplexSplit"** radio button

2. **ChoiceSheet**: Select the sheet you want to work with from the dropdown

3. **HeaderRowIndex**: Enter the row number that contains headers (1-based index)

4. **SplitColumnIndex**: Enter the column number to split by (1-based index)

5. Click **"SetConfig"** to save the current configuration to the database

> **Screenshot placeholder**: Complex split configuration panel

#### Managing Configurations

- **ViewConfig**: Click to view all saved configurations in a dialog
    - Double-click any configuration to edit it
    - Configuration includes: Sheet name, Header row, Split column, Creation date

> **Screenshot placeholder**: Configuration view dialog with list of saved configs

- **ClearConfig**: Click to delete the currently selected configuration

> **Screenshot placeholder**: Clear config confirmation dialog

### Progress Tracking

A progress bar at the bottom of the Excel Tool shows the status of ongoing operations.

- Progress is updated in real-time
- Shows percentage completion
- Displays current/max operations where applicable

---

## Email Tool

The Email Tool allows you to compose and send individual or mass emails with attachments, using contacts organized by
tags.

### Opening the Email Tool

Click **"Email Tool"** in the sidebar menu.

> **Screenshot placeholder**: Email Tool main interface

### Mass Email Sending

Mass email sending allows you to send emails to recipients matched through attachment tags.

#### How It Works

1. **Tag Extraction**: For each attachment file, the system extracts the tag from the filename
    - Format: Everything between the **last underscore `_`** and the **last dot `.`** in the filename
    - Example: `report_2024_Q1.xlsx` → tag is `2024_Q1`
    - Example: `invoice_April_2024.pdf` → tag is `April_2024`

2. **Address Matching**: The system searches the Email Address Book for contacts that have matching tags

3. **Recipient Determination**:
    - **To Tag**: Contacts with this tag become primary recipients
    - **Cc Tag**: Contacts with this tag become CC recipients

#### Enabling Mass Send Mode

1. Check the **"MassSent"** checkbox at the bottom of the email form

> **Screenshot placeholder**: MassSent checkbox

2. The Mass Send Configuration panel becomes available

#### Configuring Mass Send

Click **"MassSendConfig"** to configure mass sending:

- **To Tag**: Select the tag that identifies recipient contacts
- **Cc Tag**: (Optional) Select a tag for CC recipients
- **Attachment Folder**: Select the folder containing attachment files

> **Screenshot placeholder**: Mass send configuration dialog

Click **"Save"** to save the configuration.

#### Sending the Email

1. Compose your email (Subject, Body)
2. Click **"ByTag"** to select the attachment folder
3. Click **"Sent"** to send

The system will send individual emails to each matched recipient, with their specific attachment(s) based on the tag
matching.

> **Screenshot placeholder**: Mass email sent successfully dialog

#### Viewing Sent Log

Click **"ViewSentLog"** to see the history of sent emails:

- Shows: Recipient, Subject, Status (Success/Failed), Sent Time
- Helps track which emails were delivered successfully

> **Screenshot placeholder**: Sent log viewing dialog

### Email Address Book

The Email Address Book (accessible from Settings) stores contacts with nicknames and tags for easy organization.

**To open**: Click **"OpenAddressBook"** button in Settings → Email tab

#### Adding a Contact

1. Click **"Add"** button
2. Fill in the contact details:
    - **Nickname**: A friendly name for the contact
    - **Email**: The email address
    - **Tag**: Select or create tags to categorize the contact

3. Click **"Save"** to store the contact

> **Screenshot placeholder**: Add contact dialog

#### Editing a Contact

**Double-click** any contact row to edit it.

> **Screenshot placeholder**: Edit contact dialog (double-click)

#### Deleting a Contact

1. Select the contact row
2. Click **"Delete"** button
3. Confirm the deletion

#### Resetting Contact

Click **"Reset"** to clear only the tag information of the selected contact (keeps nickname and email).

### Tag Management

Tags help organize your contacts for easy mass email targeting.

#### Creating a Tag

1. Click **"AddTag"** button
2. Enter the tag name
3. Click **"Save"** to create the tag

> **Screenshot placeholder**: Add tag dialog

#### Deleting a Tag

1. Select the tag in the tag list
2. Click **"DeleteTag"** button

---

## HappyLearning Plugin

HappyLearning is an automated online learning plugin that automatically completes learning sessions for you.

### Opening HappyLearning

Click **"HappyLearning"** in the sidebar menu.

> **Screenshot placeholder**: HappyLearning interface

### Configuration

#### Upload Config File

1. Click **"UploadConfig"** button
2. Select your JSON configuration file
3. The config file path appears in the **ConfigFile** field

> **Screenshot placeholder**: Config file selected

#### Set PassKey

1. Enter your passkey in the **PassKey** field
2. Click **"SetPassKey"** to save it

> **Screenshot placeholder**: PassKey entered

### Learning Options

#### Subject Selection

- **OnlyMajorSubject**: Check this to learn only major subjects
- **OnlyElectiveSubject**: Check this to learn only elective subjects
- If neither is checked, both major and elective subjects will be learned

### Progress Bars

Two progress bars show your learning progress:

- **MajorSubject**: Progress for required/major courses
- **ElectiveSubject**: Progress for elective courses

Progress bars display text in "current/max h" format showing hours completed.

> **Screenshot placeholder**: Progress bars during learning

### Current Lesson Display

During learning sessions, the interface displays:

- **SubjectId**: The ID of the current lesson being learned
- **SubjectName**: The brief name of the current lesson

> **Screenshot placeholder**: Current lesson info displayed

### Controlling Learning

#### Start Learning

Click **"StartHappy"** to begin automated learning.

- Progress bars update in real-time
- Current lesson info updates as courses change
- Learning runs in the background

> **Screenshot placeholder**: Learning in progress

#### Stop Learning

Click **"UnHAppy"** to stop the current learning session.

- A confirmation may appear
- Progress stops updating
- Status changes to "cancelled"

---

## Settings

The Settings page allows you to configure email SMTP settings and manage plugins.

### Opening Settings

Click **"Settings"** in the sidebar menu.

> **Screenshot placeholder**: Settings main interface

### Email Tab

The Email tab configures your SMTP server for outgoing emails.

#### Configuration Fields

| Field       | Description                                           |
|-------------|-------------------------------------------------------|
| Protocol    | SMTP protocol (displayed as SMTP label)               |
| ServerUrl   | SMTP server address (e.g., `smtp.gmail.com`)          |
| ServerPort  | SMTP port number (e.g., `587` for TLS, `465` for SSL) |
| UserName    | Your email address                                    |
| PassWord    | Your email password or app password                   |
| FromAddress | The sender address displayed in emails                |
| TSL         | Enable TLS encryption                                 |
| SSL         | Enable SSL encryption                                 |

> **Screenshot placeholder**: Email configuration fields

#### Testing Connection

1. Fill in all configuration fields
2. Click **"SentTestEmail"** to test the SMTP connection
3. If successful, you can proceed to send emails
4. If failed, check your settings and try again

#### Saving Configuration

Click **"Save"** to store your email settings.

#### Opening Address Book

Click **"OpenAddressBook"** to manage your email contacts.

### Plugin Tab

The Plugin tab allows you to view installed plugins and install new ones.

#### Viewing Installed Plugins

The **Installed Plugins** table displays:

| Column | Description                      |
|--------|----------------------------------|
| Name   | Plugin JAR filename              |
| Path   | Full path to the plugin JAR file |
| Size   | File size in KB or MB            |

> **Screenshot placeholder**: Installed plugins table

#### Installing a Plugin

1. Click **"ChoicePlugin"** to select a plugin JAR file
2. The selected file path appears in the text field
3. Click **"Upload"** to install the plugin
4. A success message appears when installed
5. **Restart SwissKitJ** to see the new plugin in the sidebar

> **Screenshot placeholder**: Plugin selection and upload

#### Uninstalling a Plugin

1. Select the plugin from the **Installed Plugins** table
2. Click **"Uninstall"**
3. Confirm the uninstallation in the dialog
4. **Restart SwissKitJ** for changes to take effect

> **Screenshot placeholder**: Uninstall confirmation dialog

**Note**: You cannot uninstall plugins while SwissKitJ is running. Restart is required.

---

## Plugin System

SwissKitJ supports a plugin-based architecture that allows extending functionality through external JAR files.

### How Plugins Work

1. Plugins are JAR files placed in the `.swisskit/plugins/` directory
2. SwissKitJ automatically discovers plugins on startup using Java SPI
3. Plugins appear in the sidebar menu based on their order configuration

### Installing Plugins

1. Obtain the plugin JAR file (must implement `KitPage` interface)
2. Open **Settings → Plugin** tab
3. Click **"ChoicePlugin"** and select the JAR file
4. Click **"Upload"** to install
5. Restart SwissKitJ

### Uninstalling Plugins

1. Open **Settings → Plugin** tab
2. Select the plugin from the table
3. Click **"Uninstall"**
4. Confirm the action
5. Restart SwissKitJ

### Available Plugins

#### Built-in Tools

- **Welcome Page**: Application home and quick access
- **Excel Tool**: Excel file processing and splitting
- **Email Tool**: Email composition and sending
- **HappyLearning**: Automated online learning
- **Settings**: Application configuration

#### Example Plugin

- **QccToExcel** (if installed): Converts QCC files to Excel format

---

## Troubleshooting

### Excel Tool

**File not loading:**

- Ensure the file is a valid `.xlsx` or `.xls` format
- Check that the file is not corrupted or password-protected

**Split not working:**

- Verify the column index is within range
- Ensure the output directory is writable

### Email Tool

**Emails not sending:**

- Verify SMTP settings in Settings → Email tab
- Check your internet connection
- For Gmail, ensure you're using an App Password, not your regular password

**Mass send not working:**

- Ensure contacts have the correct tags assigned
- Verify the tag exists in your address book

### HappyLearning

**Learning not starting:**

- Verify your config file is valid JSON
- Ensure the passkey is correct
- Check your internet connection

**Progress not updating:**

- The learning may have completed all available courses
- Try restarting the application

### General

**Application won't start:**

- Ensure JDK 11 or higher is installed
- Run `mvn clean compile` to rebuild
- Check the `.swisskit` directory for database issues

**UI elements not responding:**

- Wait for background operations to complete
- Check the progress bar for ongoing tasks
- Try restarting the application

---

## Database

SwissKitJ uses an embedded H2 database stored at `.swisskit/swisskit.db`.

**Tables:**

- `swiss_kit_setting_email` - Email SMTP configuration
- `email_address_book` - Contact information
- `email_tag` - Tag definitions
- `email_mass_sent_config` - Mass sending configurations
- `email_sent_log` - Email sending history
- `complex_split_config` - Excel split configurations

**Note**: The database is created automatically on first run.

---

## Support

For more information:

- **Documentation**: https://muskstark.github.io/SwissKitJ
- **GitHub Issues**: Report bugs or request features
- **Email**: Contact the development team

---

*SwissKitJ v1.2.0*
