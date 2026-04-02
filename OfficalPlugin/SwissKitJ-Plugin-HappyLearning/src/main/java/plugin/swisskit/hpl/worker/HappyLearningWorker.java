package plugin.swisskit.hpl.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import plugin.swisskit.hpl.dto.UserSearchResp;
import plugin.swisskit.hpl.service.HappyLearningService;
import plugin.swisskit.hpl.util.ConfigLoader;
import plugin.swisskit.hpl.util.WebUtil;

import javax.swing.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Happy learning background worker
 * <p>
 * Executes the auto-learning process in a background thread while periodically
 * updating the UI progress bars. Supports cancellation via stop button.
 * </p>
 *
 * @author phoebej
 * @version 1.00
 * @Date 2026/3/23
 */
public class HappyLearningWorker extends SwingWorker<Void, HappyLearningWorker.LearningUpdate> {
    private static final Logger log = LoggerFactory.getLogger(HappyLearningWorker.class);

    private final String passKey;
    private final JProgressBar majorSubjectProgressBar;
    private final JProgressBar electiveSubjectProgressBar;
    private final String type;
    private HappyLearningService service;
    private final JButton startBt;
    private final JButton stopBt;
    private final JTextField subjectIdTextField;
    private final JTextField subjectName;
    private final JLabel statusLabel;
    private final JTextField classHoursField;

    /**
     * Holds progress and lesson info for UI updates.
     */
    public static class LearningUpdate {
        public final int majorProgress;
        public final int electiveProgress;
        public final Long lessonId;
        public final String lessonName;
        public final Float classHours;

        public LearningUpdate(int majorProgress, int electiveProgress, Long lessonId, String lessonName, Float classHours) {
            this.majorProgress = majorProgress;
            this.electiveProgress = electiveProgress;
            this.lessonId = lessonId;
            this.lessonName = lessonName;
            this.classHours = classHours;
        }
    }

    /**
     * Stores the exception that caused the worker to fail, for display in done()
     */
    private Exception workerException;

    public HappyLearningWorker(String passKey, JProgressBar majorSubjectProgressBar,
                               JProgressBar electiveSubjectProgressBar, String type,
                               JButton startBt, JButton stopBt,
                               JTextField subjectIdTextField, JTextField subjectName,
                               JLabel statusLabel, JTextField classHoursField) {
        this.passKey = passKey;
        this.majorSubjectProgressBar = majorSubjectProgressBar;
        this.electiveSubjectProgressBar = electiveSubjectProgressBar;
        this.type = type;
        this.startBt = startBt;
        this.stopBt = stopBt;
        this.subjectIdTextField = subjectIdTextField;
        this.subjectName = subjectName;
        this.statusLabel = statusLabel;
        this.classHoursField = classHoursField;
        this.service = new HappyLearningService();
        log.debug("HappyLearningWorker created with type: {}", type);
    }

    public void skipClass() {
        this.service.setSkipSignal(true);
    }

    @Override
    protected Void doInBackground() throws Exception {
        log.info("[Worker] === doInBackground START ===");
        log.info("[Worker] passKey is null: {}, passKey first 10 chars: {}",
                passKey == null, passKey != null ? passKey.substring(0, Math.min(10, passKey.length())) : "null");

        // Load config before using ConfigLoader
        log.info("[Worker] Loading ConfigLoader...");
        ConfigLoader.loadConfig();
        log.info("[Worker] ConfigLoader loaded successfully");

        // Set context classloader so fastjson2 can load DTO classes from plugin JAR
        Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader());

        String token;
        log.info("[Worker] About to extract token from cookie...");
        token = WebUtil.getValueFromCookie(passKey, "m0biletoken");
        log.info("[Worker] Token extraction completed");
        log.info("[Worker] Token extracted, present: {}", token != null && !token.isEmpty());

        log.info("[Worker] Token value (first 20 chars): {}",
                token != null && token.length() > 20 ? token.substring(0, 20) + "..." :
                        token != null ? token : "null");

