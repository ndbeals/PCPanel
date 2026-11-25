package com.getpcpanel.integration.volume.overlay;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import com.getpcpanel.profile.Save;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HBITMAP;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinDef.HMODULE;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinGDI.BITMAPINFO;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.platform.win32.WinUser.BLENDFUNCTION;
import com.sun.jna.platform.win32.WinUser.MSG;
import com.sun.jna.platform.win32.WinUser.SIZE;
import com.sun.jna.ptr.PointerByReference;

import lombok.extern.log4j.Log4j2;

/**
 * Win32 layered-window implementation of the volume overlay, used on Windows.
 *
 * <p>The Quarkus/GraalVM native image does not support the AWT windowing toolkit
 * ({@code sun.awt.windows.WToolkit}); showing a Swing {@code JWindow} segfaults the native AWT
 * event loop on Windows. This implementation sidesteps AWT entirely:
 * <ul>
 *   <li>the overlay content is drawn with <em>headless</em> Java2D into a
 *       {@code TYPE_INT_ARGB_PRE} {@link BufferedImage} (fully supported in native image), and</li>
 *   <li>it is presented through a Win32 layered window via JNA
 *       ({@code CreateWindowEx} + {@code UpdateLayeredWindow}).</li>
 * </ul>
 *
 * <p><b>No JNA {@code Callback} is used.</b> JNA callbacks (native→Java upcalls) crash the native
 * image (they segfault in {@code JNIJavaCallTrampolineHolder.varargsJavaCallTrampoline}). So instead
 * of registering a custom window class with a Java window-procedure, the window is created with the
 * built-in {@code "STATIC"} class (whose window procedure lives in {@code user32.dll}). A single
 * dedicated thread owns the window, pumps its message queue with {@code PeekMessage} (dispatched to
 * the native procedure), and performs all show/hide/update work directly as JNA <em>downcalls</em>.
 * The thread parks while the overlay is hidden and is unparked by {@link #show} / the dismiss timer.
 *
 * <p>Sizes and positions are in overlay units: pixels at 96 DPI, which is all the process sees, as it is not DPI
 * aware. The window's thread is made per-monitor DPI aware ({@link Win32Dpi}), so the overlay is drawn at the
 * primary monitor's real pixel density instead of being stretched by Windows; {@link #getScreenSize} answers in
 * overlay units at that same scale. A name of several lines makes the window taller than the height it is positioned
 * by, and it grows away from the screen edge it is anchored to.
 */
@Log4j2
class Win32VolumeOverlay implements OverlayWindow {
    /** Built-in window class with a native window procedure (avoids a Java callback). */
    private static final String WINDOW_CLASS = "STATIC";
    private static final int DISMISS_MS = 2000;
    private static final long PUMP_INTERVAL_NANOS = 16_000_000L; // ~60 Hz while visible

    // Window styles / flags missing from JNA's WinUser.
    private static final int WS_EX_TOOLWINDOW = 0x00000080;
    private static final int WS_EX_NOACTIVATE = 0x08000000;
    private static final int ULW_ALPHA = 0x00000002;
    private static final byte AC_SRC_ALPHA = 0x01;
    private static final int PM_REMOVE = 0x0001;

