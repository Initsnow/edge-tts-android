package top.initsnow.edge_tts_android;

public final class EdgeTtsNative {
    private static final boolean READY;
    private static final String LOAD_ERROR;

    static {
        boolean ready = false;
        String error = "";
        try {
            System.loadLibrary("edge_tts_android");
            ready = true;
        } catch (UnsatisfiedLinkError exception) {
            error = exception.getMessage() == null ? "unknown load error" : exception.getMessage();
        }
        READY = ready;
        LOAD_ERROR = error;
    }

    private EdgeTtsNative() {
    }

    public static boolean isReady() {
        return READY;
    }

    public static String getLoadError() {
        return LOAD_ERROR;
    }

    public static String listVoicesJson() {
        return nativeListVoices();
    }

    public static String beginSynthesis(
            String requestId,
            String text,
            String voice,
            String rate,
            String volume,
            String pitch
    ) {
        return nativeBeginSynthesis(requestId, text, voice, rate, volume, pitch);
    }

    public static byte[] readPcmChunk(String requestId, int maxBytes) {
        return nativeReadPcmChunk(requestId, maxBytes);
    }

    public static String getLastError(String requestId) {
        return nativeGetLastError(requestId);
    }

    public static void stop(String requestId) {
        nativeStop(requestId);
    }

    private static native String nativeListVoices();

    private static native String nativeBeginSynthesis(
            String requestId,
            String text,
            String voice,
            String rate,
            String volume,
            String pitch
    );

    private static native byte[] nativeReadPcmChunk(String requestId, int maxBytes);

    private static native String nativeGetLastError(String requestId);

    private static native void nativeStop(String requestId);
}
