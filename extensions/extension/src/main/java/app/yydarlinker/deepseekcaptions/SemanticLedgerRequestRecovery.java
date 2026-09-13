package app.yydarlinker.deepseekcaptions;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Transport-only recovery shim for the rhythm-aware semantic ledger.
 *
 * <p>The semantic controller intentionally grows its window only after a successful request that
 * still cannot close a source-language unit. Network/API failures must not feed that same growth
 * counter. This helper runs at the controller's existing diagnostic checkpoints (after retry
 * bookkeeping but before the next schedule), resets only the affected semantic-attempt counters,
 * preempts background work when an urgent current request starts, and owns display-slice network
 * connections so a video handoff can close them immediately.</p>
 *
 * <p>No source text, translation text, sentence boundary or cached ledger entry is changed here.</p>
 */
final class SemanticLedgerRequestRecovery {
    private static final Object AUX_LOCK = new Object();
    private static final List<HttpURLConnection> AUXILIARY_CONNECTIONS = new ArrayList<>();

    private SemanticLedgerRequestRecovery() {}

    static void observeStage(String stage) {
        if (stage == null || stage.isEmpty()) return;
        try {
            switch (stage) {
                case "FIRST_CUE_REQUEST":
                    // Current playback always wins. The V2 scheduler may have queued background
                    // inventory in the same tick, so cancel it before the priority HTTP call begins.
                    cancelBackgroundRequest();
                    cancelAuxiliaryConnections();
                    break;
                case "CURRENT_UNIT_RETRY":
                    // failPlan has already incremented priorityAttempts by this checkpoint. Undo
                    // only that transport-induced growth; retryAfter remains intact.
                    resetAttemptArray("priorityAttempts");
                    break;
                case "UNIT_BATCH_RETRY":
                    resetAttemptArray("backgroundAttempts");
                    break;
                case "DYNAMIC_STOPPED":
                    // A display slicer used to survive video handoff for up to 11s because the
                    // controller did not own its HttpURLConnection. Close it synchronously now.
                    cancelAuxiliaryConnections();
                    break;
                default:
                    break;
            }
        } catch (Throwable ignored) {
        }
    }

    static boolean semanticRequestBusy() {
        try {
            Object session = activeSession();
            if (session == null) return false;
            Object sessionLock = fieldValue(session, "lock");
            synchronized (sessionLock) {
                return fieldValue(session, "priorityRequest") != null ||
                        fieldValue(session, "backgroundRequest") != null;
            }
        } catch (Throwable ignored) {
            // If reflection ever stops matching a future controller, prefer keeping the optional
            // display slicer rather than breaking captions.
            return false;
        }
    }

    static void registerAuxiliary(HttpURLConnection connection) {
        if (connection == null) return;
        synchronized (AUX_LOCK) {
            AUXILIARY_CONNECTIONS.add(connection);
        }
    }

    static void unregisterAuxiliary(HttpURLConnection connection) {
        if (connection == null) return;
        synchronized (AUX_LOCK) {
            AUXILIARY_CONNECTIONS.remove(connection);
        }
    }

    static void cancelAuxiliaryConnections() {
        List<HttpURLConnection> copy;
        synchronized (AUX_LOCK) {
            if (AUXILIARY_CONNECTIONS.isEmpty()) return;
            copy = new ArrayList<>(AUXILIARY_CONNECTIONS);
            AUXILIARY_CONNECTIONS.clear();
        }
        for (HttpURLConnection connection : copy) {
            try { connection.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static void cancelBackgroundRequest() throws Exception {
        Object session = activeSession();
        if (session == null) return;
        Object sessionLock = fieldValue(session, "lock");
        Object request;
        synchronized (sessionLock) {
            Field field = session.getClass().getDeclaredField("backgroundRequest");
            field.setAccessible(true);
            request = field.get(session);
            if (request == null) return;
            // A request whose prompt already reached the provider has been paid for; cancelling it
            // only throws away a result that was already billed. Leave it running and let the
            // already-independent priority lane claim the network.
            boolean bodySent = false;
            try {
                Field sent = request.getClass().getDeclaredField("bodySent");
                sent.setAccessible(true);
                bodySent = sent.getBoolean(request);
            } catch (Throwable ignored) {
            }
            if (bodySent) return;
            field.set(session, null);
        }
        Method cancel = request.getClass().getDeclaredMethod("cancel");
        cancel.setAccessible(true);
        cancel.invoke(request);
    }

    private static void resetAttemptArray(String name) throws Exception {
        Object session = activeSession();
        if (session == null) return;
        Object sessionLock = fieldValue(session, "lock");
        synchronized (sessionLock) {
            Field field = session.getClass().getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(session);
            if (value instanceof int[]) Arrays.fill((int[]) value, 0);
        }
    }

    private static Object activeSession() throws Exception {
        Field active = SemanticLedgerCaptionControllerV2.class.getDeclaredField("active");
        active.setAccessible(true);
        return active.get(null);
    }

    private static Object fieldValue(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