    private final Object lock = new Object();
    private final OverlayRenderer renderer = new OverlayRenderer();
    private final ScheduledExecutorService dismiss = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "PCPanel Overlay Dismiss");
        t.setDaemon(true);
        return t;
    });

    private int width = OverlayRenderer.WIDTH;
    /** The height for a one-line name, which {@link Overlay} positions the window by. */
    private int height = OverlayRenderer.DEFAULT_HEIGHT;
    /** The height for the name being shown. */
    private int contentHeight = OverlayRenderer.DEFAULT_HEIGHT;
    /** How much of the extra height of a taller window goes above its position: 0 when anchored at the top, 1 at the bottom. */
    private float growUp;
    private int x;
    private int y = 48;

    private volatile Thread loopThread;
    private volatile boolean running;
    private volatile boolean pendingShow;
    private volatile boolean pendingHide;
    private boolean visible;            // only touched on the loop thread
    private boolean dpiAware;           // only touched on the loop thread
    private ScheduledFuture<?> dismissFuture;

    @Override
    public void show(OverlayContent content) {
        synchronized (lock) {
            renderer.setValue(Math.round(content.value() * 100f));
            renderer.setIcon(content.icon());
            renderer.setName(content.name());
            renderer.setBarColorOverride(content.barColorCss());
            renderer.setMarker(content.marker() < 0 ? -1 : Math.round(content.marker() * 100f));
            contentHeight = renderer.computeHeight();
        }
        pendingHide = false;
        pendingShow = true;
        ensureStarted();
        wakeLoop();
        scheduleDismiss();
    }

    @Override
    public void setStyles(Save save) {
        synchronized (lock) {
            height = renderer.setStyles(save);
            contentHeight = renderer.computeHeight();
            width = renderer.width();
            growUp = switch (save.getOverlayPosition()) {
                case topLeft, topMiddle, topRight -> 0f;
                case middleLeft, middleMiddle, middleRight -> 0.5f;
                case bottomLeft, bottomMiddle, bottomRight -> 1f;
            };
        }
    }

    @Override
    public void setLocation(int x, int y) {
        synchronized (lock) {
            this.x = x;
            this.y = y;
        }
    }

    @Override
    public int getWidth() {
        synchronized (lock) {
            return width;
        }
    }

    @Override
    public int getHeight() {
        synchronized (lock) {
            return height;
        }
    }

    @Override
    public ScreenSize getScreenSize() {
        return Win32Dpi.whileAware(() -> {
            var user32 = User32.INSTANCE;
            var scale = Win32Dpi.primaryScale();
            return new ScreenSize((int) Math.round(user32.GetSystemMetrics(WinUser.SM_CXSCREEN) / scale),
                    (int) Math.round(user32.GetSystemMetrics(WinUser.SM_CYSCREEN) / scale));
        });
    }

    private void ensureStarted() {
        synchronized (lock) {
            if (loopThread != null) {
                return;
            }
            var thread = new Thread(this::runLoop, "PCPanel Overlay");
            thread.setDaemon(true);
            loopThread = thread;
            thread.start();
        }
    }

    private void wakeLoop() {
        var t = loopThread;
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    private void scheduleDismiss() {
        synchronized (lock) {
            if (dismissFuture != null) {
                dismissFuture.cancel(false);
            }
            dismissFuture = dismiss.schedule(() -> {
                pendingHide = true;
                wakeLoop();
            }, DISMISS_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void runLoop() {
        var user32 = User32.INSTANCE;
        HMODULE hInst = Kernel32.INSTANCE.GetModuleHandle(null);

        int initialWidth;
        int initialHeight;
        synchronized (lock) {
            initialWidth = width;
            initialHeight = height;
        }

        dpiAware = Win32Dpi.makeThreadAware();
        var exStyle = WinUser.WS_EX_LAYERED | WinUser.WS_EX_TRANSPARENT | User32.WS_EX_TOPMOST
                | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE;
        var hWnd = user32.CreateWindowEx(exStyle, WINDOW_CLASS, "PCPanel Volume Overlay",
                WinUser.WS_POPUP, 0, 0, initialWidth, initialHeight, null, null, hInst, null);
        if (hWnd == null) {
            log.error("Unable to create overlay window (error {})", Kernel32.INSTANCE.GetLastError());
            return;
        }

        running = true;
        var msg = new MSG();
        try {
            while (running) {
                while (user32.PeekMessage(msg, null, 0, 0, PM_REMOVE)) {
                    user32.TranslateMessage(msg);
                    user32.DispatchMessage(msg);
                }
                if (pendingShow) {
                    pendingShow = false;
                    doShow(hWnd);
                }
                if (pendingHide) {
                    pendingHide = false;
                    doHide(hWnd);
                }
                if (visible) {
                    LockSupport.parkNanos(PUMP_INTERVAL_NANOS);
                } else {
                    LockSupport.park();
                }
            }
        } catch (RuntimeException e) {
            log.error("Overlay loop terminated", e);
        }
    }

    /** Renders the current state into a layered-window bitmap. Runs on the loop thread. */
    private void doShow(HWND hWnd) {
        int logicalW;
        int logicalH;
        int w;
        int ht;
        int posX;
        int posY;
        var scale = dpiAware ? Win32Dpi.primaryScale() : 1;
        synchronized (lock) {
            logicalW = width;
            logicalH = contentHeight;
            w = (int) Math.ceil(width * scale);
            ht = (int) Math.ceil(contentHeight * scale);
            posX = (int) Math.round(x * scale);
            posY = (int) Math.round((y - (contentHeight - height) * growUp) * scale);
        }

        try {
            // Render and present OUTSIDE the lock: show() runs on the HID input thread and takes the same
            // lock, so a slow render must never hold it. And catch Throwable: the AWT/Java2D image pipeline
            // is fragile in the native image (e.g. BufferedImage class init can fail to load native awt), so
            // a failure here must only skip a frame, never kill this thread.
            var image = new BufferedImage(w, ht, BufferedImage.TYPE_INT_ARGB_PRE);
            var g2 = image.createGraphics();
            try {
                g2.scale(scale, scale);
                renderer.render(g2, logicalW, logicalH);
            } finally {
                g2.dispose();
            }
            pushBitmap(hWnd, image, w, ht, posX, posY);
            if (!visible) {
                User32.INSTANCE.ShowWindow(hWnd, WinUser.SW_SHOWNA);
                visible = true;
            }
        } catch (Throwable t) {
            log.warn("Overlay render/show failed; skipping frame", t);
        }
    }

    private void doHide(HWND hWnd) {
        if (visible) {
            User32.INSTANCE.ShowWindow(hWnd, WinUser.SW_HIDE);
            visible = false;
        }
    }

    /** Pushes the premultiplied-ARGB image into the layered window via {@code UpdateLayeredWindow}. */
    private void pushBitmap(HWND hWnd, BufferedImage image, int w, int ht, int posX, int posY) {
        var user32 = User32.INSTANCE;
        var gdi32 = GDI32.INSTANCE;

        HDC screenDC = user32.GetDC(null);
        HDC memDC = gdi32.CreateCompatibleDC(screenDC);
        HBITMAP bitmap = null;
        HANDLE oldBitmap = null;
        try {
            var bmi = new BITMAPINFO();
            bmi.bmiHeader.biWidth = w;
            bmi.bmiHeader.biHeight = -ht;            // negative → top-down DIB (matches BufferedImage row order)
            bmi.bmiHeader.biPlanes = 1;
            bmi.bmiHeader.biBitCount = 32;
            bmi.bmiHeader.biCompression = WinGDI.BI_RGB;

            var ppvBits = new PointerByReference();
            bitmap = gdi32.CreateDIBSection(memDC, bmi, WinGDI.DIB_RGB_COLORS, ppvBits, null, 0);
            if (bitmap == null) {
                log.warn("CreateDIBSection failed (error {})", Kernel32.INSTANCE.GetLastError());
                return;
            }

            var pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            Pointer bits = ppvBits.getValue();
            // Java TYPE_INT_ARGB_PRE int 0xAARRGGBB → little-endian bytes B,G,R,A, which is exactly the
            // premultiplied BGRA layout Windows expects for ULW_ALPHA.
            bits.write(0, pixels, 0, pixels.length);

            oldBitmap = gdi32.SelectObject(memDC, bitmap);

            var blend = new BLENDFUNCTION();
            blend.SourceConstantAlpha = (byte) 0xFF;
            blend.AlphaFormat = AC_SRC_ALPHA;

            var dst = new POINT(posX, posY);
            var src = new POINT(0, 0);
            var size = new SIZE(w, ht);
            var ok = user32.UpdateLayeredWindow(hWnd, screenDC, dst, size, memDC, src, 0, blend, ULW_ALPHA);
            if (!ok) {
                log.warn("UpdateLayeredWindow failed (error {})", Kernel32.INSTANCE.GetLastError());
            }
        } finally {
            if (oldBitmap != null) {
                gdi32.SelectObject(memDC, oldBitmap);
            }
            if (bitmap != null) {
                gdi32.DeleteObject(bitmap);
            }
            gdi32.DeleteDC(memDC);
            user32.ReleaseDC(null, screenDC);
        }
    }
}