        if (token == null || token.isEmpty()) {
            log.error("[Worker] Token is null or empty, cannot proceed with learning");
            workerException = new RuntimeException("Token is null or empty, please login first");
            return null;
        }

        log.info("[Worker] Starting happy learning, type: {}", type != null ? type : "Auto");

        // Initialize progress bar maximums on EDT
        try {
            log.info("[Worker] Calling invokeAndWait to initProcess...");
            SwingUtilities.invokeAndWait(this::initProcess);
            log.info("[Worker] invokeAndWait completed successfully");
        } catch (InterruptedException e) {
            log.error("[Worker] invokeAndWait was interrupted: {}", e.getMessage(), e);
            workerException = e;
            return null;
        } catch (java.lang.reflect.InvocationTargetException e) {
            log.error("[Worker] invokeAndWait threw exception: {} - {}",
                    e.getCause() != null ? e.getCause().getClass().getSimpleName() : "null",
                    e.getCause() != null ? e.getCause().getMessage() : e.getMessage(),
                    e.getCause());
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                workerException = (Exception) cause;
            } else if (cause instanceof Error) {
                workerException = new Exception("Error: " + cause.getClass().getSimpleName() + " - " + cause.getMessage());
            } else {
                workerException = new Exception(cause);
            }
            return null;
        } catch (Exception e) {
            log.error("[Worker] Failed to initialize progress bars: {} - {}",
                    e.getClass().getSimpleName(), e.getMessage(), e);
            workerException = e;
            return null;
        }

        log.info("[Worker] invokeAndWait succeeded, proceeding to start learning thread");

        // Run autoLearning in a separate thread
        CountDownLatch latch = new CountDownLatch(1);
        log.info("[Worker] About to start learning thread, service: {}", service);
        Thread learningThread = new Thread(() -> {
            log.info("[LearningThread] === LearningThread START ===");
            try {
                log.info("[LearningThread] Calling service.autoLearning(type={}, token={}, passKey={})",
                        type, token != null ? "present" : "null", passKey != null ? "present" : "null");
                service.autoLearning(type, token, passKey);
                log.info("[LearningThread] autoLearning returned successfully");
            } catch (Exception e) {
                if (!isCancelled()) {
                    log.error("[LearningThread] Auto learning failed: {} - {}",
                            e.getClass().getSimpleName(), e.getMessage(), e);
                    workerException = e;
                } else {
                    log.info("[LearningThread] Auto learning was cancelled by user");
                }
            } finally {
                latch.countDown();
                log.info("[LearningThread] === LearningThread END ===, latch countdown");
            }
        });
        learningThread.start();
        log.info("[Worker] Learning thread started, thread id: {}", learningThread.getId());
        // Poll progress periodically while learning is running
        while (latch.getCount() > 0) {
            if (isCancelled()) {
                log.info("[Worker] Worker cancelled, interrupting learning thread");
                updateProgress();
                learningThread.interrupt();
                break;
            }
            if (majorSubjectProgressBar.getParent() == null) {
                log.warn("[Worker] Progress bar panel removed from container, stopping worker");
                updateProgress();
                learningThread.interrupt();
                break;
            }
            updateProgress();
            try {
                log.debug("[Worker] Progress polled, sleeping for 30 seconds until next poll");
                Thread.sleep(30000);
            } catch (InterruptedException e) {
                log.info("[Worker] Progress polling interrupted, exiting loop");
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.info("[Worker] Worker finished, latch count: {}", latch.getCount());
        return null;
    }

    @Override
    protected void process(List<LearningUpdate> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        LearningUpdate latest = chunks.get(chunks.size() - 1);
        log.trace("[Worker] Processing progress update: Major={}, Elective={}, LessonId={}, LessonName={}",
                latest.majorProgress, latest.electiveProgress, latest.lessonId, latest.lessonName);

        if (latest.majorProgress >= 0) {
            majorSubjectProgressBar.setValue(latest.majorProgress);
            int majorMax = majorSubjectProgressBar.getMaximum();
            majorSubjectProgressBar.setString(latest.majorProgress + "/" + majorMax + " h");
        }
        if (latest.electiveProgress >= 0) {
            electiveSubjectProgressBar.setValue(latest.electiveProgress);
            int electiveMax = electiveSubjectProgressBar.getMaximum();
            electiveSubjectProgressBar.setString(latest.electiveProgress + "/" + electiveMax + " h");
        }
        if (latest.lessonId != null && subjectIdTextField != null) {
            subjectIdTextField.setText(String.valueOf(latest.lessonId));
        }
        if (latest.lessonName != null && subjectName != null) {
            subjectName.setText(latest.lessonName);
        }
        if (latest.classHours != null && classHoursField != null) {
            classHoursField.setText(String.valueOf(latest.classHours));
        }
    }

    private void initProcess() {
        log.info("[initProcess] === initProcess START ===");
        try {
            String token = WebUtil.getValueFromCookie(passKey, "m0biletoken");
            log.info("[initProcess] Token extracted from passKey");

            log.info("[initProcess] Calling service.getPersonInfo...");
            UserSearchResp resp = service.getPersonInfo(passKey, token);
            log.info("[initProcess] getPersonInfo returned: {}", resp != null ? "not null" : "null");

            if (resp == null) {
                log.error("[initProcess] Response is null");
                throw new RuntimeException("Failed to get person info: response is null");
            }
            if (resp.getData() == null) {
                log.error("[initProcess] Response data is null, status: {}", resp.getStatus());
                throw new RuntimeException("Failed to get person info: data is null, status " + resp.getStatus());
            }
            if (resp.getData().getPeriodDataRU() == null) {
                log.error("[initProcess] periodDataRU is null");
                throw new RuntimeException("Failed to get person info: periodDataRU is null");
            }

            Float majorSubjectLearned = resp.getData().getPeriodDataRU().getGroupLearningTotal();
            Float majorSubjectGoal = resp.getData().getPeriodDataRU().getGroupLearningGoal();
            Float electiveSubjectLearned = resp.getData().getPeriodDataRU().getSelfLearningTotal();
            Float electiveSubjectGoal = resp.getData().getPeriodDataRU().getSelfLearningGoal();

            log.info("[initProcess] Learning status - Major: {}/{}h, Elective: {}/{}h",
                    majorSubjectLearned, majorSubjectGoal, electiveSubjectLearned, electiveSubjectGoal);

            // Set maximum to goal (required hours), value to learned hours
            majorSubjectProgressBar.setMaximum(majorSubjectGoal.intValue());
            majorSubjectProgressBar.setValue(majorSubjectLearned.intValue());
            electiveSubjectProgressBar.setMaximum(electiveSubjectGoal.intValue());
            electiveSubjectProgressBar.setValue(electiveSubjectLearned.intValue());

            log.info("[initProcess] === initProcess END ===");
        } catch (Exception e) {
            log.error("[initProcess] Exception: {} - {}", e.getClass().getSimpleName(), e.getMessage(), e);
            throw e; // Re-throw so invokeAndWait catches it
        }
    }

    private void updateProgress() {
        if (isCancelled()) {
            return;
        }

        String token = WebUtil.getValueFromCookie(passKey, "m0biletoken");

        try {
            if (isCancelled()) {
                return;
            }
            UserSearchResp resp = service.getPersonInfo(passKey, token);
            if (isCancelled()) {
                return;
            }
            if (resp == null || resp.getData() == null) {
                log.warn("[Worker] Failed to update progress, response is null");
                return;
            }

            int majorCurrent = resp.getData().getPeriodDataRU().getGroupLearningTotal().intValue();
            int majorMax = majorSubjectProgressBar.getMaximum();
            int electiveCurrent = resp.getData().getPeriodDataRU().getSelfLearningTotal().intValue();
            int electiveMax = electiveSubjectProgressBar.getMaximum();

            // Calculate percentages for logging
            int majorPercent = majorMax > 0 ? (majorCurrent * 100 / majorMax) : 0;
            int electivePercent = electiveMax > 0 ? (electiveCurrent * 100 / electiveMax) : 0;

            log.info("[Worker] Progress update - " +
                            "MajorSubject: {}/{}h ({}%), ElectiveSubject: {}/{}h ({}%)",
                    majorCurrent, majorMax, majorPercent,
                    electiveCurrent, electiveMax, electivePercent);

            publish(new LearningUpdate(
                    majorMax > 0 ? majorCurrent : -1,
                    electiveMax > 0 ? electiveCurrent : -1,
                    service.getCurrentLessonId(),
                    service.getCurrentLessonName(),
                    service.getClassHours()));
        } catch (Exception e) {
            if (!isCancelled()) {
                log.error("[Worker] Failed to update progress: {}", e.getMessage(), e);
            }
        }
    }

    @Override
    protected void done() {
        log.info("[Worker] === done() START ===, workerException: {}, isCancelled: {}",
                workerException != null, isCancelled());

        // NOTE: do NOT call get() here - it can cause deadlock with EDT.
        // workerException is already set by the background thread for us to use directly.

        // If cancelled by user or interrupted, ignore any stored exception and show cancellation message
        if (isCancelled() || isInterruptedException(workerException)) {
            log.info("[Worker] Worker was cancelled or interrupted, resetting UI buttons");
            SwingUtilities.invokeLater(() -> {
                if (startBt != null) {
                    startBt.setEnabled(true);
                }
                if (stopBt != null) {
                    stopBt.setEnabled(false);
                }
                if (statusLabel != null) {
                    statusLabel.setText("LearningStatus: Cancelled");
                }
                JOptionPane.showMessageDialog(
                        null,
                        "Learning was cancelled",
                        "Cancelled",
                        JOptionPane.INFORMATION_MESSAGE);
            });
            return;
        }

        if (workerException != null) {
            log.error("[Worker] Worker failed with exception, type: {}, message: {}, stack: {}",
                    workerException.getClass().getSimpleName(),
                    workerException.getMessage(), getStackTraceString(workerException));

            SwingUtilities.invokeLater(() -> {
                if (startBt != null) {
                    startBt.setEnabled(true);
                }
                if (stopBt != null) {
                    stopBt.setEnabled(false);
                }
                if (statusLabel != null) {
                    statusLabel.setText("LearningStatus: Error");
                }
                // Show error dialog to user
                JOptionPane.showMessageDialog(
                        null,
                        "Learning failed: " + workerException.getMessage(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
            });
            return;
        }

        log.info("[Worker] Worker completed successfully, resetting UI buttons");

        SwingUtilities.invokeLater(() -> {
            if (startBt != null) {
                startBt.setEnabled(true);
                log.debug("[Worker] Start button enabled");
            }
            if (stopBt != null) {
                stopBt.setEnabled(false);
                log.debug("[Worker] Stop button disabled");
            }
            if (statusLabel != null) {
                statusLabel.setText("LearningStatus: Completed");
            }
        });
    }

    /**
     * Converts an exception's stack trace to a string for logging
     */
    private String getStackTraceString(Exception e) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : e.getStackTrace()) {
            sb.append("\n    at ").append(element.toString());
            // Limit stack trace to first 10 elements to avoid log bloat
            if (sb.toString().split("\n").length >= 10) {
                sb.append("\n    ... (truncated)");
                break;
            }
        }
        return sb.toString();
    }

    /**
     * Checks if the exception or its cause chain contains an InterruptedException.
     * This handles cases where the HTTP client wraps InterruptedException in RuntimeException.
     */
    private boolean isInterruptedException(Exception e) {
        if (e == null) {
            return false;
        }
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof InterruptedException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
