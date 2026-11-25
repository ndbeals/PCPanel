package com.getpcpanel.integration.volume.overlay;

import java.util.function.Supplier;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;

import lombok.extern.log4j.Log4j2;

/**
 * Per-monitor DPI for the overlay window. PCPanel's process is not DPI aware, so Windows stretches a window it draws
 * at 96 DPI to the display's scaling, blurring it. A thread can opt in on its own (Windows 10 1607 and later): the
 * overlay's thread does, and draws at the primary monitor's real pixel density. Where the calls are missing every
 * method falls back to the unaware behaviour: scale 1, nothing switched.
 */
@Log4j2
final class Win32Dpi {
    private static final long PER_MONITOR_AWARE_V2 = -4;
    private static final int MONITOR_DEFAULTTOPRIMARY = 1;
    private static final int MDT_EFFECTIVE_DPI = 0;
    private static final int DEFAULT_DPI = 96;

    private Win32Dpi() {
    }

    /** Makes the calling thread per-monitor DPI aware, so the windows it creates get real pixels. */
    static boolean makeThreadAware() {
        return setThreadContext(new Pointer(PER_MONITOR_AWARE_V2)) != null;
    }

    /** Runs {@code action} with the calling thread per-monitor DPI aware, then puts the thread's awareness back. */
    static <T> T whileAware(Supplier<T> action) {
        var previous = setThreadContext(new Pointer(PER_MONITOR_AWARE_V2));
        try {
            return action.get();
        } finally {
            if (previous != null) {
                setThreadContext(previous);
            }
        }
    }

    /** The primary monitor's scaling (1.5 at 150%) as a per-monitor DPI aware thread sees it; 1 when unknown. */
    static double primaryScale() {
        try {
            var monitor = Function.getFunction("user32", "MonitorFromWindow").invokePointer(new Object[] { null, MONITOR_DEFAULTTOPRIMARY });
            if (monitor == null) {
                return 1;
            }
            var dpi = new Memory(8);
            var hr = Function.getFunction("shcore", "GetDpiForMonitor").invokeInt(new Object[] { monitor, MDT_EFFECTIVE_DPI, dpi, dpi.share(4) });
            var x = dpi.getInt(0);
            return hr == 0 && x > 0 ? x / (double) DEFAULT_DPI : 1;
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            log.debug("Monitor DPI unavailable; drawing the overlay at 96 DPI", e);
            return 1;
        }
    }

    /** The thread's previous DPI awareness context, or null when it could not be set. */
    private static Pointer setThreadContext(Pointer context) {
        try {
            return Function.getFunction("user32", "SetThreadDpiAwarenessContext").invokePointer(new Object[] { context });
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            log.debug("Per-thread DPI awareness unavailable", e);
            return null;
        }
    }
}
