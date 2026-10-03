package org.libsdl.app;

import android.os.Bundle;
import android.os.Handler;
import android.widget.Toast;
import android.view.Choreographer;
import android.system.Os;
import android.system.ErrnoException;
import android.content.res.AssetManager;
import android.content.SharedPreferences;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.PopupWindow;
import android.view.ViewGroup;
import android.view.View;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.FileWriter;
import java.util.List;
import java.util.ArrayList;

public class MainActivity extends SDLActivity {

    private void setupCrashLogger() {
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                File logFile = new File(getFilesDir(), "abuse_crash.txt");
                PrintWriter pw = new PrintWriter(new FileWriter(logFile));
                ex.printStackTrace(pw);
                pw.close();
            } catch (Exception e) {}
            android.os.Process.killProcess(android.os.Process.myPid());
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setupCrashLogger();
        copyAssets();
        layoutPrefs = getSharedPreferences(layoutFileFor(currentScheme()), MODE_PRIVATE);
        try {
            Os.setenv("ABUSE_PATH", getFilesDir().getAbsolutePath() + "/", true);
        } catch (ErrnoException e) { e.printStackTrace(); }
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN |
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        addTouchControls();
        startTouchControlPolling();
    }

    private void copyAssets() {
        File marker = new File(getFilesDir(), ".assets_extracted");
        if (marker.exists()) return;
        copyAssetFolder(getAssets(), "", getFilesDir().getAbsolutePath());
        try { marker.createNewFile(); } catch (IOException e) {}
    }

    private void copyAssetFolder(AssetManager am, String fromPath, String toPath) {
        try {
            String[] files = am.list(fromPath);
            if (files != null && files.length > 0) {
                new File(toPath).mkdirs();
                for (String f : files) {
                    String assetPath = fromPath.isEmpty() ? f : fromPath + "/" + f;
                    copyAssetFolder(am, assetPath, toPath + "/" + f);
                }
            } else {
                copyAssetFile(am, fromPath, toPath);
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    private void copyAssetFile(AssetManager am, String fromPath, String toPath) {
        try {
            InputStream in = am.open(fromPath);
            new File(toPath).getParentFile().mkdirs();
            FileOutputStream out = new FileOutputStream(toPath);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            in.close();
            out.close();
        } catch (IOException e) { e.printStackTrace(); }
    }

    // ===================== Edit Layout feature =====================

    private SharedPreferences layoutPrefs;
    private boolean editMode = false;
    private final Handler editHandler = new Handler();
    private EditOverlayView editOverlay;
    private Button editModeBtn;
    private Button resetBtn;

    /** Holds everything needed to edit and reset one on-screen control. */
    private static class EditableControl {
        String id;
        Button btn;
        FrameLayout.LayoutParams lp;
        View highlight;
        int baseGravity, baseLeft, baseTop, baseRight, baseBottom, baseWidth, baseHeight;
        float baseAlpha;
    }

    private final List<EditableControl> editControls = new ArrayList<>();

    private int dpToPx(float dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable makeControlBg(boolean oval, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(oval ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE);
        if (!oval) d.setCornerRadius(18f);
        d.setColor(Color.argb(140, 0, 0, 0));
        d.setStroke(dpToPx(2), strokeColor);
        return d;
    }

    private Drawable makeIconBg(boolean oval, int strokeColor, int iconRes, int iconSizeDp) {
        GradientDrawable bg = makeControlBg(oval, strokeColor);
        Drawable icon = getDrawable(iconRes).mutate();
        LayerDrawable ld = new LayerDrawable(new Drawable[]{bg, icon});
        int px = (int) (iconSizeDp * getResources().getDisplayMetrics().density);
        ld.setLayerGravity(1, Gravity.CENTER);
        ld.setLayerSize(1, px, px);
        return ld;
    }

    private int clampInt(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    private void applyLayoutOverride(String id, FrameLayout.LayoutParams lp, int baseWidth, int baseHeight) {
        lp.width = layoutPrefs.getInt("layout_" + id + "_width", baseWidth);
        lp.height = layoutPrefs.getInt("layout_" + id + "_height", baseHeight);
        if (layoutPrefs.contains("layout_" + id + "_left")) {
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.leftMargin = layoutPrefs.getInt("layout_" + id + "_left", 0);
            lp.topMargin = layoutPrefs.getInt("layout_" + id + "_top", 0);
            lp.rightMargin = 0;
            lp.bottomMargin = 0;
        }
    }

    private void saveLayoutPosition(String id, int left, int top) {
        layoutPrefs.edit().putInt("layout_" + id + "_left", left).putInt("layout_" + id + "_top", top).apply();
    }

    private void saveLayoutSize(String id, int width, int height) {
        layoutPrefs.edit().putInt("layout_" + id + "_width", width).putInt("layout_" + id + "_height", height).apply();
    }

    private void saveLayoutAlpha(String id, float alpha) {
        layoutPrefs.edit().putFloat("layout_" + id + "_alpha", alpha).apply();
    }

    private float loadLayoutAlpha(String id, float def) {
        return layoutPrefs.getFloat("layout_" + id + "_alpha", def);
    }

    /** Border-only sibling view that tracks a button's position/size but keeps alpha=1 always,
     *  so the highlight ring stays visible even while the button's own opacity is lowered. */
    private View createHighlightView(FrameLayout parent, FrameLayout.LayoutParams btnLp, boolean circular) {
        final String hlOwner = hlOwnerId;
        View hl = new View(this) {
            @Override public void setVisibility(int v) {
                super.setVisibility(controlHiddenInScheme(hlOwner) ? View.GONE : v);
            }
        };
        GradientDrawable d = new GradientDrawable();
        d.setShape(circular ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE);
        d.setColor(Color.TRANSPARENT);
        d.setStroke(dpToPx(2), Color.YELLOW);
        hl.setBackground(d);
        hl.setAlpha(1f);
        hl.setClickable(false);
        FrameLayout.LayoutParams hlLp = new FrameLayout.LayoutParams(btnLp.width, btnLp.height);
        hlLp.gravity = btnLp.gravity;
        hlLp.leftMargin = btnLp.leftMargin;
        hlLp.topMargin = btnLp.topMargin;
        hlLp.rightMargin = btnLp.rightMargin;
        hlLp.bottomMargin = btnLp.bottomMargin;
        hl.setLayoutParams(hlLp);
        hl.setVisibility(View.GONE);
        parent.addView(hl);
        return hl;
    }

    private void syncHighlight(View hl, FrameLayout.LayoutParams btnLp) {
        FrameLayout.LayoutParams hlLp = (FrameLayout.LayoutParams) hl.getLayoutParams();
        hlLp.width = btnLp.width;
        hlLp.height = btnLp.height;
        hlLp.gravity = btnLp.gravity;
        hlLp.leftMargin = btnLp.leftMargin;
        hlLp.topMargin = btnLp.topMargin;
        hlLp.rightMargin = btnLp.rightMargin;
        hlLp.bottomMargin = btnLp.bottomMargin;
        hl.setLayoutParams(hlLp);
    }

    /** Registers a button as editable: records its base (default) layout so it can be reset later,
     *  and creates its highlight ring. Call AFTER applyLayoutOverride + btn.setLayoutParams + addView. */
    private EditableControl registerEditable(String id, Button btn, FrameLayout.LayoutParams lp, boolean circular,
                                              int baseGravity, int baseLeft, int baseTop, int baseRight, int baseBottom,
                                              int baseWidth, int baseHeight, float baseAlpha) {
        EditableControl c = new EditableControl();
        c.id = id;
        c.btn = btn;
        c.lp = lp;
        c.baseGravity = baseGravity;
        c.baseLeft = baseLeft;
        c.baseTop = baseTop;
        c.baseRight = baseRight;
        c.baseBottom = baseBottom;
        c.baseWidth = baseWidth;
        c.baseHeight = baseHeight;
        c.baseAlpha = baseAlpha;
        hlOwnerId = id;
        c.highlight = createHighlightView(touchOverlay, lp, circular);
        editControls.add(c);
        return c;
    }

    private void reapplyLayouts() {
        for (EditableControl c : editControls) {
            c.lp.gravity = c.baseGravity;
            c.lp.leftMargin = c.baseLeft;
            c.lp.topMargin = c.baseTop;
            c.lp.rightMargin = c.baseRight;
            c.lp.bottomMargin = c.baseBottom;
            c.lp.width = c.baseWidth;
            c.lp.height = c.baseHeight;
            applyLayoutOverride(c.id, c.lp, c.baseWidth, c.baseHeight);
            c.btn.setLayoutParams(c.lp);
            c.btn.setAlpha(loadLayoutAlpha(c.id, c.baseAlpha));
            c.btn.setVisibility(View.VISIBLE);
            c.highlight.setVisibility(editMode ? View.VISIBLE : View.GONE);
            syncHighlight(c.highlight, c.lp);
        }
        if (casualHud != null) casualHud.invalidate();
        syncZoneHandles();
    }

    private void resetAllLayouts() {
        layoutPrefs.edit().clear().apply();
        new Handler().post(() -> refreshControlVisibility());
        for (EditableControl c : editControls) {
            c.lp.gravity = c.baseGravity;
            c.lp.leftMargin = c.baseLeft;
            c.lp.topMargin = c.baseTop;
            c.lp.rightMargin = c.baseRight;
            c.lp.bottomMargin = c.baseBottom;
            c.lp.width = c.baseWidth;
            c.lp.height = c.baseHeight;
            c.btn.setLayoutParams(c.lp);
            c.btn.setAlpha(c.baseAlpha);
            syncHighlight(c.highlight, c.lp);
        }
    }

    private void showOpacityPopup(final Button btn, final String id) {
        runOnUiThread(() -> {
            LinearLayout container = new LinearLayout(this);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setPadding(24, 24, 24, 24);
            container.setBackgroundColor(Color.argb(230, 20, 20, 20));

            TextView label = new TextView(this);
            label.setText("Opacity");
            label.setTextColor(Color.WHITE);
            container.addView(label);

            SeekBar seekBar = new SeekBar(this);
            seekBar.setMax(100);
            seekBar.setProgress((int) (btn.getAlpha() * 100));
            container.addView(seekBar, new LinearLayout.LayoutParams(420, ViewGroup.LayoutParams.WRAP_CONTENT));

            final PopupWindow popup = new PopupWindow(container,
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
            popup.setOutsideTouchable(true);
            popup.setBackgroundDrawable(new ColorDrawable(0));

            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    float alpha = Math.max(0.1f, progress / 100f);
                    btn.setAlpha(alpha);
                    saveLayoutAlpha(id, alpha);
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });

            int[] loc = new int[2];
            btn.getLocationOnScreen(loc);
            popup.showAtLocation(touchOverlay, Gravity.NO_GRAVITY, loc[0], Math.max(0, loc[1] - 160));
        });
    }

    /** Full-screen overlay that becomes the SOLE touch handler while editMode is on (sitting on top of
     *  everything, including the gameplay aim-view and the buttons themselves), so editing never fights
     *  with gameplay input. When editMode is off it consumes nothing and gameplay works exactly as before.
     *  All drag/pinch math happens in this single view's own coordinate space, so a pinch's second finger
     *  does not need to land exactly on the small button — only the first finger needs to start on it. */
    private class EditOverlayView extends View {
        private final Paint gridPaint = new Paint();
        private EditableControl active = null;
        private int pointerId1 = -1, pointerId2 = -1;
        private float startX, startY;
        private int origLeft, origTop;
        private boolean dragging = false;
        private boolean pinching = false;
        private float pinchStartDist = 0f;
        private int pinchStartW, pinchStartH;
        private Runnable longPressRunnable;

        EditOverlayView(android.content.Context ctx) {
            super(ctx);
            gridPaint.setColor(Color.argb(70, 255, 255, 255));
            gridPaint.setStrokeWidth(1f);
            setClickable(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            int step = 60;
            for (int x = 0; x <= w; x += step) canvas.drawLine(x, 0, x, h, gridPaint);
            for (int y = 0; y <= h; y += step) canvas.drawLine(0, y, w, y, gridPaint);
        }

        private boolean isInside(View v, float x, float y) {
            if (v == null || v.getVisibility() != View.VISIBLE) return false;
            return x >= v.getLeft() && x <= v.getRight() && y >= v.getTop() && y <= v.getBottom();
        }

        private EditableControl findControlAt(float x, float y) {
            int pad = dpToPx(13); // generous hit padding so small buttons are easy to grab
            for (int ci = editControls.size() - 1; ci >= 0; ci--) {
                EditableControl c = editControls.get(ci);
                if (controlHiddenInScheme(c.id)) continue;
                float left = c.btn.getLeft() - pad;
                float top = c.btn.getTop() - pad;
                float right = c.btn.getLeft() + c.lp.width + pad;
                float bottom = c.btn.getTop() + c.lp.height + pad;
                if (x >= left && x <= right && y >= top && y <= bottom) return c;
            }
            return null;
        }

        private void endGesture() {
            if (longPressRunnable != null) editHandler.removeCallbacks(longPressRunnable);
            if (active != null) {
                if (pinching) {
                    saveLayoutSize(active.id, active.lp.width, active.lp.height);
                } else if (dragging) {
                    saveLayoutPosition(active.id, active.lp.leftMargin, active.lp.topMargin);
                }
            }
            active = null;
            pointerId1 = -1;
            pointerId2 = -1;
            dragging = false;
            pinching = false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (!editMode) return false; // pass through untouched to buttons/aimView

            int action = event.getActionMasked();
            int idx = event.getActionIndex();

            if (action == MotionEvent.ACTION_DOWN) {
                float x = event.getX(0), y = event.getY(0);
                if (isInside(editModeBtn, x, y) || isInside(resetBtn, x, y)
                    || (schemeBar != null && schemeBar.getVisibility() == View.VISIBLE
                        && isInside(schemeBar, x, y))
                    || isInside(zoneHandleA, x, y) || isInside(zoneHandleM, x, y)) {
                    return false;
                }
                active = findControlAt(x, y);
                if (active == null) {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (schemeMin && now - lastEmptyTapTime < 350
                        && Math.hypot(x - lastEmptyTapX, y - lastEmptyTapY) < dpToPx(48)) {
                        setSchemePanelMin(false);
                        lastEmptyTapTime = 0;
                    } else {
                        lastEmptyTapTime = now;
                        lastEmptyTapX = x;
                        lastEmptyTapY = y;
                    }
                    return true;
                }
                pointerId1 = event.getPointerId(0);
                startX = x;
                startY = y;
                origLeft = active.btn.getLeft();
                origTop = active.btn.getTop();
                dragging = false;
                pinching = false;
                longPressRunnable = () -> showOpacityPopup(active.btn, active.id);
                editHandler.postDelayed(longPressRunnable, 500);
            } else if (action == MotionEvent.ACTION_POINTER_DOWN && active != null && pointerId2 == -1) {
                pointerId2 = event.getPointerId(idx);
                if (longPressRunnable != null) editHandler.removeCallbacks(longPressRunnable);
                pinching = true;
                dragging = false;
                int i1 = event.findPointerIndex(pointerId1);
                int i2 = event.findPointerIndex(pointerId2);
                if (i1 != -1 && i2 != -1) {
                    float ddx = event.getX(i1) - event.getX(i2);
                    float ddy = event.getY(i1) - event.getY(i2);
                    pinchStartDist = (float) Math.sqrt(ddx * ddx + ddy * ddy);
                }
                pinchStartW = active.lp.width;
                pinchStartH = active.lp.height;
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (active == null) return true;
                if (pinching && pointerId2 != -1) {
                    int i1 = event.findPointerIndex(pointerId1);
                    int i2 = event.findPointerIndex(pointerId2);
                    if (i1 != -1 && i2 != -1 && pinchStartDist > 0) {
                        float ddx = event.getX(i1) - event.getX(i2);
                        float ddy = event.getY(i1) - event.getY(i2);
                        float dist = (float) Math.sqrt(ddx * ddx + ddy * ddy);
                        float scale = dist / pinchStartDist;
                        int newW = clampInt((int) (pinchStartW * scale), dpToPx(20), dpToPx(200));
                        int newH = clampInt((int) (pinchStartH * scale), dpToPx(13), dpToPx(167));
                        active.lp.width = newW;
                        active.lp.height = newH;
                        active.btn.setLayoutParams(active.lp);
                        syncHighlight(active.highlight, active.lp);
                    }
                } else {
                    int i1 = event.findPointerIndex(pointerId1);
                    if (i1 == -1) return true;
                    float dx = event.getX(i1) - startX;
                    float dy = event.getY(i1) - startY;
                    final int touchSlop = dpToPx(7);
                    if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                        dragging = true;
                        if (longPressRunnable != null) editHandler.removeCallbacks(longPressRunnable);
                    }
                    if (dragging) {
                        int parentW = getWidth() > 0 ? getWidth() : 2000;
                        int parentH = getHeight() > 0 ? getHeight() : 1200;
                        active.lp.gravity = Gravity.TOP | Gravity.LEFT;
                        active.lp.leftMargin = clampInt(origLeft + (int) dx, 0, Math.max(0, parentW - active.lp.width));
                        active.lp.topMargin = clampInt(origTop + (int) dy, 0, Math.max(0, parentH - active.lp.height));
                        active.lp.rightMargin = 0;
                        active.lp.bottomMargin = 0;
                        active.btn.setLayoutParams(active.lp);
                        syncHighlight(active.highlight, active.lp);
                    }
                }
            } else if (action == MotionEvent.ACTION_POINTER_UP) {
                int liftedId = event.getPointerId(idx);
                if (liftedId == pointerId2) {
                    // second finger lifted: finalize pinch, resume dragging with remaining finger
                    if (active != null) saveLayoutSize(active.id, active.lp.width, active.lp.height);
                    pinching = false;
                    pointerId2 = -1;
                    int i1 = event.findPointerIndex(pointerId1);
                    if (i1 != -1 && active != null) {
                        startX = event.getX(i1);
                        startY = event.getY(i1);
                        origLeft = active.btn.getLeft();
                        origTop = active.btn.getTop();
                    }
                    dragging = false;
                } else if (liftedId == pointerId1) {
                    endGesture();
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                endGesture();
            }
            return true;
        }
    }

    private void setEditMode(boolean on) {
        editMode = on;
        if (editOverlay != null) {
            editOverlay.endGesture();
            editOverlay.setVisibility(on ? View.VISIBLE : View.GONE);
        }
        if (resetBtn != null) {
            resetBtn.setVisibility(on ? View.VISIBLE : View.GONE);
            if (!on) schemeMin = false;
            applyZoneStyle();
            if (schemeBar != null) schemeBar.setVisibility(on ? View.VISIBLE : View.GONE);
        }
        for (EditableControl c : editControls) {
            c.highlight.setVisibility(on ? View.VISIBLE : View.GONE);
        }
    }

    private void addEditModeButton(final FrameLayout parent) {
        editModeBtn = new Button(this);
        editModeBtn.setText("");
        editModeBtn.setAlpha(0.75f);
        editModeBtn.setBackground(makeIconBg(false, Color.argb(210, 255, 255, 255), R.drawable.ic_edit, 24));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dpToPx(47), dpToPx(27));
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.leftMargin = dpToPx(70); // beside ESC
        lp.topMargin = dpToPx(7);
        editModeBtn.setLayoutParams(lp);
        editModeBtn.setOnClickListener(v -> {
            setEditMode(!editMode);
            editModeBtn.setBackground(makeIconBg(false,
                editMode ? Color.argb(230, 60, 200, 60) : Color.argb(210, 255, 255, 255),
                editMode ? R.drawable.ic_check : R.drawable.ic_edit, 24));
        });
        parent.addView(editModeBtn);
    }

    private void addResetButton(final FrameLayout parent) {
        resetBtn = new Button(this);
        resetBtn.setText("");
        resetBtn.setAlpha(0.75f);
        resetBtn.setBackground(makeIconBg(false, Color.argb(220, 220, 60, 50), R.drawable.ic_refresh, 24));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dpToPx(47), dpToPx(27));
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.leftMargin = dpToPx(123); // beside EDIT
        lp.topMargin = dpToPx(7);
        resetBtn.setLayoutParams(lp);
        resetBtn.setVisibility(View.GONE);
        resetBtn.setOnClickListener(v -> resetAllLayouts());
        parent.addView(resetBtn);
    }

    private void addKeyboardButton(final FrameLayout parent) {
        final String id = "KEYS";
        Button btn = new Button(this) {
            @Override public void setVisibility(int v) {
                super.setVisibility(controlHiddenInScheme("KEYS") ? View.GONE : v);
            }
        };
        btn.setText("");
        btn.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_keyboard, 0, 0);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(0, 0, 0, 0);
        float baseAlpha = 0.6f;
        btn.setAlpha(loadLayoutAlpha(id, baseAlpha));
        btn.setBackground(makeControlBg(false, Color.argb(210, 255, 255, 255)));
        int gravity = Gravity.TOP | Gravity.RIGHT;
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dpToPx(47), dpToPx(27));
        lp.gravity = gravity;
        lp.rightMargin = dpToPx(147);
        lp.topMargin = dpToPx(3);
        int baseLeft = 0, baseTop = lp.topMargin, baseRight = lp.rightMargin, baseBottom = 0;
        applyLayoutOverride(id, lp, dpToPx(47), dpToPx(27));
        btn.setLayoutParams(lp);
        parent.addView(btn);
        registerEditable(id, btn, lp, false, gravity, baseLeft, baseTop, baseRight, baseBottom, dpToPx(47), dpToPx(27), baseAlpha);
        btn.setVisibility(View.VISIBLE);
        btn.setOnClickListener(v -> {
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.toggleSoftInput(android.view.inputmethod.InputMethodManager.SHOW_FORCED, 0);
            }
        });
    }

    // ===================== End Edit Layout feature =====================

    private FrameLayout touchOverlay;
    private long suppressFireUntil = 0;
    private void addTouchControls() {
        touchOverlay = new FrameLayout(this);
        // Hidden from the very first frame — otherwise it defaults to VISIBLE and stays that
        // way until the first poll tick (up to 500ms later) gets a chance to check
        // nativeIsInGame() and hide it, causing a brief flash of controls over the CDC logo /
        // loading screen right at app launch, before any gameplay (or even the intro) has begun.
        touchOverlay.setVisibility(View.GONE);
        final float[] lastPos = {160f, 100f};
        android.view.View aimView = new android.view.View(this);
        final int[] aimPointerId = casualDragId;
        final boolean[] gestureIsMenu = {false};
        final float[] menuTouchPos = {0f, 0f};
        final Choreographer.FrameCallback[] menuFrameCallback = new Choreographer.FrameCallback[1];
        aimView.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            int idx = event.getActionIndex();
            if (action == MotionEvent.ACTION_DOWN) {
                boolean menuOpen = false;
                try { menuOpen = nativeIsMenuOpen(); } catch (Throwable t) {}
                gestureIsMenu[0] = menuOpen;
                if (menuOpen) {
                    menuTouchPos[0] = event.getX(idx);
                    menuTouchPos[1] = event.getY(idx);
                    // Check on EVERY rendered frame (Choreographer, vsync-aligned) for as long as
                    // the finger stays down after a menu click: the instant nativeIsMenuOpen()
                    // flips false (save just loaded / menu just closed), force-release the mouse
                    // button right away instead of waiting for the physical finger-up. Frame
                    // callbacks are tied to the same render clock the game loop uses, so this is
                    // tighter/more consistent than a fixed-delay Handler timer.
                    menuFrameCallback[0] = new Choreographer.FrameCallback() {
                        @Override public void doFrame(long frameTimeNanos) {
                            if (!gestureIsMenu[0]) return;
                            boolean stillMenu = true;
                            try { stillMenu = nativeIsMenuOpen(); } catch (Throwable t) {}
                            if (!stillMenu) {
                                SDLActivity.onNativeMouse(0, 1, menuTouchPos[0], menuTouchPos[1], false);
                                gestureIsMenu[0] = false;
                                suppressFireUntil = System.currentTimeMillis() + 400;
                            } else {
                                Choreographer.getInstance().postFrameCallback(this);
                            }
                        }
                    };
                    Choreographer.getInstance().postFrameCallback(menuFrameCallback[0]);
                }
            }
            if (gestureIsMenu[0]) {
                if (action == MotionEvent.ACTION_DOWN) {
                    SDLActivity.onNativeMouse(1, 0, event.getX(idx), event.getY(idx), false);
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    if (menuFrameCallback[0] != null) Choreographer.getInstance().removeFrameCallback(menuFrameCallback[0]);
                    if (gestureIsMenu[0]) {
                        SDLActivity.onNativeMouse(0, 1, event.getX(idx), event.getY(idx), false);
                        gestureIsMenu[0] = false;
                    }
                } else if (action == MotionEvent.ACTION_MOVE) {
                    menuTouchPos[0] = event.getX(idx);
                    menuTouchPos[1] = event.getY(idx);
                    SDLActivity.onNativeMouse(0, 2, event.getX(idx), event.getY(idx), false);
                }
                return true;
            }
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                if (aimPointerId[0] == -1) {
                    aimPointerId[0] = event.getPointerId(idx);
                    lastPos[0] = event.getX(idx);
                    lastPos[1] = event.getY(idx);
                }
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (aimPointerId[0] != -1) {
                    int pIdx = event.findPointerIndex(aimPointerId[0]);
                    if (pIdx != -1) {
                        lastPos[0] = event.getX(pIdx);
                        lastPos[1] = event.getY(pIdx);
                        SDLActivity.onNativeMouse(0, 2, lastPos[0], lastPos[1], false);
                    }
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
                if (event.getPointerId(idx) == aimPointerId[0]) {
                    aimPointerId[0] = -1;
                }
            }
            return true;
        });
        touchOverlay.addView(aimView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        casualHud = new CasualHud(this);
        touchOverlay.addView(casualHud, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addFloatZone(touchOverlay, lastPos, "AIMZ", true);
        addFloatZone(touchOverlay, lastPos, "MOVEZ", false);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        int btnSize = dpToPx(57);

        // Movement D-pad (bottom-left)
        addBtn(touchOverlay, "<", Gravity.BOTTOM | Gravity.LEFT, dpToPx(47), dpToPx(27), btnSize, KeyEvent.KEYCODE_DPAD_LEFT, false, R.drawable.ic_chevron_left);
        addBtn(touchOverlay, ">", Gravity.BOTTOM | Gravity.LEFT, dpToPx(167), dpToPx(27), btnSize, KeyEvent.KEYCODE_DPAD_RIGHT, false, R.drawable.ic_chevron_right);
        addBtn(touchOverlay, "^", Gravity.BOTTOM | Gravity.LEFT, dpToPx(107), dpToPx(87), btnSize, KeyEvent.KEYCODE_DPAD_UP, false, R.drawable.ic_expand_less);
        addBtn(touchOverlay, "v", Gravity.BOTTOM | Gravity.LEFT, dpToPx(107), dpToPx(27), btnSize, KeyEvent.KEYCODE_DPAD_DOWN, false, R.drawable.ic_expand_more);
        addMovePad(touchOverlay);

        // ESC (top-left)
        addBtn(touchOverlay, "ESC", Gravity.TOP | Gravity.LEFT, dpToPx(7), dpToPx(7), btnSize, KeyEvent.KEYCODE_ESCAPE, true, R.drawable.ic_menu);
        addEditModeButton(touchOverlay);
        addResetButton(touchOverlay);
        addSchemeSelector(touchOverlay);
        addKeyboardButton(touchOverlay);

        // Weapon prev/next (right side, mid-height)
        addBtn(touchOverlay, "Q", Gravity.TOP | Gravity.RIGHT, dpToPx(7), dpToPx(133), btnSize, KeyEvent.KEYCODE_Q, true, R.drawable.ic_chevron_left);
        addBtn(touchOverlay, "E", Gravity.TOP | Gravity.RIGHT, dpToPx(7), dpToPx(73), btnSize, KeyEvent.KEYCODE_E, true, R.drawable.ic_chevron_right);

        // Run (bottom-right)
        addBtn(touchOverlay, "SHIFT", Gravity.BOTTOM | Gravity.RIGHT, dpToPx(40), dpToPx(20), btnSize, KeyEvent.KEYCODE_SHIFT_LEFT, true, R.drawable.ic_bolt);
        addBtnWide(touchOverlay, "SPACE", Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, dpToPx(20), btnSize * 2, btnSize, KeyEvent.KEYCODE_SPACE);

        addFireButton(touchOverlay, lastPos, Gravity.BOTTOM | Gravity.RIGHT, dpToPx(127), dpToPx(67), btnSize + dpToPx(30));
        addAimPad(touchOverlay, lastPos);
        addToggleButton(touchOverlay);

        addZoneHandles(touchOverlay);
        editOverlay = new EditOverlayView(this);
        editOverlay.setVisibility(View.GONE);
        touchOverlay.addView(editOverlay, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        addContentView(touchOverlay, flp);
        addStrayShotGuard();
    }

    private void addBtn(FrameLayout parent, String label, int gravity, int marginRight, int marginVert, int size, final int keyCode, final boolean oval, final int iconRes) {
        final String id = label;
        Button btn = new Button(this) {
            @Override public void setVisibility(int v) {
                super.setVisibility(controlHiddenInScheme(id) ? View.GONE : v);
            }
        };
        btn.setText("");
        float baseAlpha = 0.6f;
        btn.setAlpha(loadLayoutAlpha(id, baseAlpha));
        btn.setBackground(makeIconBg(oval, Color.argb(210, 255, 255, 255), iconRes, 26));
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = gravity;
        if ((gravity & Gravity.LEFT) == Gravity.LEFT) lp.leftMargin = marginRight;
        if ((gravity & Gravity.RIGHT) == Gravity.RIGHT) lp.rightMargin = marginRight;
        if ((gravity & Gravity.TOP) == Gravity.TOP) lp.topMargin = marginVert;
        if ((gravity & Gravity.BOTTOM) == Gravity.BOTTOM) lp.bottomMargin = marginVert;
        int baseLeft = lp.leftMargin, baseTop = lp.topMargin, baseRight = lp.rightMargin, baseBottom = lp.bottomMargin;
        applyLayoutOverride(id, lp, size, size);
        btn.setLayoutParams(lp);
        parent.addView(btn);
        registerEditable(id, btn, lp, oval, gravity, baseLeft, baseTop, baseRight, baseBottom, size, size, baseAlpha);
        btn.setVisibility(View.VISIBLE);
        btn.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                SDLActivity.onNativeKeyDown(keyCode);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
                SDLActivity.onNativeKeyUp(keyCode);
            }
            return true;
        });
    }

    private void addBtnWide(FrameLayout parent, String label, int gravity, int marginRight, int marginVert, int width, int height, final int keyCode) {
        final String id = label;
        Button btn = new Button(this) {
            @Override public void setVisibility(int v) {
                super.setVisibility(controlHiddenInScheme(id) ? View.GONE : v);
            }
        };
        btn.setText("");
        float baseAlpha = 0.5f;
        btn.setAlpha(loadLayoutAlpha(id, baseAlpha));
        btn.setBackground(makeControlBg(false, Color.argb(210, 255, 255, 255)));
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, height);
        lp.gravity = gravity;
        if ((gravity & Gravity.LEFT) == Gravity.LEFT) lp.leftMargin = marginRight;
        if ((gravity & Gravity.RIGHT) == Gravity.RIGHT) lp.rightMargin = marginRight;
        if ((gravity & Gravity.TOP) == Gravity.TOP) lp.topMargin = marginVert;
        if ((gravity & Gravity.BOTTOM) == Gravity.BOTTOM) lp.bottomMargin = marginVert;
        int baseLeft = lp.leftMargin, baseTop = lp.topMargin, baseRight = lp.rightMargin, baseBottom = lp.bottomMargin;
        applyLayoutOverride(id, lp, width, height);
        btn.setLayoutParams(lp);
        parent.addView(btn);
        registerEditable(id, btn, lp, false, gravity, baseLeft, baseTop, baseRight, baseBottom, width, height, baseAlpha);
        btn.setVisibility(View.VISIBLE);
        btn.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                SDLActivity.onNativeKeyDown(keyCode);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
                SDLActivity.onNativeKeyUp(keyCode);
            }
            return true;
        });
    }


    // ===== Casual aim scheme: floating right-side aim stick. Classic code path is untouched. =====
    private static final String SCHEME_CLASSIC = "classic";
    private static final String SCHEME_CASUAL = "casual";
    private static final String SCHEME_DPAD = "dpad";
    private static final String SCHEME_CUSTOM = "custom";
    private static final int CASUAL_RADIUS_DP = 56;      // stick travel
    private static final int CASUAL_CURSOR_DP = 140;     // cursor distance from player
    private static final float CASUAL_DEADZONE = 0.15f;  // below: keep last angle
    private static final float CASUAL_FIRE_ZONE = 0.80f; // at/above: fire
    private android.widget.LinearLayout schemeBar;
    private android.widget.TextView schemeInfo;
    private Button schemeMinBtn;
    private boolean schemeMin = false;
    private long lastEmptyTapTime = 0;
    private float lastEmptyTapX, lastEmptyTapY;
    private Button schemeClassicBtn, schemeCasualBtn, schemeDpadBtn, schemeCustomBtn;
    private android.view.View casualHud;
    private Button fireBtn;
    private final int[] casualDragId = {-1};
    private String hlOwnerId;
    private Button moveBtn;
    private int movePointerId = -1;
    private float moveKx, moveKy;
    private boolean mkL, mkR, mkU, mkD;
    private android.graphics.drawable.Drawable fireBgClassic;
    private int casualPointerId = -1;
    private float casualOx, casualOy, casualKx, casualKy, casualAng = 0f, casualMag = 0f;
    private boolean casualFiring = false;
    private final float[] casualCursor = {0f, 0f};
    private float[] casualLastPos;
    private Choreographer.FrameCallback casualFrame;

    private native int[] nativeGetPlayerScreenPos();

    private CharSequence presetInfo(String scheme) {
        String title, body;
        if (SCHEME_CUSTOM.equals(scheme)) {
            title = "Custom";
            body = "Choose your own controls.\nTap Custom again to switch controls on or off.\nControls that are off disappear completely.";
        } else if (SCHEME_CASUAL.equals(scheme)) {
            title = "Preset 2: Analog";
            body = "Left analog: move (up = jump, down = use).\nRight analog: aim 360 degrees, push to the red ring to fire.\nDrag anywhere on the screen to move the crosshair.";
        } else if (SCHEME_DPAD.equals(scheme)) {
            title = "Preset 3: D-pad + analog aim";
            body = "D-pad: move, jump (up), use (down).\nAnalog pad: aim 360 degrees, push to the red ring to fire.\nDrag anywhere on the screen to move the crosshair.";
        } else {
            title = "Preset 1: Classic";
            body = "D-pad: move, jump (up), use (down).\nDrag the FIRE circle to aim, hold it to fire.\nDrag anywhere on the screen to move the crosshair.";
        }
        android.text.SpannableString sp = new android.text.SpannableString(title + "\n" + body);
        sp.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, title.length(),
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sp;
    }

    private void setSchemePanelMin(boolean m) {
        schemeMin = m;
        if (schemeBar != null) schemeBar.setVisibility(editMode ? View.VISIBLE : View.GONE);
    }

    private String currentScheme() {
        return getSharedPreferences("control_scheme", MODE_PRIVATE).getString("scheme", SCHEME_CLASSIC);
    }

    private String layoutFileFor(String scheme) {
        if (SCHEME_CASUAL.equals(scheme)) return "touch_layout_casual";
        if (SCHEME_DPAD.equals(scheme)) return "touch_layout_dpad";
        if (SCHEME_CUSTOM.equals(scheme)) return "touch_layout_custom";
        return "touch_layout";
    }

    // "Casual" here means: the analog aim pad is active (Preset 2 and Preset 3)
    private boolean isCasualScheme() {
        String sc = currentScheme();
        if (SCHEME_CLASSIC.equals(sc)) return false;
        if (SCHEME_CUSTOM.equals(sc)) return false;
        return true;
    }

    private boolean casualOwns(MotionEvent event, boolean menuGesture) {
        if (menuGesture) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            try { if (nativeIsMenuOpen()) return false; } catch (Throwable t) {}
        }
        return true;
    }

