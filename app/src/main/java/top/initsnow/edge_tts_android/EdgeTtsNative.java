package top.initsnow.edge_tts_android;

public final class EdgeTtsNative {
    private static final boolean READY;
    private static final String LOAD_ERROR;

    static {
        boolean ready = false;
        String error = "";
        try {
            try {
                System.loadLibrary("c++_shared");
            } catch (UnsatisfiedLinkError ignored) {
            }
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

    public static String beginSynthesisWithBeatrice(
            String requestId,
            String text,
            String voice,
            String rate,
            String volume,
            String pitch,
            String beatriceConfigJson
    ) {
        return nativeBeginSynthesisWithBeatrice(requestId, text, voice, rate, volume, pitch, beatriceConfigJson);
    }

    public static String getBeatriceModelInfo(String modelPath) {
        return nativeGetBeatriceModelInfo(modelPath);
    }

    public static boolean isBeatriceSupported() {
        if (!READY) {
            return false;
        }
        return nativeIsBeatriceSupported();
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

    private static native String nativeBeginSynthesisWithBeatrice(
            String requestId,
            String text,
            String voice,
            String rate,
            String volume,
            String pitch,
            String beatriceConfigJson
    );

    private static native String nativeGetBeatriceModelInfo(String modelPath);

    private static native boolean nativeIsBeatriceSupported();

    private static native byte[] nativeReadPcmChunk(String requestId, int maxBytes);

    private static native String nativeGetLastError(String requestId);

    private static native void nativeStop(String requestId);
}
