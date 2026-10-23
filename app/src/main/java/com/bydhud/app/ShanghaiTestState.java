package com.bydhud.app;

/** Cached UI snapshot; no platform reads are performed while rendering it. */
public final class ShanghaiTestState {
    public enum Phase { IDLE, STARTING, PREPARING, DRIVING, FINISHING,
        COMPLETED, STOPPED, ERROR, RECOVERING }
    public enum CaptureStatus { NOT_STARTED, STARTING, READY, PARTIAL, FAILED }
    public enum FooterStatus { CLEANUP_PENDING, FAILURE, PARTIAL, NORMAL }

    public static final int PREPARATION_SECONDS = 15;
    public static final int DURATION_SECONDS = 295;
    public final Phase phase;
    public final int elapsedSeconds;
    public final String sessionId;
    public final String detail;
    public final CaptureStatus captureStatus;
    public final boolean cleanupPending;
    public final String captureDay;
    public final String logcatDay;

    public ShanghaiTestState() {
        this(Phase.IDLE, 0, "", "", CaptureStatus.NOT_STARTED, false, "", "");
    }

    ShanghaiTestState(Phase phase, int elapsedSeconds, String sessionId, String detail,
            CaptureStatus captureStatus, boolean cleanupPending, String captureDay, String logcatDay) {
        this.phase = phase;
        this.elapsedSeconds = Math.max(0, Math.min(DURATION_SECONDS, elapsedSeconds));
        this.sessionId = safe(sessionId);
        this.detail = safe(detail);
        this.captureStatus = captureStatus == null ? CaptureStatus.NOT_STARTED : captureStatus;
        this.cleanupPending = cleanupPending;
        this.captureDay = safe(captureDay);
        this.logcatDay = safe(logcatDay);
    }

    public FooterStatus footerStatus() {
        if (cleanupPending) return FooterStatus.CLEANUP_PENDING;
        if (phase == Phase.ERROR || captureStatus == CaptureStatus.FAILED) return FooterStatus.FAILURE;
        if (captureStatus == CaptureStatus.PARTIAL) return FooterStatus.PARTIAL;
        return FooterStatus.NORMAL;
    }

    public boolean isBusy() {
        return phase == Phase.STARTING || phase == Phase.PREPARING
                || phase == Phase.DRIVING || phase == Phase.FINISHING
                || phase == Phase.RECOVERING;
    }

    public boolean canStop() {
        return phase == Phase.STARTING || phase == Phase.PREPARING || phase == Phase.DRIVING;
    }

    public boolean canReset() {
        return phase != Phase.FINISHING && phase != Phase.RECOVERING;
    }

    public float progress() { return elapsedSeconds / (float) DURATION_SECONDS; }

    public boolean protectsDay(String day) {
        return isBusy() && day != null && !day.isEmpty()
                && (day.equals(captureDay) || day.equals(logcatDay));
    }

    static String failureDetail(String language, String code, String blockingApp, String raw) {
        String uk, en, ru;
        switch (safe(code)) {
            case "FOREIGN_MOCK":
                String app = safe(blockingApp);
                if (app.isEmpty()) app = choose(language, "інший застосунок", "another app", "другое приложение");
                return choose(language,
                        "Штучні координати вже передає " + app + ". Зупиніть підміну GPS у цьому застосунку та повторіть тест.",
                        "Mock location is already controlled by " + app + ". Stop GPS simulation in that app and retry the test.",
                        "Искусственные координаты уже передаёт " + app + ". Остановите подмену GPS в этом приложении и повторите тест.");
            case "UNTRACKED_OWN_MOCK":
            case "PENDING_RECOVERY":
                uk = "Попередня підміна GPS не завершена. Повторіть скидання.";
                en = "The previous GPS simulation has not been cleaned up. Retry reset.";
                ru = "Предыдущая подмена GPS не завершена. Повторите сброс.";
                break;
            case "UNKNOWN_PROVIDER":
                uk = "Не вдалося визначити власника GPS.";
                en = "Could not identify the GPS provider owner.";
                ru = "Не удалось определить владельца GPS.";
                break;
            case "OWNERSHIP_LOST":
                uk = "Під час тесту змінився власник або стан GPS.";
                en = "GPS ownership or provider state changed during the test.";
                ru = "Во время теста изменился владелец или состояние GPS.";
                break;
            case "PERMISSION_ERROR":
                uk = "Не вдалося отримати або зберегти дозвіл на підміну GPS.";
                en = "Could not obtain or retain permission for GPS simulation.";
                ru = "Не удалось получить или сохранить разрешение на подмену GPS.";
                break;
            case "PERSISTENCE_ERROR":
                uk = "Помилка збереження або відновлення стану підміни GPS.";
                en = "Could not save or restore GPS simulation state.";
                ru = "Ошибка сохранения или восстановления состояния подмены GPS.";
                break;
            case "SERVICE_START":
                uk = "Не вдалося запустити фоновий сервіс тесту.";
                en = "Could not start the test service.";
                ru = "Не удалось запустить фоновый сервис теста.";
                break;
            case "RECORDING_TIMEOUT":
                uk = "Запис діагностики не став готовим за відведений час.";
                en = "Diagnostic recording did not become ready before the timeout.";
                ru = "Запись диагностики не стала готова за отведённое время.";
                break;
            case "RECORDING_FAILED":
            case "LOGCAT_STOPPED":
                uk = "Обов'язковий запис діагностики зупинився або не запустився.";
                en = "Required diagnostic recording stopped or failed to start.";
                ru = "Обязательная запись диагностики остановилась или не запустилась.";
                break;
            default:
                uk = "Не вдалося виконати тест Шанхая.";
                en = "Could not complete the Shanghai test.";
                ru = "Не удалось выполнить тест Шанхая.";
                break;
        }
        String detail = safe(raw).trim();
        int newline = detail.indexOf('\n');
        if (newline >= 0) detail = detail.substring(0, newline);
        String message = choose(language, uk, en, ru) + (detail.isEmpty() ? "" : "\n" + detail);
        if (!safe(blockingApp).isEmpty()) message += "\n" + failureDetail(language, "FOREIGN_MOCK", blockingApp, "");
        return message;
    }

    private static String choose(String language, String uk, String en, String ru) {
        return "uk".equals(language) ? uk : "ru".equals(language) ? ru : en;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