private float casualPadRadius(Button b) {
        return Math.max(b.getWidth() / 2f, dpToPx(36));
    }

    private void applyPadStyle() {
        if (fireBtn != null) fireBtn.setBackground(isCasualScheme() ? null : fireBgClassic);
        if (casualHud != null) casualHud.invalidate();
    }

    private boolean handleCasualPad(Button btn, MotionEvent event, float[] lastPos) {
        casualLastPos = lastPos;
        if (editMode) return true;
        int action = event.getActionMasked();
        int idx = event.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN) {
            casualPointerId = event.getPointerId(0);
            {
                float[] pp = casualPlayerPos();
                float ddx = lastPos[0] - pp[0], ddy = lastPos[1] - pp[1];
                if (Math.hypot(ddx, ddy) > dpToPx(8)) casualAng = (float) Math.atan2(ddy, ddx);
            }
            updateCasualPad(btn, event, 0);
            startCasualFrames();
        } else if (action == MotionEvent.ACTION_MOVE) {
            int i = casualPointerId == -1 ? -1 : event.findPointerIndex(casualPointerId);
            if (i != -1) updateCasualPad(btn, event, i);
        } else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
            stopCasual();
        } else if (action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(idx) == casualPointerId) {
            stopCasual();
        }
        return true;
    }

    private void updateCasualPad(Button btn, MotionEvent e, int i) {
        casualOx = btn.getLeft() + btn.getWidth() / 2f;
        casualOy = btn.getTop() + btn.getHeight() / 2f;
        float dx = btn.getLeft() + e.getX(i) - casualOx, dy = btn.getTop() + e.getY(i) - casualOy;
        float d = (float) Math.hypot(dx, dy);
        float r = casualPadRadius(btn);
        float m = Math.min(d, r);
        casualMag = m / r;
        casualKx = casualOx + (d > 0f ? dx / d * m : 0f);
        casualKy = casualOy + (d > 0f ? dy / d * m : 0f);
        if (casualMag > CASUAL_DEADZONE) casualAng = (float) Math.atan2(dy, dx);
        applyCasualAim();
    }

        private float[] casualPlayerPos() {
        try {
            int[] p = nativeGetPlayerScreenPos();
            if (p != null && p.length == 2 && p[0] >= 0 && p[1] >= 0) return new float[]{p[0], p[1]};
        } catch (Throwable t) {}
        return new float[]{touchOverlay.getWidth() / 2f, touchOverlay.getHeight() / 2f};
    }

    private void applyCasualAim() {
        if (casualPointerId == -1) return;
        float[] pp = casualPlayerPos();
        float cs = (float) Math.cos(casualAng), sn = (float) Math.sin(casualAng);
        float t = dpToPx(CASUAL_CURSOR_DP);
        float w = touchOverlay.getWidth() - 1f, h = touchOverlay.getHeight() - 1f;
        // shrink the radius (not the angle) when the cursor would leave the screen
        if (cs > 0.001f) t = Math.min(t, (w - pp[0]) / cs); else if (cs < -0.001f) t = Math.min(t, -pp[0] / cs);
        if (sn > 0.001f) t = Math.min(t, (h - pp[1]) / sn); else if (sn < -0.001f) t = Math.min(t, -pp[1] / sn);
        t = Math.max(t, dpToPx(12));
        float x = pp[0] + cs * t, y = pp[1] + sn * t;
        if (casualDragId[0] != -1 && casualLastPos != null) {
            x = casualLastPos[0]; y = casualLastPos[1]; // dragged crosshair owns the cursor
        } else if (casualLastPos != null) {
            casualLastPos[0] = x; casualLastPos[1] = y;
        }
        casualCursor[0] = x; casualCursor[1] = y;
        boolean wantFire = casualFiring ? casualMag >= CASUAL_FIRE_ZONE - 0.08f : casualMag >= CASUAL_FIRE_ZONE;
        SDLActivity.onNativeMouse(casualFiring ? 1 : 0, 2, x, y, false);
        if (wantFire && !casualFiring && System.currentTimeMillis() >= suppressFireUntil) {
            SDLActivity.onNativeMouse(1, 0, x, y, false);
            casualFiring = true;
        } else if (!wantFire && casualFiring) {
            SDLActivity.onNativeMouse(0, 1, x, y, false);
            casualFiring = false;
        }
        if (casualHud != null) casualHud.invalidate();
    }

    private void startCasualFrames() {
        if (casualFrame != null) return;
        casualFrame = new Choreographer.FrameCallback() {
            @Override public void doFrame(long frameTimeNanos) {
                if (casualPointerId == -1) { casualFrame = null; return; }
                applyCasualAim();
                Choreographer.getInstance().postFrameCallback(this);
            }
        };
        Choreographer.getInstance().postFrameCallback(casualFrame);
    }

    private void stopCasual() {
        casualPointerId = -1;
        casualFloating = false;
        if (casualFrame != null) { Choreographer.getInstance().removeFrameCallback(casualFrame); casualFrame = null; }
        if (casualFiring) {
            SDLActivity.onNativeMouse(0, 1, casualCursor[0], casualCursor[1], false);
            casualFiring = false;
        }
        casualMag = 0f;
        if (casualHud != null) casualHud.invalidate();
    }

    private class CasualHud extends android.view.View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        CasualHud(android.content.Context c) { super(c); }
        @Override protected void onDraw(android.graphics.Canvas cv) {
            drawMovePad(cv, p);
            if (casualFloating && casualPointerId != -1) drawFloatAim(cv, p);
            Button ab = aimPadBtn();
            if (ab == null || ab.getVisibility() != View.VISIBLE) return;
            float r = casualPadRadius(ab);
            float cx = ab.getLeft() + ab.getWidth() / 2f, cy = ab.getTop() + ab.getHeight() / 2f;
            boolean on = casualPointerId != -1;
            float kx = on ? casualKx : cx, ky = on ? casualKy : cy;
            float a = ab.getAlpha();
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(dpToPx(2));
            p.setColor(Color.argb(padA(0.85f, a), 255, 255, 255));
            cv.drawCircle(cx, cy, r, p);
            p.setColor(Color.argb(padA(0.95f, a), 255, 90, 80));
            cv.drawCircle(cx, cy, r * CASUAL_FIRE_ZONE, p);
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(casualFiring ? Color.argb(padA(1f, a), 255, 90, 80) : Color.argb(padA(0.9f, a), 255, 255, 255));
            cv.drawCircle(kx, ky, dpToPx(18), p);
            drawCrosshairIcon(cv, p, kx, ky, casualFiring, ab.getAlpha());
        }
    }

    private int padA(float k, float a) {
        return Math.max(0, Math.min(255, (int) (255 * k * a)));
    }

        private void drawCrosshairIcon(android.graphics.Canvas cv, android.graphics.Paint p, float x, float y, boolean firing, float a) {
        float d = getResources().getDisplayMetrics().density;
        int al = Math.max(0, Math.min(255, (int) (255 * a)));
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(1.8f * d);
        p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        p.setColor(firing ? Color.argb(al, 255, 255, 255) : Color.argb(al, 28, 31, 36));
        cv.drawCircle(x, y, 6f * d, p);
        cv.drawLine(x - 12f * d, y, x - 3.5f * d, y, p);
        cv.drawLine(x + 3.5f * d, y, x + 12f * d, y, p);
        cv.drawLine(x, y - 12f * d, x, y - 3.5f * d, p);
        cv.drawLine(x, y + 3.5f * d, x, y + 12f * d, p);
        p.setStyle(android.graphics.Paint.Style.FILL);
        cv.drawCircle(x, y, 1.4f * d, p);
        p.setStrokeCap(android.graphics.Paint.Cap.BUTT);
    }

    private SharedPreferences cPrefs() { return getSharedPreferences("touch_layout_custom", MODE_PRIVATE); }
    private String cAim() { return cPrefs().getString("c_aim", "off"); }
    private String cMove() { return cPrefs().getString("c_move", "off"); }
    private boolean cOn(String k) { return cPrefs().getBoolean("c_on_" + k, true); }

    private void refreshControlVisibility() {
        stopCasual();
        releaseMove();
        for (EditableControl c : editControls) {
            c.btn.setVisibility(View.VISIBLE);
            c.highlight.setVisibility(editMode ? View.VISIBLE : View.GONE);
        }
        applyPadStyle();
        syncZoneHandles();
        if (casualHud != null) casualHud.invalidate();
    }

    private void styleOpt(android.widget.TextView t, boolean on) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setCornerRadius(dpToPx(8));
        d.setColor(on ? Color.argb(235, 60, 200, 60) : Color.argb(60, 255, 255, 255));
        d.setStroke(dpToPx(1), Color.argb(120, 255, 255, 255));
        t.setBackground(d);
        t.setTextColor(Color.WHITE);
    }

    private android.view.View customToggleRow(final String key, String label) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dpToPx(4), dpToPx(5), dpToPx(4), dpToPx(5));
        android.widget.TextView name = new android.widget.TextView(this);
        name.setText(label);
        name.setTextColor(Color.WHITE);
        name.setTextSize(15);
        row.addView(name, new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final android.widget.TextView st = new android.widget.TextView(this);
        st.setGravity(Gravity.CENTER);
        st.setTextSize(13);
        st.setTypeface(null, android.graphics.Typeface.BOLD);
        st.setMinWidth(dpToPx(52));
        st.setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6));
        final Runnable paint = () -> { boolean on = cOn(key); st.setText(on ? "ON" : "OFF"); styleOpt(st, on); };
        paint.run();
        row.setOnClickListener(v -> {
            cPrefs().edit().putBoolean("c_on_" + key, !cOn(key)).apply();
            paint.run();
            refreshControlVisibility();
        });
        row.addView(st);
        return row;
    }

    private android.view.View customOptionRow(String title, final String[][] opts, final String key, final String def) {
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(0, dpToPx(14), 0, 0);
        android.widget.TextView t = new android.widget.TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(t);
        android.widget.LinearLayout line = new android.widget.LinearLayout(this);
        line.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        line.setPadding(0, dpToPx(6), 0, 0);
        final android.widget.TextView[] cells = new android.widget.TextView[opts.length];
        final Runnable paint = () -> {
            String cur = cPrefs().getString(key, def);
            for (int j = 0; j < cells.length; j++) styleOpt(cells[j], opts[j][0].equals(cur));
        };
        for (int i = 0; i < opts.length; i++) {
            final int idx = i;
            android.widget.TextView c = new android.widget.TextView(this);
            c.setText(opts[i][1]);
            c.setGravity(Gravity.CENTER);
            c.setTextSize(13);
            c.setPadding(dpToPx(6), dpToPx(10), dpToPx(6), dpToPx(10));
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dpToPx(6);
            c.setOnClickListener(v -> {
                cPrefs().edit().putString(key, opts[idx][0]).apply();
                paint.run();
                refreshControlVisibility();
            });
            cells[i] = c;
            line.addView(c, lp);
        }
        paint.run();
        box.addView(line);
        return box;
    }

    private FrameLayout makeSwitchView() {
        FrameLayout f = new FrameLayout(this);
        View knob = new View(this);
        GradientDrawable kd = new GradientDrawable();
        kd.setShape(GradientDrawable.OVAL);
        kd.setColor(Color.WHITE);
        knob.setBackground(kd);
        f.addView(knob, new FrameLayout.LayoutParams(dpToPx(24), dpToPx(24)));
        return f;
    }

    private void paintSwitch(FrameLayout sw, boolean on) {
        GradientDrawable t = new GradientDrawable();
        t.setCornerRadius(dpToPx(15));
        t.setColor(on ? Color.argb(255, 60, 200, 60) : Color.argb(255, 90, 96, 104));
        sw.setBackground(t);
        View knob = sw.getChildAt(0);
        FrameLayout.LayoutParams kl = (FrameLayout.LayoutParams) knob.getLayoutParams();
        kl.gravity = Gravity.CENTER_VERTICAL | (on ? Gravity.RIGHT : Gravity.LEFT);
        kl.leftMargin = dpToPx(3);
        kl.rightMargin = dpToPx(3);
        knob.setLayoutParams(kl);
    }

    private android.view.View customRowView(final String key, String label, int iw, int ih,
                                            android.graphics.drawable.Drawable iconBg, final String prefKey) {
        final boolean analog = prefKey != null;
        final android.widget.LinearLayout wrap = new android.widget.LinearLayout(this);
        wrap.setOrientation(android.widget.LinearLayout.VERTICAL);
        wrap.setPadding(dpToPx(10), dpToPx(5), dpToPx(10), dpToPx(5));
        final android.widget.LinearLayout main = new android.widget.LinearLayout(this);
        main.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        main.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout col = new FrameLayout(this);
        View icon = new View(this);
        icon.setBackground(iconBg);
        col.addView(icon, new FrameLayout.LayoutParams(dpToPx(iw), dpToPx(ih), Gravity.CENTER));
        main.addView(col, new android.widget.LinearLayout.LayoutParams(dpToPx(60), dpToPx(52)));
        android.widget.TextView name = new android.widget.TextView(this);
        name.setText(label);
        name.setTextColor(Color.WHITE);
        name.setTextSize(17);
        name.setPadding(dpToPx(8), 0, 0, 0);
        main.addView(name, new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final FrameLayout swv = makeSwitchView();
        main.addView(swv, new android.widget.LinearLayout.LayoutParams(dpToPx(56), dpToPx(30)));
        wrap.addView(main);
        boolean on0 = analog ? !"off".equals(cPrefs().getString(prefKey, "off")) : cOn(key);
        paintSwitch(swv, on0);
        final android.widget.LinearLayout sub = analog ? new android.widget.LinearLayout(this) : null;
        final Runnable paintSub;
        if (analog) {
            sub.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            sub.setPadding(dpToPx(68), dpToPx(4), 0, dpToPx(4));
            final String[][] modes = {{"fixed", "Fixed"}, {"floating", "Floating"}};
            final android.widget.TextView[] chips = new android.widget.TextView[2];
            paintSub = () -> {
                String cur = cPrefs().getString(prefKey, "off");
                for (int j = 0; j < 2; j++) styleOpt(chips[j], modes[j][0].equals(cur));
            };
            for (int i = 0; i < 2; i++) {
                final int idx = i;
                android.widget.TextView c = new android.widget.TextView(this);
                c.setText(modes[i][1]);
                c.setGravity(Gravity.CENTER);
                c.setTextSize(14);
                c.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
                android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dpToPx(8);
                c.setOnClickListener(v -> {
                    cPrefs().edit().putString(prefKey, modes[idx][0]).apply();
                    paintSub.run();
                    refreshControlVisibility();
                });
                chips[i] = c;
                sub.addView(c, lp);
            }
            sub.setVisibility(on0 ? View.VISIBLE : View.GONE);
            paintSub.run();
            wrap.addView(sub);
        } else {
            paintSub = null;
        }
        main.setOnClickListener(v -> {
            boolean on;
            if (analog) {
                String cur = cPrefs().getString(prefKey, "off");
                if (!"off".equals(cur)) {
                    cPrefs().edit().putString(prefKey + "_last", cur).putString(prefKey, "off").apply();
                    on = false;
                } else {
                    cPrefs().edit().putString(prefKey, cPrefs().getString(prefKey + "_last", "fixed")).apply();
                    on = true;
                }
            } else {
                on = !cOn(key);
                cPrefs().edit().putBoolean("c_on_" + key, on).apply();
            }
            paintSwitch(swv, on);
            if (sub != null) {
                sub.setVisibility(on ? View.VISIBLE : View.GONE);
                paintSub.run();
            }
            refreshControlVisibility();
        });
        return wrap;
    }

    private void showCustomDialog() {
        final int W = Color.argb(210, 255, 255, 255);
        int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
        android.widget.LinearLayout panel = new android.widget.LinearLayout(this);
        panel.setOrientation(android.widget.LinearLayout.VERTICAL);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setCornerRadius(dpToPx(14));
        pbg.setColor(Color.argb(245, 12, 20, 30));
        pbg.setStroke(dpToPx(2), Color.argb(200, 70, 200, 230));
        panel.setBackground(pbg);
        FrameLayout header = new FrameLayout(this);
        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("Custom controls");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        android.widget.TextView close = new android.widget.TextView(this);
        close.setText("\u2715");
        close.setTextColor(Color.WHITE);
        close.setTextSize(22);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10));
        header.addView(close, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL));
        header.setPadding(0, dpToPx(8), 0, dpToPx(4));
        panel.addView(header);
        android.widget.LinearLayout list = new android.widget.LinearLayout(this);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        list.addView(customRowView("DPAD", "D-pad", 44, 44, makeIconBg(false, W, R.drawable.ic_expand_less, 26), null));
        list.addView(customRowView("FIRE", "FIRE circle", 48, 48, makeIconBg(true, Color.argb(220, 220, 60, 50), R.drawable.ic_target, 30), null));
        list.addView(customRowView("AIMA", "Analog aim", 48, 48, makeIconBg(true, W, R.drawable.ic_target, 26), "c_aim"));
        list.addView(customRowView("MOVEA", "Analog move", 48, 48, makeControlBg(true, W), "c_move"));
        list.addView(customRowView("Q", "Prev", 44, 44, makeIconBg(true, W, R.drawable.ic_chevron_left, 26), null));
        list.addView(customRowView("E", "Next", 44, 44, makeIconBg(true, W, R.drawable.ic_chevron_right, 26), null));
        list.addView(customRowView("SHIFT", "Special", 44, 44, makeIconBg(true, W, R.drawable.ic_bolt, 26), null));
        list.addView(customRowView("SPACE", "SPACE", 48, 26, makeControlBg(false, W), null));
        list.addView(customRowView("ESC", "ESC", 44, 44, makeIconBg(true, W, R.drawable.ic_menu, 26), null));
        list.addView(customRowView("KEYS", "KEYS", 48, 30, makeIconBg(false, W, R.drawable.ic_keyboard, 24), null));
        list.addView(customRowView("HIDE", "HIDE / SHOW", 48, 30, makeIconBg(false, W, R.drawable.ic_eye_open, 24), null));
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(list);
        panel.addView(scroll, new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (sh * 0.88f) - dpToPx(72)));
        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dlg.setContentView(panel);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            dlg.getWindow().getDecorView().setPadding(0, 0, 0, 0);
        }
        close.setOnClickListener(v -> dlg.dismiss());
        dlg.show();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setLayout((int) (sw * 0.62f), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void showCustomDialogOld() {
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(dpToPx(20), dpToPx(6), dpToPx(20), dpToPx(8));
        String[][] sw = {{"DPAD", "D-pad"}, {"ESC", "ESC"}, {"Q", "Prev"}, {"E", "Next"}, {"SHIFT", "Special"}, {"SPACE", "SPACE"}, {"KEYS", "KEYS"}, {"HIDE", "HIDE / SHOW"}, {"FIRE", "FIRE circle"}};
        for (int i = 0; i < sw.length; i += 2) {
            android.widget.LinearLayout line = new android.widget.LinearLayout(this);
            line.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            for (int j = i; j < i + 2 && j < sw.length; j++) {
                android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.rightMargin = dpToPx(j == i ? 14 : 0);
                line.addView(customToggleRow(sw[j][0], sw[j][1]), lp);
            }
            root.addView(line);
        }
        root.addView(customOptionRow("Analog aim", new String[][]{
            {"off", "Off"}, {"fixed", "Fixed"}, {"floating", "Floating"}}, "c_aim", "off"));
        root.addView(customOptionRow("Analog move", new String[][]{
            {"off", "Off"}, {"fixed", "Fixed"}, {"floating", "Floating"}}, "c_move", "off"));
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(root);
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
            .setTitle("Custom controls")
            .setView(scroll)
            .setPositiveButton("OK", null)
            .create();
        dlg.show();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.6f),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private Button aimBtn, aimZone, moveZone, casualFloatBtn, moveFloatBtn;
    private boolean casualFloating = false, moveFloating = false;
    private float moveOx, moveOy;

    private void styleZone(Button z) {
        if (z == null) return;
        if (editMode) {
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(dpToPx(12));
            d.setColor(Color.argb(40, 255, 255, 255));
            d.setStroke(dpToPx(2), Color.argb(170, 255, 255, 255), dpToPx(8), dpToPx(5));
            z.setBackground(d);
            z.setAllCaps(false);
            z.setTextSize(12);
            z.setTextColor(Color.argb(210, 255, 255, 255));
            z.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            z.setText(z == aimZone ? "AIM zone" : "MOVE zone");
        } else {
            z.setBackground(null);
            z.setText("");
        }
    }

    private android.widget.TextView zoneHandleA, zoneHandleM;

    private EditableControl findEditable(String id) {
        for (EditableControl c : editControls) if (c.id.equals(id)) return c;
        return null;
    }

    private void applyZoneStyle() {
        styleZone(aimZone);
        styleZone(moveZone);
        syncZoneHandles();
    }

    private void addZoneHandles(FrameLayout parent) {
        zoneHandleA = makeZoneHandle(parent, "AIMZ");
        zoneHandleM = makeZoneHandle(parent, "MOVEZ");
        syncZoneHandles();
    }

    private void syncZoneHandles() {
        syncZoneHandle(zoneHandleA, "AIMZ");
        syncZoneHandle(zoneHandleM, "MOVEZ");
    }

    private void syncZoneHandle(android.widget.TextView h, String zid) {
        if (h == null) return;
        EditableControl c = findEditable(zid);
        boolean show = editMode && c != null && !controlHiddenInScheme(zid) && c.btn.getVisibility() == View.VISIBLE;
        h.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        int sz = dpToPx(40);
        boolean tl = c.lp.gravity == (Gravity.TOP | Gravity.LEFT);
        int right = tl ? c.lp.leftMargin + c.lp.width : c.btn.getRight();
        int bottom = tl ? c.lp.topMargin + c.lp.height : c.btn.getBottom();
        FrameLayout.LayoutParams hl = (FrameLayout.LayoutParams) h.getLayoutParams();
        hl.leftMargin = right - sz;
        hl.topMargin = bottom - sz;
        h.setLayoutParams(hl);
    }

    private boolean zoneHandleAllowed(String zid) {
        EditableControl c = findEditable(zid);
        return editMode && c != null && !controlHiddenInScheme(zid) && c.btn.getVisibility() == View.VISIBLE;
    }

    private android.widget.TextView makeZoneHandle(final FrameLayout parent, final String zid) {
        final android.widget.TextView h = new android.widget.TextView(this) {
            @Override public void setVisibility(int v) {
                super.setVisibility((v == View.VISIBLE && !zoneHandleAllowed(zid)) ? View.GONE : v);
            }
        };
        h.setText("\u2198");
        h.setGravity(Gravity.CENTER);
        h.setTextSize(20);
        h.setTextColor(Color.WHITE);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dpToPx(8));
        d.setColor(Color.argb(200, 60, 160, 60));
        d.setStroke(dpToPx(2), Color.WHITE);
        h.setBackground(d);
        int sz = dpToPx(40);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(sz, sz);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        h.setLayoutParams(lp);
        h.setVisibility(View.GONE);
        parent.addView(h);
        final float[] st = new float[4];
        h.setOnTouchListener((v, e) -> {
            EditableControl c = findEditable(zid);
            if (c == null) return true;
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                c.lp.gravity = Gravity.TOP | Gravity.LEFT;
                c.lp.leftMargin = c.btn.getLeft();
                c.lp.topMargin = c.btn.getTop();
                c.lp.rightMargin = 0;
                c.lp.bottomMargin = 0;
                c.lp.width = c.btn.getWidth();
                c.lp.height = c.btn.getHeight();
                c.btn.setLayoutParams(c.lp);
                st[0] = e.getRawX(); st[1] = e.getRawY(); st[2] = c.lp.width; st[3] = c.lp.height;
            } else if (a == MotionEvent.ACTION_MOVE) {
                int minS = dpToPx(80);
                int maxW = Math.max(minS, parent.getWidth() - c.lp.leftMargin);
                int maxH = Math.max(minS, parent.getHeight() - c.lp.topMargin);
                c.lp.width = clampInt((int) (st[2] + e.getRawX() - st[0]), minS, maxW);
                c.lp.height = clampInt((int) (st[3] + e.getRawY() - st[1]), minS, maxH);
                c.btn.setLayoutParams(c.lp);
                syncHighlight(c.highlight, c.lp);
                syncZoneHandles();
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                saveLayoutPosition(zid, c.lp.leftMargin, c.lp.topMargin);
                saveLayoutSize(zid, c.lp.width, c.lp.height);
            }
            return true;
        });
        return h;
    }

    private void addFloatZone(FrameLayout parent, final float[] lastPos, final String id, final boolean aim) {
        final Button btn = new Button(this) {
            @Override public void setVisibility(int v) {
                super.setVisibility(controlHiddenInScheme(id) ? View.GONE : v);
            }
            @Override public void setAlpha(float a) {
                super.setAlpha(a);
                if (casualHud != null) casualHud.invalidate();
            }
        };
        btn.setText("");
        btn.setClickable(false);
        btn.setFocusable(false);
        btn.addOnLayoutChangeListener((vw, l, t, r, b, ol, ot, orr, ob) -> syncZoneHandles());
        float baseAlpha = 0.55f;
        btn.setAlpha(loadLayoutAlpha(id, baseAlpha));
        btn.setBackground(null);
        int w = dpToPx(aim ? 300 : 260), h = dpToPx(200);
        int gravity = aim ? (Gravity.BOTTOM | Gravity.RIGHT) : (Gravity.BOTTOM | Gravity.LEFT);
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.gravity = gravity;
        if (aim) lp.rightMargin = dpToPx(10); else lp.leftMargin = dpToPx(10);
        lp.bottomMargin = dpToPx(10);
        int baseLeft = lp.leftMargin, baseTop = lp.topMargin, baseRight = lp.rightMargin, baseBottom = lp.bottomMargin;
        applyLayoutOverride(id, lp, w, h);
        btn.setLayoutParams(lp);
        parent.addView(btn);
        registerEditable(id, btn, lp, false, gravity, baseLeft, baseTop, baseRight, baseBottom, w, h, baseAlpha);
        btn.setVisibility(View.VISIBLE);
        if (aim) aimZone = btn; else moveZone = btn;
        styleZone(btn);
        btn.setOnTouchListener((v, event) -> aim ? handleFloatAim(btn, event, lastPos) : handleFloatMove(btn, event));
    }

    private boolean handleFloatAim(Button z, MotionEvent e, float[] lastPos) {
        int a = e.getActionMasked(), idx = e.getActionIndex();
        if (a == MotionEvent.ACTION_DOWN) {
            if (editMode) return true;
            try { if (nativeIsMenuOpen()) return false; } catch (Throwable t) {}
            casualLastPos = lastPos;
            casualPointerId = e.getPointerId(0);
            casualFloating = true;
            casualFloatBtn = z;
            casualOx = casualKx = z.getLeft() + e.getX(0);
            casualOy = casualKy = z.getTop() + e.getY(0);
            casualMag = 0f;
            float[] pp = casualPlayerPos();
            float ddx = lastPos[0] - pp[0], ddy = lastPos[1] - pp[1];
            if (Math.hypot(ddx, ddy) > dpToPx(8)) casualAng = (float) Math.atan2(ddy, ddx);
            startCasualFrames();
            applyCasualAim();
        } else if (a == MotionEvent.ACTION_MOVE) {
            int i = casualPointerId == -1 ? -1 : e.findPointerIndex(casualPointerId);
            if (i != -1 && casualFloating) {
                float dx = z.getLeft() + e.getX(i) - casualOx, dy = z.getTop() + e.getY(i) - casualOy;
                float d = (float) Math.hypot(dx, dy), r = dpToPx(CASUAL_RADIUS_DP), m = Math.min(d, r);
                casualMag = m / r;
                if (d > 0f) { casualKx = casualOx + dx / d * m; casualKy = casualOy + dy / d * m; }
                if (casualMag > CASUAL_DEADZONE) casualAng = (float) Math.atan2(dy, dx);
                applyCasualAim();
            }
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            stopCasual();
        } else if (a == MotionEvent.ACTION_POINTER_UP && e.getPointerId(idx) == casualPointerId) {
            stopCasual();
        }
        return true;
    }

    private boolean handleFloatMove(Button z, MotionEvent e) {
        int a = e.getActionMasked(), idx = e.getActionIndex();
        if (a == MotionEvent.ACTION_DOWN) {
            if (editMode) return true;
            try { if (nativeIsMenuOpen()) return false; } catch (Throwable t) {}
            movePointerId = e.getPointerId(0);
            moveFloating = true;
            moveFloatBtn = z;
            moveOx = moveKx = z.getLeft() + e.getX(0);
            moveOy = moveKy = z.getTop() + e.getY(0);
            updateFloatMove(z, e, 0);
        } else if (a == MotionEvent.ACTION_MOVE) {
            int i = movePointerId == -1 ? -1 : e.findPointerIndex(movePointerId);
            if (i != -1 && moveFloating) updateFloatMove(z, e, i);
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            releaseMove();
        } else if (a == MotionEvent.ACTION_POINTER_UP && e.getPointerId(idx) == movePointerId) {
            releaseMove();
        }
        return true;
    }

    private void updateFloatMove(Button z, MotionEvent e, int i) {
        float r = dpToPx(CASUAL_RADIUS_DP);
        float dx = z.getLeft() + e.getX(i) - moveOx, dy = z.getTop() + e.getY(i) - moveOy;
        float d = (float) Math.hypot(dx, dy), m = Math.min(d, r);
        moveKx = moveOx + (d > 0f ? dx / d * m : 0f);
        moveKy = moveOy + (d > 0f ? dy / d * m : 0f);
        mkL = moveKey(KeyEvent.KEYCODE_DPAD_LEFT, mkL, dx < -r * (mkL ? 0.20f : 0.35f));
        mkR = moveKey(KeyEvent.KEYCODE_DPAD_RIGHT, mkR, dx > r * (mkR ? 0.20f : 0.35f));
        mkU = moveKey(KeyEvent.KEYCODE_DPAD_UP, mkU, dy < -r * (mkU ? 0.35f : 0.50f));
        mkD = moveKey(KeyEvent.KEYCODE_DPAD_DOWN, mkD, dy > r * (mkD ? 0.35f : 0.50f));
        if (casualHud != null) casualHud.invalidate();
    }

    private void drawFloatAim(android.graphics.Canvas cv, android.graphics.Paint p) {
        float r = dpToPx(CASUAL_RADIUS_DP);
        float a = casualFloatBtn != null ? casualFloatBtn.getAlpha() : 0.6f;
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(dpToPx(2));
        p.setColor(Color.argb(padA(0.85f, a), 255, 255, 255));
        cv.drawCircle(casualOx, casualOy, r, p);
        p.setColor(Color.argb(padA(0.95f, a), 255, 90, 80));
        cv.drawCircle(casualOx, casualOy, r * CASUAL_FIRE_ZONE, p);
        p.setStyle(android.graphics.Paint.Style.FILL);
        p.setColor(casualFiring ? Color.argb(padA(1f, a), 255, 90, 80) : Color.argb(padA(0.9f, a), 255, 255, 255));
        cv.drawCircle(casualKx, casualKy, dpToPx(18), p);
        drawCrosshairIcon(cv, p, casualKx, casualKy, casualFiring, a);
    }

    private void drawFloatMove(android.graphics.Canvas cv, android.graphics.Paint p) {
        float r = dpToPx(CASUAL_RADIUS_DP);
        float a = moveFloatBtn != null ? moveFloatBtn.getAlpha() : 0.6f;
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(dpToPx(2));
        p.setColor(Color.argb(padA(0.85f, a), 255, 255, 255));
        cv.drawCircle(moveOx, moveOy, r, p);
        p.setColor(Color.argb(padA(0.4f, a), 255, 255, 255));
        cv.drawCircle(moveOx, moveOy, r * 0.35f, p);
        p.setStyle(android.graphics.Paint.Style.FILL);
        p.setColor(Color.argb(padA(1f, a), 255, 255, 255));
        cv.drawCircle(moveKx, moveKy, dpToPx(18), p);
    }

    private Button aimBtn_unused;

    private Button aimPadBtn() {
        if (SCHEME_CUSTOM.equals(currentScheme())) return "fixed".equals(cAim()) ? aimBtn : null;
        return isCasualScheme() ? fireBtn : null;
    }

    private void addAimPad(FrameLayout parent, final float[] lastPos) {
        final String id = "AIM";
        int size = dpToPx(110);
        final Button btn = new Button(this) {
            @Override public void setVisibility(int v) {
                super.setVisibility(controlHiddenInScheme(id) ? View.GONE : v);
            }
            @Override public void setAlpha(float a) {
                super.setAlpha(a);
                if (casualHud != null) casualHud.invalidate();
            }
        };
        btn.setText("");
        float baseAlpha = 0.55f;
        btn.setAlpha(loadLayoutAlpha(id, baseAlpha));
        btn.setBackground(null);
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        lp.rightMargin = dpToPx(230);
        lp.bottomMargin = dpToPx(40);
        int baseLeft = lp.leftMargin, baseTop = lp.topMargin, baseRight = lp.rightMargin, baseBottom = lp.bottomMargin;
        applyLayoutOverride(id, lp, size, size);
        btn.setLayoutParams(lp);
        parent.addView(btn);
        registerEditable(id, btn, lp, true, Gravity.BOTTOM | Gravity.RIGHT, baseLeft, baseTop, baseRight, baseBottom, size, size, baseAlpha);
        btn.setVisibility(View.VISIBLE);
        aimBtn = btn;
        btn.addOnLayoutChangeListener((vw, l, t, r, b, ol, ot, orr, ob) -> { if (casualHud != null) casualHud.invalidate(); });
        btn.setOnTouchListener((v, event) -> handleCasualPad(btn, event, lastPos));
    }

    private boolean controlHiddenInScheme(String id) {
        boolean dpad = "<".equals(id) || ">".equals(id) || "^".equals(id) || "v".equals(id);
        String sc = currentScheme();
        if (SCHEME_CUSTOM.equals(sc)) {
            if (dpad) return !cOn("DPAD");
            if ("MOVE".equals(id)) return !"fixed".equals(cMove());
            if ("FIRE".equals(id)) return !cOn("FIRE");
            if ("AIM".equals(id)) return !"fixed".equals(cAim());
            if ("AIMZ".equals(id)) return !"floating".equals(cAim());
            if ("MOVEZ".equals(id)) return !"floating".equals(cMove());
            if ("ESC".equals(id) || "Q".equals(id) || "E".equals(id) || "SHIFT".equals(id) || "SPACE".equals(id)
                || "KEYS".equals(id) || "HIDE".equals(id)) return !cOn(id);
            return false;
        }
        if ("AIM".equals(id) || "AIMZ".equals(id) || "MOVEZ".equals(id)) return true;
        return SCHEME_CASUAL.equals(sc) ? dpad : "MOVE".equals(id);
    }

    private void addMovePad(FrameLayout parent) {
        final String id = "MOVE";
        int size = dpToPx(130);
        final Button btn = new Button(this) {
            @Override public void setVisibility(int v) {
                super.setVisibility(controlHiddenInScheme(id) ? View.GONE : v);
            }
            @Override public void setAlpha(float a) {
                super.setAlpha(a);
                if (casualHud != null) casualHud.invalidate();
            }
        };
        btn.setText("");
        float baseAlpha = 0.55f;
        btn.setAlpha(loadLayoutAlpha(id, baseAlpha));
        btn.setBackground(null);
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        lp.leftMargin = dpToPx(70);
        lp.bottomMargin = dpToPx(20);
        int baseLeft = lp.leftMargin, baseTop = lp.topMargin, baseRight = lp.rightMargin, baseBottom = lp.bottomMargin;
        applyLayoutOverride(id, lp, size, size);
        btn.setLayoutParams(lp);
        parent.addView(btn);
        registerEditable(id, btn, lp, true, Gravity.BOTTOM | Gravity.LEFT, baseLeft, baseTop, baseRight, baseBottom, size, size, baseAlpha);
        btn.setVisibility(View.VISIBLE);
        moveBtn = btn;
        btn.addOnLayoutChangeListener((vw, l, t, r, b, ol, ot, orr, ob) -> { if (casualHud != null) casualHud.invalidate(); });
        btn.setOnTouchListener((v, event) -> handleMovePad(btn, event));
    }

    private boolean moveKey(int code, boolean cur, boolean want) {
        if (want && !cur) SDLActivity.onNativeKeyDown(code);
        else if (!want && cur) SDLActivity.onNativeKeyUp(code);
        return want;
    }

    private boolean handleMovePad(Button btn, MotionEvent e) {
        if (editMode) return true;
        int a = e.getActionMasked(), idx = e.getActionIndex();
        if (a == MotionEvent.ACTION_DOWN) {
            movePointerId = e.getPointerId(0);
            updateMovePad(btn, e, 0);
        } else if (a == MotionEvent.ACTION_MOVE) {
            int i = movePointerId == -1 ? -1 : e.findPointerIndex(movePointerId);
            if (i != -1) updateMovePad(btn, e, i);
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            releaseMove();
        } else if (a == MotionEvent.ACTION_POINTER_UP && e.getPointerId(idx) == movePointerId) {
            releaseMove();
        }
        return true;
    }

    private void updateMovePad(Button btn, MotionEvent e, int i) {
        float r = Math.max(btn.getWidth() / 2f, dpToPx(36));
        float cx = btn.getWidth() / 2f, cy = btn.getHeight() / 2f;
        float dx = e.getX(i) - cx, dy = e.getY(i) - cy;
        float d = (float) Math.hypot(dx, dy), m = Math.min(d, r);
        moveKx = btn.getLeft() + cx + (d > 0f ? dx / d * m : 0f);
        moveKy = btn.getTop() + cy + (d > 0f ? dy / d * m : 0f);
        mkL = moveKey(KeyEvent.KEYCODE_DPAD_LEFT, mkL, dx < -r * (mkL ? 0.20f : 0.35f));
        mkR = moveKey(KeyEvent.KEYCODE_DPAD_RIGHT, mkR, dx > r * (mkR ? 0.20f : 0.35f));
        mkU = moveKey(KeyEvent.KEYCODE_DPAD_UP, mkU, dy < -r * (mkU ? 0.35f : 0.50f));
        mkD = moveKey(KeyEvent.KEYCODE_DPAD_DOWN, mkD, dy > r * (mkD ? 0.35f : 0.50f));
        if (casualHud != null) casualHud.invalidate();
    }

    private void releaseMove() {
        movePointerId = -1;
        moveFloating = false;
        mkL = moveKey(KeyEvent.KEYCODE_DPAD_LEFT, mkL, false);
        mkR = moveKey(KeyEvent.KEYCODE_DPAD_RIGHT, mkR, false);
        mkU = moveKey(KeyEvent.KEYCODE_DPAD_UP, mkU, false);
        mkD = moveKey(KeyEvent.KEYCODE_DPAD_DOWN, mkD, false);
        if (casualHud != null) casualHud.invalidate();
    }

    private void drawMovePad(android.graphics.Canvas cv, android.graphics.Paint p) {
        if (moveFloating && movePointerId != -1) { drawFloatMove(cv, p); return; }
        if (moveBtn == null || moveBtn.getVisibility() != View.VISIBLE) return;
        float r = Math.max(moveBtn.getWidth() / 2f, dpToPx(36));
        float cx = moveBtn.getLeft() + moveBtn.getWidth() / 2f, cy = moveBtn.getTop() + moveBtn.getHeight() / 2f;
        boolean on = movePointerId != -1;
        float kx = on ? moveKx : cx, ky = on ? moveKy : cy;
        float a = moveBtn.getAlpha();
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(dpToPx(2));
        p.setColor(Color.argb(padA(0.85f, a), 255, 255, 255));
        cv.drawCircle(cx, cy, r, p);
        p.setColor(Color.argb(padA(0.4f, a), 255, 255, 255));
        cv.drawCircle(cx, cy, r * 0.35f, p);
        p.setStyle(android.graphics.Paint.Style.FILL);
        p.setColor(Color.argb(padA(on ? 1f : 0.9f, a), 255, 255, 255));
        cv.drawCircle(kx, ky, dpToPx(18), p);
    }

    private void addSchemeSelector(FrameLayout parent) {
        schemeBar = new android.widget.LinearLayout(this) {
            @Override public void setVisibility(int v) {
                int eff = (schemeMin && v == View.VISIBLE) ? View.GONE : v;
                super.setVisibility(eff);
                if (schemeInfo != null) schemeInfo.setVisibility(eff);
            }
        };
        schemeBar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        schemeMinBtn = new Button(this);
        schemeMinBtn.setText("\u2013");
        schemeMinBtn.setAllCaps(false);
        schemeMinBtn.setTextSize(20);
        schemeMinBtn.setPadding(0, 0, 0, 0);
        schemeMinBtn.setMinWidth(0);
        schemeMinBtn.setMinimumWidth(0);
        styleSchemeBtn(schemeMinBtn, false);
        schemeMinBtn.setOnClickListener(v -> setSchemePanelMin(true));
        android.widget.LinearLayout.LayoutParams mlp = new android.widget.LinearLayout.LayoutParams(
            dpToPx(44), ViewGroup.LayoutParams.MATCH_PARENT);
        mlp.rightMargin = dpToPx(6);
        schemeBar.addView(schemeMinBtn, mlp);
        schemeClassicBtn = makeSchemeBtn("Preset 1", SCHEME_CLASSIC);
        schemeCasualBtn = makeSchemeBtn("Preset 2", SCHEME_CASUAL);
        schemeDpadBtn = makeSchemeBtn("Preset 3", SCHEME_DPAD);
        schemeBar.addView(schemeClassicBtn);
        schemeBar.addView(schemeCasualBtn);
        schemeBar.addView(schemeDpadBtn);
        schemeCustomBtn = makeSchemeBtn("Custom", SCHEME_CUSTOM);
        schemeBar.addView(schemeCustomBtn);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dpToPx(80);
        schemeBar.setLayoutParams(lp);
        schemeBar.setVisibility(View.GONE);
        parent.addView(schemeBar);
        schemeInfo = new android.widget.TextView(this);
        schemeInfo.setTextColor(Color.WHITE);
        schemeInfo.setTextSize(12);
        schemeInfo.setMaxWidth(dpToPx(380));
        schemeInfo.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        android.graphics.drawable.GradientDrawable ibg = new android.graphics.drawable.GradientDrawable();
        ibg.setCornerRadius(dpToPx(10));
        ibg.setColor(Color.argb(190, 20, 20, 20));
        schemeInfo.setBackground(ibg);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        ilp.topMargin = dpToPx(134);
        schemeInfo.setLayoutParams(ilp);
        schemeInfo.setClickable(false);
        schemeInfo.setVisibility(View.GONE);
        parent.addView(schemeInfo);
        refreshSchemeButtons();
    }

    private Button makeSchemeBtn(String text, final String scheme) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setPadding(dpToPx(18), dpToPx(8), dpToPx(18), dpToPx(8));
        b.setMinWidth(dpToPx(88));
        b.setMinHeight(dpToPx(44));
        b.setOnClickListener(v -> {
            if (SCHEME_CUSTOM.equals(scheme) && SCHEME_CUSTOM.equals(currentScheme())) showCustomDialog();
            else setControlScheme(scheme);
        });
        return b;
    }

    private void styleSchemeBtn(Button b, boolean on) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setCornerRadius(dpToPx(10));
        d.setColor(on ? Color.argb(235, 60, 200, 60) : Color.argb(170, 25, 25, 25));
        d.setStroke(dpToPx(2), on ? Color.WHITE : Color.argb(140, 255, 255, 255));
        b.setBackground(d);
        b.setTextColor(Color.WHITE);
    }

    private void refreshSchemeButtons() {
        String cur = currentScheme();
        if (schemeClassicBtn != null) styleSchemeBtn(schemeClassicBtn, SCHEME_CLASSIC.equals(cur));
        if (schemeCasualBtn != null) styleSchemeBtn(schemeCasualBtn, SCHEME_CASUAL.equals(cur));
        if (schemeDpadBtn != null) styleSchemeBtn(schemeDpadBtn, SCHEME_DPAD.equals(cur));
        if (schemeCustomBtn != null) styleSchemeBtn(schemeCustomBtn, SCHEME_CUSTOM.equals(cur));
        if (schemeInfo != null) schemeInfo.setText(presetInfo(cur));
    }

    private void setControlScheme(String scheme) {
        getSharedPreferences("control_scheme", MODE_PRIVATE).edit().putString("scheme", scheme).apply();
        stopCasual();
        layoutPrefs = getSharedPreferences(layoutFileFor(scheme), MODE_PRIVATE);
        releaseMove();
        reapplyLayouts();
        applyPadStyle();
        refreshSchemeButtons();
    }

    private native boolean nativeIsInGame();
    private native boolean nativeIsMenuOpen();
    private native boolean nativeIsSaveConsoleOpen();
    private native boolean nativeIsIntroPlaying();
    private boolean prevMenuOpen = false;
    private boolean prevInGame = false;
    private boolean everInGame = false;
    private void startTouchControlPolling() {
        final Handler handler = new Handler();
        final int[] notInGameStreak = {0};
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean inGame = nativeIsInGame();
                    boolean menuOpen = nativeIsMenuOpen();
                    boolean saveOpen = false;
                    try { saveOpen = nativeIsSaveConsoleOpen(); } catch (Throwable t2) {}
                    if (inGame) everInGame = true;
                    if (touchOverlay != null) {
                        if (inGame) {
                            notInGameStreak[0] = 0;
                            touchOverlay.setVisibility(saveOpen ? android.view.View.GONE : android.view.View.VISIBLE);
                        } else {
                            notInGameStreak[0]++;
                            // Require a few consecutive "not in game" polls (~300ms) before
                            // actually hiding the controls. A single transient false reading
                            // (e.g. a brief internal engine state flicker mid-gameplay) was
                            // making every button flash off-screen for under a second; real
                            // transitions to title/pause/save screens persist far longer than
                            // that, so they're unaffected.
                            if (notInGameStreak[0] >= 3) {
                                touchOverlay.setVisibility(android.view.View.GONE);
                            }
                        }
                    }
                    if ((prevMenuOpen && !menuOpen) || (!prevInGame && inGame)) {
                        suppressFireUntil = System.currentTimeMillis() + 400;
                    }
                    prevMenuOpen = menuOpen;
                    prevInGame = inGame;
                } catch (Throwable t) { }
                handler.postDelayed(this, 100);
            }
        }, 500);
    }

    private void addFireButton(FrameLayout parent, float[] lastPos, int gravity, int marginRight, int marginVert, int size) {
        final String id = "FIRE";
        Button btn = new Button(this) {
            @Override public void setAlpha(float a) {
                super.setAlpha(a);
                if (casualHud != null) casualHud.invalidate();
            }
            @Override public void setVisibility(int v) {
                super.setVisibility(controlHiddenInScheme("FIRE") ? View.GONE : v);
            }
        };
        fireBtn = btn;
        btn.setText("");
        float baseAlpha = 0.55f;
        btn.setAlpha(loadLayoutAlpha(id, baseAlpha));
        btn.setBackground(getDrawable(R.drawable.ic_fire_scope));
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = gravity;
        lp.rightMargin = marginRight;
        lp.bottomMargin = marginVert;
        applyLayoutOverride(id, lp, size, size);
        btn.setLayoutParams(lp);
        parent.addView(btn);
        registerEditable(id, btn, lp, true, gravity, 0, 0, marginRight, marginVert, size, size, baseAlpha);
        btn.setVisibility(View.VISIBLE);
        fireBgClassic = btn.getBackground();
        btn.addOnLayoutChangeListener((vw, l, t, r, b, ol, ot, orr, ob) -> { if (casualHud != null) casualHud.invalidate(); });
        applyPadStyle();
        final float[] lastFireTouch = {0f, 0f};
        btn.setOnTouchListener((v, event) -> {
            if (isCasualScheme()) return handleCasualPad(btn, event, lastPos);
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                lastFireTouch[0] = event.getX();
                lastFireTouch[1] = event.getY();
                if (System.currentTimeMillis() >= suppressFireUntil) {
                    SDLActivity.onNativeMouse(1, 0, lastPos[0], lastPos[1], false);
                }
            } else if (action == MotionEvent.ACTION_MOVE) {
                float dx = event.getX() - lastFireTouch[0];
                float dy = event.getY() - lastFireTouch[1];
                lastFireTouch[0] = event.getX();
                lastFireTouch[1] = event.getY();
                lastPos[0] += dx;
                lastPos[1] += dy;
                SDLActivity.onNativeMouse(1, 2, dx, dy, true);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
                SDLActivity.onNativeMouse(0, 1, lastPos[0], lastPos[1], false);
            }
            return true;
        });
    }

    private void addToggleButton(FrameLayout parent) {
        final Button toggleBtn = new Button(this) {
            @Override public void setVisibility(int v) {
                super.setVisibility(controlHiddenInScheme("HIDE") ? View.GONE : v);
            }
        };
        toggleBtn.setText("");
        toggleBtn.setAlpha(loadLayoutAlpha("HIDE", 0.75f));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dpToPx(47), dpToPx(27));
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dpToPx(3);
        final int hideBaseTop = lp.topMargin;
        applyLayoutOverride("HIDE", lp, dpToPx(47), dpToPx(27));
        toggleBtn.setLayoutParams(lp);
        parent.addView(toggleBtn);
        registerEditable("HIDE", toggleBtn, lp, false, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, hideBaseTop, 0, 0, dpToPx(47), dpToPx(27), 0.75f);
        toggleBtn.setVisibility(View.VISIBLE);

        // Persisted across app restarts: if the player hid the controls (e.g. playing with a
        // keyboard/gamepad), resuming a saved game later keeps them hidden instead of resetting
        // to shown every launch.
        final boolean[] hidden = {getSharedPreferences("touch_layout", MODE_PRIVATE).getBoolean("controls_hidden", false)};
        Runnable applyHiddenState = () -> {
            if (controlHiddenInScheme("HIDE")) hidden[0] = false;
            int childCount = parent.getChildCount();
            for (int i = 0; i < childCount; i++) {
                android.view.View child = parent.getChildAt(i);
                boolean isHighlight = false;
                for (EditableControl c : editControls) {
                    if (child == c.highlight) { isHighlight = true; break; }
                }
                if (child != toggleBtn && child != editOverlay && !isHighlight) {
                    child.setVisibility(hidden[0] ? android.view.View.GONE : android.view.View.VISIBLE);
                }
            }
            if (resetBtn != null) {
                resetBtn.setVisibility((!hidden[0] && editMode) ? View.VISIBLE : View.GONE);
                if (schemeBar != null) schemeBar.setVisibility((!hidden[0] && editMode) ? View.VISIBLE : View.GONE);
            }
            toggleBtn.setBackground(makeIconBg(false, Color.argb(210, 255, 255, 255),
                hidden[0] ? R.drawable.ic_eye_closed : R.drawable.ic_eye_open, 24));
        };
        applyHiddenState.run(); // apply the persisted state immediately at startup

        toggleBtn.setOnClickListener(v -> {
            hidden[0] = !hidden[0];
            getSharedPreferences("touch_layout", MODE_PRIVATE).edit().putBoolean("controls_hidden", hidden[0]).apply();
            applyHiddenState.run();
        });
    }

    /** Full-screen invisible catcher, always present (independent of touchOverlay's own visibility),
     *  that only steps in when a menu is open AND touchOverlay is currently hidden (e.g. a full-screen
     *  in-game menu like the ESC/options screen, where nativeIsInGame() reports false so our normal
     *  aimView never gets a chance to run). Uses the same Choreographer-based force-release safety net
     *  as the save-menu fix. When its condition isn't met it returns false immediately, passing the
     *  touch through untouched to whatever's underneath. */
    private void addMenuClickGuard() {
        View guard = new View(this);
        final boolean[] active = {false};
        final float[] touchPos = {0f, 0f};
        final Choreographer.FrameCallback[] cb = new Choreographer.FrameCallback[1];
        guard.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            int idx = event.getActionIndex();
            if (action == MotionEvent.ACTION_DOWN) {
                boolean inGameNow = true;
                try { inGameNow = nativeIsInGame(); } catch (Throwable t) {}
                // Intercept whenever we're NOT in active gameplay (title screen, pause/ESC menu,
                // options, etc.) — covers menus that nativeIsMenuOpen() doesn't flag (the save
                // console already has its own working fix via aimView's gestureIsMenu path).
                active[0] = !inGameNow;
                if (!active[0]) return false;
                touchPos[0] = event.getX(idx);
                touchPos[1] = event.getY(idx);
                SDLActivity.onNativeMouse(1, 0, touchPos[0], touchPos[1], false);
                cb[0] = new Choreographer.FrameCallback() {
                    @Override public void doFrame(long frameTimeNanos) {
                        if (!active[0]) return;
                        boolean nowInGame = false;
                        try { nowInGame = nativeIsInGame(); } catch (Throwable t) {}
                        if (nowInGame) {
                            SDLActivity.onNativeMouse(0, 1, touchPos[0], touchPos[1], false);
                            active[0] = false;
                            suppressFireUntil = System.currentTimeMillis() + 400;
                        } else {
                            Choreographer.getInstance().postFrameCallback(this);
                        }
                    }
                };
                Choreographer.getInstance().postFrameCallback(cb[0]);
                return true;
            }
            if (!active[0]) return false;
            if (action == MotionEvent.ACTION_MOVE) {
                touchPos[0] = event.getX(idx);
                touchPos[1] = event.getY(idx);
                SDLActivity.onNativeMouse(0, 2, touchPos[0], touchPos[1], false);
                return true;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (cb[0] != null) Choreographer.getInstance().removeFrameCallback(cb[0]);
                if (active[0]) {
                    SDLActivity.onNativeMouse(0, 1, event.getX(idx), event.getY(idx), false);
                    active[0] = false;
                }
                return true;
            }
            return true;
        });
        addContentView(guard, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Consumes touch only while !nativeIsInGame() (title screen, pause menu, save console,
     *  etc.), forwarding press/move/release manually so we fully control the mouse-button state
     *  ourselves. A Choreographer callback watches every frame for nativeIsInGame() flipping
     *  true (gameplay just started/resumed) and force-releases the button right then, even if
     *  the same finger is still physically down — stopping a held "Start Game"/"Return to Game"
     *  tap from being read as an already-pressed fire button. Sends a cursor-move event before
     *  the press so the widget system's hit-testing uses the correct tap position (fixes
     *  submenu close/X buttons that broke when this only sent the press with no prior move). */
    private void addStrayShotGuard() {
        View guard = new View(this);
        final boolean[] active = {false};
        final float[] touchPos = {0f, 0f};
        final Choreographer.FrameCallback[] cb = new Choreographer.FrameCallback[1];
        guard.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            int idx = event.getActionIndex();
            if (action == MotionEvent.ACTION_DOWN) {
                boolean inGameNow = true;
                try { inGameNow = nativeIsInGame(); } catch (Throwable t) {}
                active[0] = !inGameNow;
                if (!active[0]) return false;
                touchPos[0] = event.getX(idx);
                touchPos[1] = event.getY(idx);
                boolean introPlaying = false;
                try { introPlaying = nativeIsIntroPlaying(); } catch (Throwable t) {}
                if (introPlaying) {
                    SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_ENTER);
                    SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_ENTER);
                }
                SDLActivity.onNativeMouse(0, 2, touchPos[0], touchPos[1], false);
                SDLActivity.onNativeMouse(1, 0, touchPos[0], touchPos[1], false);
                cb[0] = new Choreographer.FrameCallback() {
                    @Override public void doFrame(long frameTimeNanos) {
                        if (!active[0]) return;
                        boolean nowInGame = false;
                        try { nowInGame = nativeIsInGame(); } catch (Throwable t) {}
                        if (nowInGame) {
                            SDLActivity.onNativeMouse(0, 1, touchPos[0], touchPos[1], false);
                            active[0] = false;
                            suppressFireUntil = System.currentTimeMillis() + 400;
                        } else {
                            Choreographer.getInstance().postFrameCallback(this);
                        }
                    }
                };
                Choreographer.getInstance().postFrameCallback(cb[0]);
                return true;
            }
            if (!active[0]) return false;
            if (action == MotionEvent.ACTION_MOVE) {
                touchPos[0] = event.getX(idx);
                touchPos[1] = event.getY(idx);
                SDLActivity.onNativeMouse(0, 2, touchPos[0], touchPos[1], false);
                return true;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (cb[0] != null) Choreographer.getInstance().removeFrameCallback(cb[0]);
                if (active[0]) {
                    SDLActivity.onNativeMouse(0, 1, event.getX(idx), event.getY(idx), false);
                    active[0] = false;
                }
                return true;
            }
            return true;
        });
        addContentView(guard, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }
}
