package com.wodi.redguotools;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 悬浮球点选：直接在页面上点哪个控件就藏哪个。
 *
 * <p>为什么需要它：{@code UiController.scanBlocks()} 是「按结构猜块」，
 * 宿主改版把类名/id 换掉之后就扫不出来；这里反过来 —— 用户点哪就藏哪，
 * 不依赖任何结构命名。所以扫描清单和它并存，一个给「想不起来叫什么」的场景，
 * 一个给「就是这个东西」的场景。
 *
 * <p>三条防事故的护栏（都是踩过的坑，别删）：
 * <ul>
 *   <li>{@code sTouching}：手指按在圆球上时暂停 {@link #KEEP_TOP} 的重新挂载/置顶，
 *       否则手势中途被 cancel，UP 会漏成一次选点；</li>
 *   <li>{@code sRootDown}：遮罩根只处理「自己收到 DOWN 的那次 UP」，
 *       来自自家 UI 的游离 UP 一律不当选点；</li>
 *   <li>{@code insidePanel}：落在悬浮球/卡片范围内的 UP 不参与选点。</li>
 * </ul>
 */
public final class PickerOverlay {

    private static final String TAG = RGModule.TAG;

    private static final String KEY_POS_X = "pick_bubble_x";
    private static final String KEY_POS_Y = "pick_bubble_y";

    /** 自家 View 的标记：选点、命中判定都靠它把自己摘出去。 */
    private static final Object OURS = new Object();

    private static Activity sAct;
    private static ViewGroup sDecor;
    private static FrameLayout sOverlay;
    private static ViewGroup sPanel;
    private static View sBox;
    private static View sBubble;
    private static View sMark;
    private static View sLayer;
    private static View sPicked;
    /** 当前选中项解析出的规则；null = 这个控件没有可靠特征（需要「上一级」）。 */
    private static String sRule;
    private static TextView sInfo;
    private static Handler sHandler;
    private static ValueAnimator sRingAnim;
    private static boolean sTouching;
    private static boolean sRootDown;
    /** 穿透模式：遮罩不吃触摸，用户能正常操作 App（球仍然可点）。 */
    private static boolean sPassthrough;
    private static TextView sPassBtn;
    private static TextView sUpBtn;
    private static TextView sDownBtn;
    /** 「编辑控件」小节：状态行 + 进入/保存按钮。 */
    private static TextView sEditInfo;
    private static TextView sEditBtn;
    /** 链式选层：从最初点中的叶子沿 parent 上溯到 decor 根，leaf 在末尾。 */
    private static List<View> sChain;
    private static int sChainIndex;
    private static Ring sRing;
    /* 主题色快照：切换「穿透」时要重画球和按钮，不能在回调里再取一次 Palette */
    private static int sInk;
    private static int sCard;
    private static int sStroke;
    private static int sPrimary;

    private static final Runnable KEEP_TOP = new Runnable() {
        @Override
        public void run() {
            keepOnTop();
            if (sHandler != null) {
                sHandler.postDelayed(this, 400L);
            }
        }
    };

    private PickerOverlay() {
    }

    public static boolean isOpen() {
        return sOverlay != null;
    }

    /** 入口：先收面板，等它 dismiss 完再把遮罩挂到宿主页面上。 */
    public static void start(final Activity a) {
        if (a == null) {
            return;
        }
        if (otherHost(a)) {
            FanqiePanel.dismiss();
        } else {
            SettingsPanel.dismissForPicker();
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    show(a);
                } catch (Throwable t) {
                    Log.e(TAG, "picker failed", t);
                }
            }
        }, 260L);
    }

    private static void show(Activity a) {
        if (sOverlay != null && sOverlay.getParent() != null) {
            return;
        }
        View decor = a.getWindow() == null ? null : a.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) {
            return;
        }
        sAct = a;
        sDecor = (ViewGroup) decor;
        Theme.Palette p;
        try {
            p = Theme.of(a);
        } catch (Throwable t) {
            p = null;
        }
        final int ink = p == null ? 0xFF1C1C1E : p.body;
        final int card = p == null ? 0xFFFFFFFF : p.card;
        final int bg = p == null ? 0xFFF2F2F4 : p.bg;
        final int sub = p == null ? 0xFF8A8A8E : p.sub;
        final int stroke = p == null ? 0x33000000 : p.stroke;
        final int danger = p == null ? 0xFFFF3B30 : p.danger;
        final int primary = p == null ? 0xFF1A73E8 : p.primary;
        sInk = ink;
        sCard = card;
        sStroke = stroke;
        sPrimary = primary;

        FrameLayout overlay = new FrameLayout(a);
        overlay.setTag(OURS);
        overlay.setBackgroundColor(0x33000000);
        overlay.setClickable(true);
        sDecor.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sOverlay = overlay;

        /* 选中框：琥珀色，跟主题色区分开，避免和宿主高亮撞色 */
        View mark = new View(a);
        mark.setTag(OURS);
        GradientDrawable markBg = new GradientDrawable();
        markBg.setColor(0x33FFB300);
        markBg.setStroke(dp(2), 0xFFFFB300);
        mark.setBackground(markBg);
        mark.setClickable(false);
        mark.setFocusable(false);
        overlay.addView(mark, new FrameLayout.LayoutParams(dp(1), dp(1)));
        mark.setVisibility(View.INVISIBLE);
        sMark = mark;

        /* 悬浮球整组：上面是二级菜单卡片，下面是圆球 */
        LinearLayout group = new LinearLayout(a);
        group.setTag(OURS);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setGravity(Gravity.END);
        group.setClipToPadding(false);

        LinearLayout box = new LinearLayout(a);
        box.setTag(OURS);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable boxBg = new GradientDrawable();
        boxBg.setColor(card);
        boxBg.setCornerRadius(dp(16));
        boxBg.setStroke(dp(1), stroke);
        box.setBackground(boxBg);
        box.setElevation(dp(8));

        TextView info = new TextView(a);
        info.setTag(OURS);
        info.setTextColor(ink);
        info.setTextSize(12.5f);
        info.setMaxLines(3);
        GradientDrawable infoBg = new GradientDrawable();
        infoBg.setColor(bg);
        infoBg.setCornerRadius(dp(10));
        info.setBackground(infoBg);
        info.setPadding(dp(10), dp(8), dp(10), dp(8));
        info.setText("点按要隐藏的控件；可点「上一级」扩大范围，点「退出」结束");
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.bottomMargin = dp(4);
        box.addView(info, ilp);
        sInfo = info;

        TextView guide = new TextView(a);
        guide.setTag(OURS);
        guide.setText("点击需要隐藏的位置");
        guide.setTextSize(11f);
        guide.setTextColor(sub);
        guide.setSingleLine(true);
        guide.setPadding(dp(4), 0, dp(4), dp(6));
        box.addView(guide, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        box.addView(label(sub, "选择操作"), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row1 = new LinearLayout(a);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        row1.addView(button(card, bg, ink, danger, "隐藏所选", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sPicked == null || sAct == null) {
                    if (sInfo != null) {
                        sInfo.setText("请先在页面上点选一个控件");
                    }
                    return;
                }
                if (sRule == null) {
                    if (sInfo != null) {
                        sInfo.setText("这个控件没有可靠特征，点「上一级」选外层容器再隐藏");
                    }
                    return;
                }
                hidePicked(sAct, sRule);
                clearSelection();
                if (sInfo != null) {
                    sInfo.setText("已隐藏，可继续点选下一个控件；退出请点「退出」");
                }
            }
        }), weight());
        sUpBtn = button(card, bg, ink, danger, "上一级", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                upLevel();
            }
        });
        row1.addView(sUpBtn, weight());
        sDownBtn = button(card, bg, ink, danger, "下一级", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downLevel();
            }
        });
        row1.addView(sDownBtn, weight());
        LinearLayout.LayoutParams r1lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r1lp.bottomMargin = dp(6);
        box.addView(row1, r1lp);

        LinearLayout row2 = new LinearLayout(a);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        row2.addView(button(card, bg, ink, danger, "列表模式", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Activity a2 = sAct;
                close();
                if (a2 != null && otherHost(a2)) {
                    ScanHide.scanDialog(a2, null);
                } else {
                    SettingsPanel.openScan(a2);
                }
            }
        }), weight());
        sPassBtn = button(card, bg, ink, danger, "穿透", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setPassthrough(!sPassthrough);
            }
        });
        row2.addView(sPassBtn, weight());
        row2.addView(button(card, bg, ink, danger, "退出", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Activity a2 = sAct;
                // 编辑中退出 = 未保存就走：草稿整体回滚
                if (a2 != null && EditGuard.isEditing(a2)) {
                    EditGuard.abandon(a2);
                }
                close();
            }
        }), weight());
        LinearLayout.LayoutParams r2lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2lp.bottomMargin = dp(6);
        box.addView(row2, r2lp);

        /* 撤销上一步：回滚最近一次未保存的改动（编辑模式的历史栈，见 EditGuard.undo） */
        TextView undoBtn = button(card, bg, ink, danger, "撤销上一步", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sAct != null) {
                    EditGuard.undo(sAct);
                }
                renderEditSection();
            }
        });
        undoBtn.setTextColor(0xFFE65100);   // 橙色：纠正动作，和「穿透」「列表模式」区分开
        box.addView(undoBtn, full());

        box.addView(label(sub, "编辑控件"), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* 编辑区状态行：未编辑是说明文案，编辑中换草稿计数 */
        TextView editInfo = new TextView(a);
        editInfo.setTag(OURS);
        editInfo.setTextSize(11f);
        editInfo.setTextColor(sub);
        editInfo.setSingleLine(true);
        editInfo.setPadding(dp(4), 0, dp(4), dp(6));
        box.addView(editInfo, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sEditInfo = editInfo;

        /* 通栏主按钮：进入编辑模式 / 保存并退出编辑（renderEditSection 换文案和动作） */
        sEditBtn = button(card, bg, ink, danger, "进入编辑模式", false, null);
        GradientDrawable primBg = new GradientDrawable();
        primBg.setColor(primary);
        primBg.setCornerRadius(dp(12));
        sEditBtn.setBackground(primBg);
        sEditBtn.setTextColor(0xFFFFFFFF);
        box.addView(sEditBtn, full());

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(236),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.bottomMargin = dp(8);
        group.addView(box, blp);
        sBox = box;
        box.setVisibility(View.GONE);

        /* 圆球：46dp 白底圆 + 3dp 蓝色旋转弧（空闲 4.2s/圈，展开 1.8s/圈） */
        TextView bubble = new TextView(a);
        bubble.setTag(OURS);
        bubble.setText("选择");
        bubble.setTextColor(ink);
        bubble.setTextSize(12f);
        bubble.setGravity(Gravity.CENTER);
        GradientDrawable bubbleBg = new GradientDrawable();
        bubbleBg.setShape(GradientDrawable.OVAL);
        bubbleBg.setColor((card & 0x00FFFFFF) | 0xF2000000);
        bubbleBg.setStroke(dp(1), stroke);
        bubble.setBackground(bubbleBg);
        bubble.setElevation(dp(10));

        FrameLayout container = new FrameLayout(a);
        container.setTag(OURS);
        Ring ring = new Ring(a, primary);
        ring.setTag(OURS);
        sRing = ring;
        container.addView(ring, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dp(46), dp(46));
        flp.gravity = Gravity.CENTER;
        container.addView(bubble, flp);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(58), dp(58));
        clp.gravity = Gravity.END;
        group.addView(container, clp);
        sBubble = bubble;
        sRingAnim = startRing(ring);

        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.gravity = Gravity.END | Gravity.BOTTOM;
        glp.rightMargin = dp(12);
        glp.bottomMargin = dp(96);   // 默认落在底栏上方，别压住「商城/我的」
        overlay.addView(group, glp);
        sPanel = group;
        restorePos(a, group);

        final int slop = ViewConfiguration.get(a).getScaledTouchSlop();
        bubble.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private int startRight;
            private int startBottom;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (sPanel == null || sDecor == null) {
                    return false;
                }
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sPanel.getLayoutParams();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startRight = lp.rightMargin;
                        startBottom = lp.bottomMargin;
                        moved = false;
                        sTouching = true;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (downX - e.getRawX());
                        int dy = (int) (downY - e.getRawY());
                        if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                            moved = true;
                        }
                        if (moved) {
                            int maxR = Math.max(0, (sDecor.getWidth() - sPanel.getWidth()) - dp(4));
                            int maxB = Math.max(0, (sDecor.getHeight() - sPanel.getHeight()) - dp(4));
                            lp.rightMargin = clamp(startRight + dx, 0, maxR);
                            lp.bottomMargin = clamp(startBottom + dy, 0, maxB);
                            sPanel.setLayoutParams(lp);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        sTouching = false;
                        if (!moved) {
                            toggleBox();
                        } else {
                            savePos(sAct);
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        sTouching = false;
                        return true;
                    default:
                        return false;
                }
            }
        });

        overlay.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (sPassthrough) {
                    sRootDown = false;
                    return false;       // 不消费：事件继续往下传给宿主界面
                }
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    sRootDown = true;
                    if (insidePanel(e)) {
                        return true;
                    }
                }
                if (e.getAction() == MotionEvent.ACTION_UP) {
                    if (!sRootDown) {
                        return true;
                    }
                    sRootDown = false;
                    try {
                        if (insidePanel(e)) {
                            return true;
                        }
                        int[] loc = new int[2];
                        sDecor.getLocationOnScreen(loc);
                        sLayer = null;
                        View hit = pick(sDecor, e.getRawX() - loc[0], e.getRawY() - loc[1]);
                        if (hit == null) {
                            hit = sLayer;
                        }
                        if (hit != null) {
                            select(hit);
                        } else if (sInfo != null) {
                            sInfo.setText("这里没命中控件；试试点文字/图标，或用「列表模式」");
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "hit failed", t);
                    }
                }
                if (e.getAction() == MotionEvent.ACTION_CANCEL) {
                    sRootDown = false;
                }
                return true;
            }
        });

        renderEditSection();
        updateLevelBtns();

        sHandler = new Handler(Looper.getMainLooper());
        sHandler.postDelayed(KEEP_TOP, 400L);
        Log.i(TAG, "picker shown " + sDecor.getWidth() + "x" + sDecor.getHeight());
    }

    /** 宿主会重建/重排 decor 的子 View，遮罩要定期回到最上层，顺便纠正越界的悬浮球。 */
    private static void keepOnTop() {
        if (sOverlay == null || sDecor == null || sTouching) {
            return;
        }
        try {
            if (sOverlay.getParent() != sDecor) {
                if (sOverlay.getParent() instanceof ViewGroup) {
                    ((ViewGroup) sOverlay.getParent()).removeView(sOverlay);
                }
                ViewGroup.LayoutParams lp = sOverlay.getLayoutParams();
                if (lp == null) {
                    lp = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                }
                sDecor.addView(sOverlay, lp);
            } else if (sDecor.indexOfChild(sOverlay) != sDecor.getChildCount() - 1) {
                sOverlay.bringToFront();
            }
            if (sPanel == null || sPanel.getWidth() <= 0 || sDecor.getWidth() <= 0) {
                return;
            }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sPanel.getLayoutParams();
            int r = clamp(lp.rightMargin, 0,
                    Math.max(0, (sDecor.getWidth() - sPanel.getWidth()) - dp(4)));
            int b = clamp(lp.bottomMargin, 0,
                    Math.max(0, (sDecor.getHeight() - sPanel.getHeight()) - dp(4)));
            if (r == lp.rightMargin && b == lp.bottomMargin) {
                return;
            }
            lp.rightMargin = r;
            lp.bottomMargin = b;
            sPanel.setLayoutParams(lp);
        } catch (Throwable ignored) {
        }
    }

    /** UP 是否落在悬浮球/菜单卡片范围内（这类 UP 不算选点）。 */
    private static boolean insidePanel(MotionEvent e) {
        if (sPanel == null || sPanel.getWidth() == 0) {
            return false;
        }
        int[] loc = new int[2];
        sPanel.getLocationOnScreen(loc);
        return e.getRawX() >= loc[0] && e.getRawX() < loc[0] + sPanel.getWidth()
                && e.getRawY() >= loc[1] && e.getRawY() < loc[1] + sPanel.getHeight();
    }

    /**
     * 找最合适的那一层：自下而上、后加的优先；能往下钻就钻到最深的可见子 View，
     * 太小的（&lt;8dp，比如分割线）丢掉；整屏大小的容器记为兜底候选。
     */
    private static View pick(ViewGroup parent, float x, float y) {
        for (int i = parent.getChildCount() - 1; i >= 0; i--) {
            View c = parent.getChildAt(i);
            if (c == null || c.getTag() == OURS || c.getVisibility() != View.VISIBLE
                    || c.getAlpha() < 0.05f || isModuleView(c)) {
                continue;
            }
            float lx = (parent.getScrollX() + x) - c.getLeft() - c.getTranslationX();
            float ly = (parent.getScrollY() + y) - c.getTop() - c.getTranslationY();
            if (lx < 0 || ly < 0 || lx >= c.getWidth() || ly >= c.getHeight()) {
                continue;
            }
            View deeper = c instanceof ViewGroup ? pick((ViewGroup) c, lx, ly) : null;
            if (deeper != null) {
                return deeper;
            }
            if (c.getWidth() >= dp(8) && c.getHeight() >= dp(8)) {
                if (!isFullScreenLayer(c)) {
                    return c;
                }
                if (sLayer == null) {
                    sLayer = c;
                }
            }
        }
        return null;
    }

    private static boolean isFullScreenLayer(View v) {
        if (sDecor == null || sDecor.getWidth() <= 0 || sDecor.getHeight() <= 0) {
            return false;
        }
        return ((long) v.getWidth()) * v.getHeight() * 100
                >= ((long) sDecor.getWidth()) * sDecor.getHeight() * 80;
    }

    private static boolean isModuleView(View v) {
        return v.getClass().getName().startsWith("com.wodi.redguotools.");
    }

    /** 点选命中：建「叶子 → decor 根」的层链，停在最初点中的那一层（leaf 在链末尾）。 */
    private static void select(View v) {
        sChain = new ArrayList<>();
        View cur = v;
        while (cur != null && cur != sDecor) {
            sChain.add(0, cur);
            cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
        }
        sChainIndex = sChain.isEmpty() ? 0 : sChain.size() - 1;
        applyLevel();
    }

    /** 选中框/规则/信息条跟随 sChainIndex 指向的层重新生成。 */
    private static void applyLevel() {
        if (sChain == null || sChain.isEmpty() || sDecor == null || sMark == null) {
            return;
        }
        sChainIndex = clamp(sChainIndex, 0, sChain.size() - 1);
        View v = sChain.get(sChainIndex);
        sPicked = v;
        sRule = UiController.ruleForPicked(sAct, v);
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        int[] root = new int[2];
        sDecor.getLocationOnScreen(root);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sMark.getLayoutParams();
        lp.width = v.getWidth();
        lp.height = v.getHeight();
        lp.leftMargin = loc[0] - root[0];
        lp.topMargin = loc[1] - root[1];
        sMark.setLayoutParams(lp);
        sMark.setVisibility(View.VISIBLE);
        if (sInfo != null) {
            String txt = "已选：" + describe(v) + "  " + v.getWidth() + "x" + v.getHeight()
                    + (sRule != null ? "\n规则：" + sRule
                    : "\n这个控件没有可靠特征，点「上一级」选外层容器再隐藏");
            // 层级指示：N=1 是最初点中的控件，每按一次「上一级」+1
            int n = sChain.size() - sChainIndex;
            if (n > 1) {
                txt += "（第 " + n + " 级）";
            }
            sInfo.setText(txt);
        }
        // 卡片收着时选中了控件：把卡片弹出来，否则用户看不到规则和「隐藏所选」
        if (sBox != null && sBox.getVisibility() != View.VISIBLE) {
            toggleBox();
        }
        updateLevelBtns();
    }

    /** 上一级：往根走，范围变大。 */
    private static void upLevel() {
        if (sChain == null || sChainIndex <= 0) {
            return;
        }
        sChainIndex--;
        applyLevel();
    }

    /** 下一级：往叶子走，范围变小。 */
    private static void downLevel() {
        if (sChain == null || sChainIndex >= sChain.size() - 1) {
            return;
        }
        sChainIndex++;
        applyLevel();
    }

    /** 「上一级/下一级」到链两端时置灰。 */
    private static void updateLevelBtns() {
        boolean has = sChain != null && !sChain.isEmpty();
        setUsable(sUpBtn, has && sChainIndex > 0);
        setUsable(sDownBtn, has && sChainIndex < sChain.size() - 1);
    }

    private static void setUsable(TextView b, boolean on) {
        if (b == null) {
            return;
        }
        b.setEnabled(on);
        b.setAlpha(on ? 1f : 0.4f);
    }

    /** 清空当前选中与层链，隐藏选中框。 */
    private static void clearSelection() {
        sPicked = null;
        sRule = null;
        sChain = null;
        sChainIndex = 0;
        if (sMark != null) {
            sMark.setVisibility(View.INVISIBLE);
        }
        updateLevelBtns();
    }

    private static String describe(View v) {
        StringBuilder sb = new StringBuilder();
        String name = v.getClass().getSimpleName();
        if (name == null || name.isEmpty()) {
            name = v.getClass().getName();
        }
        sb.append(name);
        if (v.getId() != View.NO_ID) {
            sb.append(" #").append(idName(v, v.getId()));
        }
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) {
                sb.append(" \"").append(shortText(t.toString())).append('"');
            }
        }
        return sb.toString();
    }

    private static String idName(View v, int id) {
        try {
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return String.valueOf(id);
        }
    }

    private static String shortText(String s) {
        s = s.trim().replace('\n', ' ');
        return s.length() > 24 ? s.substring(0, 24) : s;
    }

    /** 加入清单 + 立即刷新，不退出点选模式（可以连着藏好几个）。首次隐藏自动进入草稿会话。 */
    private static void hidePicked(Activity a, String rule) {
        if (!EditGuard.isEditing(a)) {
            EditGuard.enter(a);
        }
        try {
            Config.addOverlay(a, rule);
        } catch (Throwable t) {
            Log.e(TAG, "addOverlay failed", t);
            return;
        }
        // 计数 + toast 跟着 addOverlay 成功走
        EditGuard.onApplied(a);
        try {
            if (otherHost(a)) {
                ScanHide.applyNow(a);       // 番茄/畅听：走它们自己的隐藏引擎
            } else {
                UiController.refresh(a);
            }
        } catch (Throwable t) {
            Log.e(TAG, "refresh failed", t);
        }
        Log.i(TAG, "hide " + rule + " (draft)");
        renderEditSection();
    }

    private static void close() {
        if (sHandler != null) {
            sHandler.removeCallbacks(KEEP_TOP);
            sHandler = null;
        }
        if (sRingAnim != null) {
            sRingAnim.cancel();
            sRingAnim = null;
        }
        if (sOverlay != null && sOverlay.getParent() instanceof ViewGroup) {
            ((ViewGroup) sOverlay.getParent()).removeView(sOverlay);
        }
        sOverlay = null;
        sMark = null;
        sInfo = null;
        sPanel = null;
        sBox = null;
        sBubble = null;
        sPicked = null;
        sRule = null;
        sLayer = null;
        sTouching = false;
        sRootDown = false;
        sPassthrough = false;
        sPassBtn = null;
        sUpBtn = null;
        sDownBtn = null;
        sEditInfo = null;
        sEditBtn = null;
        sChain = null;
        sChainIndex = 0;
        sRing = null;
    }

    private static TextView button(int card, int bg, int ink, int danger,
                                   String text, boolean red, View.OnClickListener l) {
        TextView b = new TextView(sAct);
        b.setTag(OURS);
        b.setText(text);
        b.setTextColor(red ? danger : ink);
        b.setTextSize(13f);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(6), 0, dp(6), 0);
        GradientDrawable d = new GradientDrawable();
        d.setColor(red ? ((danger & 0x00FFFFFF) | 0x1A000000) : bg);
        d.setCornerRadius(dp(12));
        b.setBackground(d);
        b.setOnClickListener(l);
        return b;
    }

    /**
     * 穿透模式：遮罩不再吃触摸，可以正常操作 App；球仍然可点可拖，点开再恢复点选。
     *
     * <p>用户原话：「我不想操作软件的时候直接选中这个组件了」—— 点选模式本质是
     * 全屏吃掉触摸，没有这个开关就必须先「退出」才能用 App，太绕。
     *
     * <p>不清选中（快速查看用：切过去看一眼，切回来接着选）。
     */
    private static void setPassthrough(boolean on) {
        sPassthrough = on;
        restyleTouch();
        if (sInfo != null) {
            sInfo.setText(on
                    ? "已暂停点选：现在可以正常操作 App，点小球 →「点选」恢复"
                    : "点按要隐藏的控件；可点「上一级」扩大范围，点「退出」结束");
        }
    }

    /** 按 sPassthrough 重画遮罩、球、旋转弧和「穿透」按钮（换色 + 换文案）。 */
    private static void restyleTouch() {
        boolean pass = sPassthrough;
        if (sOverlay != null) {
            sOverlay.setClickable(!pass);
            sOverlay.setBackgroundColor(pass ? 0x00000000 : 0x33000000);
        }
        // 球和按钮整体换色：白+蓝 = 正在点选；橙 = 穿透中（一眼看出当前是哪种状态）
        boolean orange = sPassthrough;
        if (sBubble instanceof TextView) {
            TextView b = (TextView) sBubble;
            b.setText(sPassthrough ? "穿透" : "选择");
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(orange ? 0xFFFFF1E0 : ((sCard & 0x00FFFFFF) | 0xF2000000));
            bg.setStroke(dp(1), orange ? 0xFFF57C00 : sStroke);
            b.setBackground(bg);
            b.setTextColor(orange ? 0xFFE65100 : sInk);
        }
        if (sRing != null) {
            sRing.setAccent(orange ? 0xFFF57C00 : sPrimary);
        }
        if (sPassBtn != null) {
            sPassBtn.setText(sPassthrough ? "点选" : "穿透");
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(12));
            bg.setColor(sPassthrough ? sPrimary : 0xFFF2F2F4);
            sPassBtn.setBackground(bg);
            sPassBtn.setTextColor(sPassthrough ? 0xFFFFFFFF : sInk);
        }
    }

    /** 「编辑控件」小节：状态行 + 按钮文案/动作跟着草稿状态走。 */
    private static void renderEditSection() {
        Activity a = sAct;
        if (a == null || sEditInfo == null || sEditBtn == null) {
            return;
        }
        if (EditGuard.isEditing(a)) {
            sEditInfo.setText("编辑中：已加入 " + EditGuard.draftCount(a) + " 条草稿，保存后一次生效");
            sEditBtn.setText("保存并退出编辑");
            sEditBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (sAct != null) {
                        EditGuard.save(sAct);
                    }
                    renderEditSection();
                }
            });
        } else {
            sEditInfo.setText("编辑模式：多步改动先累积，保存时一次确认");
            sEditBtn.setText("进入编辑模式");
            sEditBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (sAct != null) {
                        EditGuard.enter(sAct);
                    }
                    renderEditSection();
                }
            });
        }
    }

    /** 是否真的在"吃触摸"（穿透/暂停下不算），宿主的手势 hook 用它做闸门。 */
    static boolean isCapturing() {
        return sOverlay != null && !sPassthrough;
    }

    /** 番茄 / 番茄畅听：它们没有红果那套 UiController/SettingsPanel，走 ScanHide。 */
    private static boolean otherHost(Activity a) {
        if (a == null) {
            return false;
        }
        String p = a.getPackageName();
        return Hosts.isFanqie(p) || Hosts.isCt(p);
    }

    private static void toggleBox() {
        if (sBox == null) {
            return;
        }
        boolean open = sBox.getVisibility() != View.VISIBLE;
        sBox.setVisibility(open ? View.VISIBLE : View.GONE);
        if (sRingAnim != null) {
            sRingAnim.setDuration(open ? 1800L : 4200L);
        }
    }

    private static ValueAnimator startRing(View v) {
        ObjectAnimator anim = ObjectAnimator.ofFloat(v, "rotation", 0f, 360f);
        anim.setDuration(4200L);
        anim.setInterpolator(new LinearInterpolator());
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.start();
        return anim;
    }

    /** 圆球外圈：1dp 淡色底环 + 3dp 主色弧，弧跟着 View 旋转。 */
    private static final class Ring extends View {
        private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);

        Ring(Context c, int primary) {
            super(c);
            base.setStyle(Paint.Style.STROKE);
            base.setStrokeWidth(dp(1));
            base.setColor((primary & 0x00FFFFFF) | 0x66000000);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(dp(3));
            arc.setStrokeCap(Paint.Cap.ROUND);
            arc.setColor(primary);
        }

        void setAccent(int color) {
            arc.setColor(color);
            base.setColor((color & 0x00FFFFFF) | 0x66000000);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(cx, cy) - dp(3);
            canvas.drawCircle(cx, cy, r, base);
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, 100f, false, arc);
        }
    }

    /** 位置按「圆球中心占屏幕的万分数」存，换分辨率/旋屏后仍落在相对位置。 */
    private static void restorePos(final Activity a, final ViewGroup group) {
        group.post(new Runnable() {
            @Override
            public void run() {
                if (group != sPanel || sDecor == null
                        || sDecor.getWidth() <= 0 || sDecor.getHeight() <= 0) {
                    return;
                }
                int px = Config.getInt(a, KEY_POS_X, -1);
                int py = Config.getInt(a, KEY_POS_Y, -1);
                if (px < 0 || py < 0) {
                    return;
                }
                int w = sDecor.getWidth();
                int h = sDecor.getHeight();
                int gw = group.getWidth();
                int gh = group.getHeight();
                if (gw <= 0 || gh <= 0) {
                    return;
                }
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) group.getLayoutParams();
                lp.rightMargin = clamp(w - (px * w) / 10000 - gw / 2, 0, Math.max(0, w - gw));
                lp.bottomMargin = clamp(h - (py * h) / 10000 - gh / 2, 0, Math.max(0, h - gh));
                group.setLayoutParams(lp);
            }
        });
    }

    private static void savePos(Activity a) {
        if (a == null || sDecor == null || sPanel == null || sBubble == null) {
            return;
        }
        int w = sDecor.getWidth();
        int h = sDecor.getHeight();
        int bw = sBubble.getWidth();
        int bh = sBubble.getHeight();
        if (w <= 0 || h <= 0 || bw <= 0 || bh <= 0) {
            return;
        }
        int[] loc = new int[2];
        sBubble.getLocationOnScreen(loc);
        int[] root = new int[2];
        sDecor.getLocationOnScreen(root);
        int cx = (loc[0] - root[0]) + bw / 2;
        int cy = (loc[1] - root[1]) + bh / 2;
        Config.setInt(a, KEY_POS_X, clamp((cx * 10000) / w, 0, 10000));
        Config.setInt(a, KEY_POS_Y, clamp((cy * 10000) / h, 0, 10000));
        Log.i(TAG, "bubble pos saved " + cx + "," + cy);
    }

    private static LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        lp.leftMargin = dp(3);
        lp.rightMargin = dp(3);
        return lp;
    }

    /** 小节标签：灰字小标题。 */
    private static TextView label(int sub, String text) {
        TextView t = new TextView(sAct);
        t.setTag(OURS);
        t.setText(text);
        t.setTextSize(11f);
        t.setTextColor(sub);
        t.setSingleLine(true);
        t.setPadding(dp(4), dp(2), dp(4), dp(4));
        return t;
    }

    /** 通栏按钮的排版参数（与三钮行同高）。 */
    private static LinearLayout.LayoutParams full() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        lp.leftMargin = dp(3);
        lp.rightMargin = dp(3);
        lp.bottomMargin = dp(6);
        return lp;
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static int dp(int v) {
        Activity a = sAct;
        return a == null ? v : Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
