package org.libsdl.app;

import android.os.Bundle;
import android.os.Handler;
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
        layoutPrefs = getSharedPreferences("touch_layout", MODE_PRIVATE);
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
        View hl = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(circular ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE);
        d.setColor(Color.TRANSPARENT);
        d.setStroke(6, Color.YELLOW);
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
        c.highlight = createHighlightView(touchOverlay, lp, circular);
        editControls.add(c);
        return c;
    }

    private void resetAllLayouts() {
        layoutPrefs.edit().clear().apply();
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
            int pad = 40; // generous hit padding so small buttons are easy to grab
            for (EditableControl c : editControls) {
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
                if (isInside(editModeBtn, x, y) || isInside(resetBtn, x, y)) {
                    return false;
                }
                active = findControlAt(x, y);
                if (active == null) return true;
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
                        int newW = clampInt((int) (pinchStartW * scale), 60, 600);
                        int newH = clampInt((int) (pinchStartH * scale), 40, 500);
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
                    final int touchSlop = 20;
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
        }
        for (EditableControl c : editControls) {
            c.highlight.setVisibility(on ? View.VISIBLE : View.GONE);
        }
    }

    private void addEditModeButton(final FrameLayout parent) {
        editModeBtn = new Button(this);
        editModeBtn.setText("EDIT");
        editModeBtn.setTextSize(9);
        editModeBtn.setAlpha(0.6f);
        editModeBtn.setBackgroundColor(Color.DKGRAY);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(140, 80);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.leftMargin = 210; // beside ESC (ESC: margin 20 + size 170 + gap 20)
        lp.topMargin = 20;
        editModeBtn.setLayoutParams(lp);
        editModeBtn.setOnClickListener(v -> {
            setEditMode(!editMode);
            editModeBtn.setText(editMode ? "DONE" : "EDIT");
            editModeBtn.setBackgroundColor(editMode ? Color.rgb(0, 130, 0) : Color.DKGRAY);
        });
        parent.addView(editModeBtn);
    }

    private void addResetButton(final FrameLayout parent) {
        resetBtn = new Button(this);
        resetBtn.setText("RESET");
        resetBtn.setTextSize(9);
        resetBtn.setAlpha(0.6f);
        resetBtn.setBackgroundColor(Color.rgb(130, 30, 30));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(140, 80);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.leftMargin = 370; // beside EDIT (EDIT: left 210 + width 140 + gap 20)
        lp.topMargin = 20;
        resetBtn.setLayoutParams(lp);
        resetBtn.setVisibility(View.GONE);
        resetBtn.setOnClickListener(v -> resetAllLayouts());
        parent.addView(resetBtn);
    }

    private void addKeyboardButton(final FrameLayout parent) {
        final String id = "KEYS";
        Button btn = new Button(this);
        btn.setText("KEYS");
        btn.setTextSize(9);
        float baseAlpha = 0.6f;
        btn.setAlpha(loadLayoutAlpha(id, baseAlpha));
        btn.setBackgroundColor(Color.DKGRAY);
        int gravity = Gravity.TOP | Gravity.RIGHT;
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(140, 80);
        lp.gravity = gravity;
        lp.rightMargin = 440;
        lp.topMargin = 10;
        int baseLeft = 0, baseTop = lp.topMargin, baseRight = lp.rightMargin, baseBottom = 0;
        applyLayoutOverride(id, lp, 140, 80);
        btn.setLayoutParams(lp);
        parent.addView(btn);
        registerEditable(id, btn, lp, false, gravity, baseLeft, baseTop, baseRight, baseBottom, 140, 80, baseAlpha);
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
        final float[] lastPos = {160f, 100f};
        android.view.View aimView = new android.view.View(this);
        final int[] aimPointerId = {-1};
        final boolean[] gestureIsMenu = {false};
        aimView.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            int idx = event.getActionIndex();
            if (action == MotionEvent.ACTION_DOWN) {
                boolean menuOpen = false;
                try { menuOpen = nativeIsMenuOpen(); } catch (Throwable t) {}
                gestureIsMenu[0] = menuOpen;
            }
            if (gestureIsMenu[0]) {
                if (action == MotionEvent.ACTION_DOWN) {
                    SDLActivity.onNativeMouse(1, 0, event.getX(idx), event.getY(idx), false);
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    SDLActivity.onNativeMouse(0, 1, event.getX(idx), event.getY(idx), false);
                    gestureIsMenu[0] = false;
                } else if (action == MotionEvent.ACTION_MOVE) {
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
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        int btnSize = 170;

        // Movement D-pad (bottom-left)
        addBtn(touchOverlay, "<", Gravity.BOTTOM | Gravity.LEFT, 140, 80, btnSize, KeyEvent.KEYCODE_DPAD_LEFT);
        addBtn(touchOverlay, ">", Gravity.BOTTOM | Gravity.LEFT, 500, 80, btnSize, KeyEvent.KEYCODE_DPAD_RIGHT);
        addBtn(touchOverlay, "^", Gravity.BOTTOM | Gravity.LEFT, 320, 260, btnSize, KeyEvent.KEYCODE_DPAD_UP);
        addBtn(touchOverlay, "v", Gravity.BOTTOM | Gravity.LEFT, 320, 80, btnSize, KeyEvent.KEYCODE_DPAD_DOWN);

        // ESC (top-left)
        addBtn(touchOverlay, "ESC", Gravity.TOP | Gravity.LEFT, 20, 20, btnSize, KeyEvent.KEYCODE_ESCAPE);
        addEditModeButton(touchOverlay);
        addResetButton(touchOverlay);
        addKeyboardButton(touchOverlay);

        // Weapon prev/next (right side, mid-height)
        addBtn(touchOverlay, "Q", Gravity.TOP | Gravity.RIGHT, 20, 400, btnSize, KeyEvent.KEYCODE_Q);
        addBtn(touchOverlay, "E", Gravity.TOP | Gravity.RIGHT, 20, 220, btnSize, KeyEvent.KEYCODE_E);

        // Run (bottom-right)
        addBtn(touchOverlay, "SHIFT", Gravity.BOTTOM | Gravity.RIGHT, 120, 60, btnSize, KeyEvent.KEYCODE_SHIFT_LEFT);
        addBtnWide(touchOverlay, "SPACE", Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 60, btnSize * 2, btnSize, KeyEvent.KEYCODE_SPACE);

        addFireButton(touchOverlay, lastPos, Gravity.BOTTOM | Gravity.RIGHT, 380, 200, btnSize + 90);
        addToggleButton(touchOverlay);

        editOverlay = new EditOverlayView(this);
        editOverlay.setVisibility(View.GONE);
        touchOverlay.addView(editOverlay, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        addContentView(touchOverlay, flp);
    }

    private void addBtn(FrameLayout parent, String label, int gravity, int marginRight, int marginVert, int size, final int keyCode) {
        final String id = label;
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextSize(10);
        float baseAlpha = 0.5f;
        btn.setAlpha(loadLayoutAlpha(id, baseAlpha));
        btn.setBackgroundColor(Color.DKGRAY);
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
        registerEditable(id, btn, lp, false, gravity, baseLeft, baseTop, baseRight, baseBottom, size, size, baseAlpha);
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
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextSize(10);
        float baseAlpha = 0.5f;
        btn.setAlpha(loadLayoutAlpha(id, baseAlpha));
        btn.setBackgroundColor(Color.DKGRAY);
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


    private native boolean nativeIsInGame();
    private native boolean nativeIsMenuOpen();
    private boolean prevMenuOpen = false;
    private boolean prevInGame = false;
    private void startTouchControlPolling() {
        final Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean inGame = nativeIsInGame();
                    boolean menuOpen = nativeIsMenuOpen();
                    if (touchOverlay != null) {
                        touchOverlay.setVisibility(inGame ? android.view.View.VISIBLE : android.view.View.GONE);
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
        Button btn = new Button(this);
        btn.setText("FIRE");
        btn.setTextSize(10);
        float baseAlpha = 0.5f;
        btn.setAlpha(loadLayoutAlpha(id, baseAlpha));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.DKGRAY);
        btn.setBackground(circle);
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = gravity;
        lp.rightMargin = marginRight;
        lp.bottomMargin = marginVert;
        applyLayoutOverride(id, lp, size, size);
        btn.setLayoutParams(lp);
        parent.addView(btn);
        registerEditable(id, btn, lp, true, gravity, 0, 0, marginRight, marginVert, size, size, baseAlpha);
        final float[] lastFireTouch = {0f, 0f};
        btn.setOnTouchListener((v, event) -> {
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
        final Button toggleBtn = new Button(this);
        toggleBtn.setText("HIDE");
        toggleBtn.setTextSize(9);
        toggleBtn.setAlpha(0.6f);
        toggleBtn.setBackgroundColor(Color.DKGRAY);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(140, 80);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = 10;
        toggleBtn.setLayoutParams(lp);
        final boolean[] hidden = {false};
        toggleBtn.setOnClickListener(v -> {
            hidden[0] = !hidden[0];
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
            }
            toggleBtn.setText(hidden[0] ? "SHOW" : "HIDE");
        });
        parent.addView(toggleBtn);
    }

    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }
}
