package com.wodi.redguotools;

import android.app.Activity;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 番茄畅听（{@code com.xs.fm} 6.7.8.32）的界面精简。
 *
 * <h3>和番茄小说 / 红果的关系</h3>
 * 三个包同源不同代。7.x（红果 / 番茄小说）底栏是
 * {@code BottomTabBarItemType} + {@code pages.main.m#c()} 工厂 + 返回 null 即不创建；
 * <b>6.7.8 的畅听没有这一套</b>，它用老的 {@code AbsBottomTab} + XML {@code RadioGroup}：
 * <ol>
 *   <li>{@code AbsBottomTab.initTabAndAction()} 把布局里 6~7 个
 *       {@code ScaleBadgeRadioButton} 找出来挂监听；</li>
 *   <li>子类 {@code FMBottomTab}（混淆名 {@code pages.main.tab.k}）在
 *       {@code !RecommendTabApi.IMPL.isDefaultTabList()} 时，按
 *       {@code RecommendTabApi.IMPL.getBottomTabList()} 的 **顺序** 从这堆按钮里
 *       挑一个复用它、{@code setText(tabName)}、再 addView 回去，剩下的全部
 *       {@code setVisibility(8)}。</li>
 * </ol>
 * 也就是说：**底栏文案完全来自数据层的 tabName**，视图层只是复用池。
 *
 * <h3>为什么按文案 GONE 而不是改数据源</h3>
 * 改 {@code getBottomTabList()} 的返回值看着更「源头」，但那条 map 是全局共享实例
 * （{@code BottomTabDataManager} 的 lazy 缓存），删 key 会连带影响
 * 「上次停留的 tab」「默认 tab」「服务端恢复」等一串逻辑，还要处理
 * {@code isDefaultTabList()==true}（未下发配置，走 XML 原样布局）这一支 ——
 * 那条路上根本没有 map 可删。
 *
 * <p>反过来：子类重排结束后**再没有任何地方重设可见性**（本版本实测，
 * {@code onTabCheckedChanged} 只是回调），所以重排后一次 GONE 就是终态；
 * 切页、切主题都不会复活。RadioGroup 是 LinearLayout，GONE 的格子不参与
 * measure，剩下的两个各占一半，不会留空位。
 *
 * <p>代价是文案依赖：服务端换名字（领现金 / 领金币 / 福利）就得跟着改，
 * 所以 {@link #HIDE_WORDS} 收的是「词根」，且每次命中都写日志。
 */
final class Ct {

    /** 底栏实现父类（长类名，跨版本稳定）。 */
    private static final String CLS_ABS_TAB = "com.dragon.read.pages.main.tab.AbsBottomTab";

    /**
     * 要隐藏的底栏文案词根。
     *
     * <p>用户要求：只留「首页」「我的」，去掉「商城 / 听歌 / 领金币（截图里是领现金）」。
     * 「福利」是同一格位的另一套实验文案（金币页被 A/B 换成福利时用），一并收掉。
     * 用 contains 而不是 equals：宿主的文案会带角标后缀或空格。
     */
    static final String[] HIDE_WORDS = {"商城", "听歌", "领现金", "领金币", "福利"};

    private static volatile boolean sInit;
    private static volatile String sStatus;

    private Ct() {
    }

    static String status() {
        return sStatus;
    }

    static void hook(XposedModule mod, ClassLoader cl) {
        if (sInit) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("tab=").append(hookBottomTab(mod, cl));
        sb.append(" ad=").append(hookAd(mod, cl));
        sb.append(" resume=").append(hookResume(mod));
        sb.append(" tabchg=").append(hookTabChange(mod, cl));
        sb.append(" touch=").append(hookTouch(mod));
        sb.append(" popup=").append(hookPopup(mod));
        try {
            ScanHide.hook(mod);
            sb.append(" scan=ok");
        } catch (Throwable t) {
            sb.append(" scan=x");
            diag("scan hook failed " + t);
        }
        sStatus = sb.toString();
        sInit = true;
        diag("install " + sb);
    }

    /* ---------------- 广告总闸 ---------------- */

    /** 6.7.8 的广告位开关（7.x 叫 NsAdImpl，这里叫 AdConfigManager）。 */
    private static final String CLS_AD_CFG = "com.dragon.read.base.ad.AdConfigManager";

    /**
     * {@code checkAdAvailable(position, source)} 返回 true 就代表这个广告位可以展示。
     *
     * <p>宿主每个广告位（开屏 / 首页 TopView / 信息流 / 阅读中 / 短剧暂停 …）在真正拉广告前
     * 都会问一次，所以恒 false 就是「全关」，不需要逐个广告位去打点 —— 这也是红果那边
     * 验证过的路子，只是换了类名。
     */
    private static String hookAd(XposedModule mod, ClassLoader cl) {
        try {
            Class<?> cls = RGModule.tryLoad(cl, CLS_AD_CFG);
            if (cls == null) {
                diag("ad: AdConfigManager absent");
                return "absent";
            }
            int n = 0;
            for (Method m : cls.getDeclaredMethods()) {
                if (!"checkAdAvailable".equals(m.getName()) || m.getParameterCount() != 2) {
                    continue;
                }
                mod.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            if (!Config.ctBlockAds(UiController.appCtx())) {
                                return chain.proceed();
                            }
                            try {
                                diag("block ad: " + chain.getArg(0) + " / " + chain.getArg(1));
                            } catch (Throwable ignored) {
                            }
                            return Boolean.FALSE;
                        });
                n++;
            }
            diag("ad: hooks=" + n);
            return n == 0 ? "nohook" : "ok";
        } catch (Throwable t) {
            diag("ad: hook failed " + t);
            return "fail";
        }
    }

    /* ---------------- 诊断日志 ---------------- */

    /**
     * 写 App 私有目录。
     *
     * <p>不用 {@link UiController#logFile}：那个走模块自己包的路径，在宿主进程里
     * 早期（Application 还没建完）拿不到 ctx 会静默丢。这里直接写宿主的 files/，
     * 任何时机都有权限，root 也读得到。
     */
    private static void diag(String msg) {
        java.io.FileOutputStream fos = null;
        try {
            java.io.File f = new java.io.File(
                    "/data/data/" + Hosts.CT + "/files/rg_ct.txt");
            fos = new java.io.FileOutputStream(f, true);
            fos.write((System.currentTimeMillis() + " " + msg + "\n").getBytes("UTF-8"));
        } catch (Throwable ignored) {
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /* ---------------- 底栏：只留首页 / 我的 ---------------- */

    private static String hookBottomTab(XposedModule mod, ClassLoader cl) {
        try {
            Class<?> abs = RGModule.tryLoad(cl, CLS_ABS_TAB);
            if (abs == null) {
                diag("tab: AbsBottomTab absent");
                return "absent";
            }
            Method init = abs.getDeclaredMethod("initTabAndAction");
            init.setAccessible(true);
            Method getGroup = abs.getMethod("getTabRadioGroup");
            getGroup.setAccessible(true);

            mod.hook(init)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object r = chain.proceed();
                        try {
                            if (!Config.ctHideTabs(UiController.appCtx())) {
                                return r;
                            }
                            Object self = chain.getThisObject();
                            Object rg = getGroup.invoke(self);
                            if (rg instanceof View) {
                                final View group = (View) rg;
                                // 子类重排（removeAllViews + 逐个 addView + setText）都在
                                // 本方法返回前同步完成，所以只要排到下一帧就一定看得到终态文案。
                                group.post(() -> hideTabs(group));
                            }
                        } catch (Throwable t) {
                            diag("tab: apply failed " + t);
                        }
                        return r;
                    });
            return "ok";
        } catch (Throwable t) {
            diag("tab: hook failed " + t);
            return "fail";
        }
    }

    /** 按文案把不要的底栏格子 GONE 掉；每次重排后重跑，天然幂等。 */
    private static void hideTabs(View group) {
        if (!(group instanceof ViewGroup)) {
            return;
        }
        ViewGroup rg = (ViewGroup) group;
        StringBuilder seen = new StringBuilder();
        for (int i = 0; i < rg.getChildCount(); i++) {
            View child = rg.getChildAt(i);
            String text = textOf(child).trim();
            seen.append('[').append(text).append(']');
            diag("  child " + i + " " + child.getClass().getSimpleName()
                    + " id=" + Integer.toHexString(child.getId())
                    + " vis=" + child.getVisibility()
                    + " kids=" + (child instanceof ViewGroup
                    ? ((ViewGroup) child).getChildCount() : 0)
                    + " txt=[" + text + "]");
            boolean hit = false;
            for (String w : HIDE_WORDS) {
                if (text.contains(w)) {
                    hit = true;
                    break;
                }
            }
            if (hit && child.getVisibility() != View.GONE) {
                child.setVisibility(View.GONE);
                diag("hide tab: " + text + " idx=" + i);
            }
        }
        diag("tabs now: " + seen);
    }

    /**
     * 取一个格子的可见文案。
     *
     * <p>不能只看 {@code getText()}：实测「领现金」那格的 {@code ScaleBadgeRadioButton}
     * 自身 text 是空的，文案在它的子 View 上（带角标/红包图的那套组合）。
     * 递归收一遍子节点的文本，才能拿到真实名字。
     */
    private static String textOf(View v) {
        if (v instanceof android.widget.TextView) {
            CharSequence c = ((android.widget.TextView) v).getText();
            if (c != null && c.length() > 0) {
                return c.toString();
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < g.getChildCount(); i++) {
                sb.append(textOf(g.getChildAt(i)));
            }
            return sb.toString();
        }
        return "";
    }

    /* ---------------- 弹窗 ---------------- */

    private static String hookPopup(XposedModule mod) {
        try {
            FanqiePopup.hook(mod);
            return "ok";
        } catch (Throwable t) {
            diag("popup: hook failed " + t);
            return "fail";
        }
    }

    /* ---------------- 「我的」页精简 ---------------- */

    /**
     * 「我的」页三刀：金刚位卡、我的资产卡、右上角四宫格。
     *
     * <p>结构（6.7.8 实测，视图树见 rgtools_tree.txt）：
     * <pre>
     *   MeasureOnceRelativeLayout2
     *     ├─ SimpleDraweeView            背景图
     *     └─ CoordinatorLayout
     *          └─ CommonCustomAppBarLayout
     *               └─ LinearLayout e7c
     *                    ├─ ConstraintLayout hf4   头像 / 昵称 / 签名 / 右侧小箭头
     *                    ├─ RelativeLayout   cf1   ★金刚位（Compose 电商卡：消息/优惠券/购物车/订单/游戏）
     *                    └─ LinearLayout     e9g   ★我的资产（金币余额 / 现金余额 / 今日签到）
     *     RelativeLayout gku（浮在最上层，高 283）  顶部工具条
     *          ├─ scale.c fzu      夜间
     *          ├─ scale.c fpx
     *          ├─ scale.c qd
     *          ├─ scale.c ejx      ★四宫格（全部功能）
     *          └─ scale.c ff8      上者的红点角标
     * </pre>
     *
     * <p>id 是混淆短名（cf1/e9g/gku/ejx/ff8），同版本固定、升级会变，所以每次命中都写日志；
     * 四宫格还带一层结构兜底：拿不到 id 就取工具条里 <b>x 最大且可点击</b> 的那个
     * —— 它永远排在最右边。
     */
    private static void applyMine(Activity a) {
        applyMineRound(a, 0);
    }

    /** 四宫格那个 View 的引用：一旦定位就复用，避免每轮重新按坐标挑（见 applyMineOnce）。 */
    private static View sAllFuncView;

    /**
     * 分三轮执行。
     *
     * <p>为什么要重试：金刚位那张卡是 Compose 画的（{@code MineEcomKmpCardAndroidView}），
     * 数据回来后会**重新 setVisibility(VISIBLE)**，一次性 GONE 会被它盖回来 ——
     * 实测第一次隐藏成功（日志有），过一拍又出现在界面上。三轮 600ms 间隔足够覆盖
     * 首屏数据回填那段窗口，之后宿主不会再动它。
     */
    private static void applyMineRound(Activity a, int round) {
        applyMineOnce(a, round);
        if (round < 3 && a != null && !a.isFinishing()) {
            try {
                a.getWindow().getDecorView().postDelayed(
                        () -> applyMineRound(a, round + 1), 600);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void applyMineOnce(Activity a, int round) {
        if (a == null || a.isFinishing()) {
            return;
        }
        View decor = a.getWindow().getDecorView();
        if (decor == null) {
            return;
        }
        String pkg = a.getPackageName();
        android.content.res.Resources res = a.getResources();
        boolean slim = Config.ctSlimMine(a);
        boolean noAllFunc = Config.ctHideAllFunc(a);
        if (!slim && !noAllFunc) {
            return;
        }

        if (slim) {
            hideById(a, decor, res, pkg, "cf1", "金刚位", round);
            hideById(a, decor, res, pkg, "e9g", "我的资产", round);
        }

        if (noAllFunc) {
            // 先按 id 直接命中（ejx）。**不能每轮都重新按坐标挑**：
            // 被 GONE 掉的 View 的 getLocationOnScreen 会返回 (0,0)，
            // 于是下一轮"x 最大"会挑到别的图标，一轮一个把整排干掉
            // —— 实测四轮下来连最左边的夜间模式都被误杀。
            View target = sAllFuncView;
            if (target != null && target.getParent() == null) {
                target = null;
                sAllFuncView = null;
            }
            if (target == null) {
                target = findById(a, decor, res, pkg, "ejx");
            }
            if (target == null) {
                target = pickRightmostClickable(a, decor, res, pkg, round);
            }
            if (target != null) {
                sAllFuncView = target;
                if (target.getAlpha() != 0f || target.isClickable()) {
                    if (round == 0) {
                        diag("mine: hide 四宫格 "
                                + (target.getId() == 0 ? "?" : Integer.toHexString(target.getId()))
                                + " " + target.getWidth() + "x" + target.getHeight());
                    }
                    // 用「透明 + 不可点」而不是 GONE：实测把这一个 GONE 掉之后，
                    // 同一排的另外两个图标也跟着不渲染了（gku 是自定义 RelativeLayout，
                    // onLayout 按子 View 索引摆位，少一个就整排错位）。
                    // 透明保住占位，视觉上同样"没有这个按钮"。
                    target.setAlpha(0f);
                    target.setClickable(false);
                    target.setOnClickListener(null);
                    View bar = findById(a, decor, res, pkg, "gku");
                    View dot = bar != null ? findById(a, bar, res, pkg, "ff8") : null;
                    if (dot != null) {
                        dot.setAlpha(0f);
                    }
                }
            }
        }
    }

    /** 结构兜底：工具条里 x 最大且可点击的那个（四宫格永远在最右）。 */
    private static View pickRightmostClickable(Activity a, View decor,
                                               android.content.res.Resources res,
                                               String pkg, int round) {
        View bar = findById(a, decor, res, pkg, "gku");
        if (!(bar instanceof ViewGroup)) {
            return null;
        }
        ViewGroup g = (ViewGroup) bar;
        View target = null;
        int bestX = -1;
        StringBuilder sb = new StringBuilder("gku:");
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            int[] loc = new int[2];
            c.getLocationOnScreen(loc);
            sb.append(' ').append(i).append("@").append(loc[0])
                    .append(" w=").append(c.getWidth())
                    .append(" vis=").append(c.getVisibility())
                    .append(c.isClickable() ? " clk" : "");
            if (c.getVisibility() != View.VISIBLE || c.getWidth() <= 0 || !c.isClickable()) {
                continue;
            }
            if (loc[0] > bestX) {
                bestX = loc[0];
                target = c;
            }
        }
        if (round == 0) {
            diag("mine: " + sb);
        }
        return target;
    }

    private static View findById(Activity a, View root, android.content.res.Resources res,
                                 String pkg, String idName) {
        int id = res.getIdentifier(idName, "id", pkg);
        if (id == 0) {
            return null;
        }
        try {
            return root.findViewById(id);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void hideById(Activity a, View decor, android.content.res.Resources res,
                                 String pkg, String idName, String tag, int round) {
        View v = findById(a, decor, res, pkg, idName);
        if (v == null) {
            if (round == 0) {
                diag("mine: " + tag + " id " + idName + " not found");
            }
            return;
        }
        if (v.getVisibility() != View.GONE) {
            diag("mine: hide " + tag + " " + idName + " " + v.getWidth() + "x" + v.getHeight()
                    + " round=" + round);
            v.setVisibility(View.GONE);
        }
    }

    /* ---------------- 长按唤出面板 ---------------- */

    private static final long LONG_PRESS_MS = 600;
    private static final int ZONE_NONE = 0;
    private static final int ZONE_BOTTOM = 1;
    private static final int ZONE_TOP = 2;

    private static float sDownX;
    private static float sDownY;
    private static int sZone = ZONE_NONE;
    private static boolean sFired;
    private static Activity sAct;
    /** 最近一次 onResume 的 Activity —— 兜底用。 */
    private static volatile Activity sTop;

    /**
     * 记录当前 Activity。
     *
     * <p>为什么需要：冷启后第一次长按，{@code sAct}（DOWN 时抓到的那个）经常已经
     * {@code isFinishing()} —— 宿主从闪屏切首页时会有一次 Activity 交接，抓到的
     * 是正在销毁的那个，于是面板静默不弹（日志里只有 long press 没有 panel shown）。
     * 记住最近 resume 的那个，失败时用它兜底。
     */
    private static String hookResume(XposedModule mod) {
        try {
            Method m = Activity.class.getDeclaredMethod("onResume");
            mod.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object r = chain.proceed();
                        Object self = chain.getThisObject();
                        if (self instanceof Activity) {
                            Activity a = (Activity) self;
                            if (Hosts.isCt(a.getPackageName()) && !a.isFinishing()) {
                                sTop = a;
                                try {
                                    a.getWindow().getDecorView().postDelayed(() -> {
                                        try {
                                            applyMine(a);
                                        } catch (Throwable ignored) {
                                        }
                                    }, 2000);
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                        return r;
                    });
            return "ok";
        } catch (Throwable t) {
            diag("resume: hook failed " + t);
            return "fail";
        }
    }

    /**
     * 切 tab 后重扫。
     *
     * <p>切到「我的」页**不会触发 Activity.onResume**（同一个 Activity 里换 Fragment），
     * 所以「我的」页上的精简只能靠底栏的选中事件来驱动。
     */
    private static String hookTabChange(XposedModule mod, ClassLoader cl) {
        try {
            Class<?> abs = RGModule.tryLoad(cl, CLS_ABS_TAB);
            if (abs == null) {
                return "absent";
            }
            Method m = abs.getDeclaredMethod("onTabCheckedChanged", int.class);
            m.setAccessible(true);
            mod.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object r = chain.proceed();
                        try {
                            // AbsBottomTab 不是 View（它是组合底栏的一个普通对象），
                            // 所以不能拿 this 当 post 目标 —— 用当前 Activity 的 decor。
                            final Activity ca = sTop;
                            if (ca != null && !ca.isFinishing()) {
                                ca.getWindow().getDecorView().postDelayed(() -> {
                                    try {
                                        applyMine(ca);
                                    } catch (Throwable ignored) {
                                    }
                                }, 700);
                            }
                        } catch (Throwable ignored) {
                        }
                        return r;
                    });
            return "ok";
        } catch (Throwable t) {
            diag("tabchg: hook failed " + t);
            return "fail";
        }
    }

    private static final Runnable FIRE = new Runnable() {
        @Override
        public void run() {
            if (sZone == ZONE_NONE || sFired) {
                return;
            }
            Activity a = (sAct != null && !sAct.isFinishing()) ? sAct : sTop;
            if (a == null || a.isFinishing()) {
                diag("long press: no usable activity (sAct=" + sAct + " top=" + sTop + ")");
                return;
            }
            sFired = true;
            diag("long press -> panel zone=" + sZone);
            try {
                FanqiePanel.show(a, true);
                diag("panel shown=" + FanqiePanel.isShowing());
            } catch (Throwable t) {
                diag("panel failed: " + t);
            }
        }
    };

    /**
     * 挂 {@code Activity.dispatchTouchEvent} 识别长按。
     *
     * <p>两个热区：底部左侧（「首页」格，y 在最后 10%、x 在左 1/3）和顶部中央
     * （x 30%~70%、y<8%）—— 后者是二级页唯一的入口，畅听的阅读/播放页没有底栏。
     */
    private static String hookTouch(XposedModule mod) {
        try {
            Method dispatch = Activity.class.getDeclaredMethod(
                    "dispatchTouchEvent", MotionEvent.class);
            mod.hook(dispatch)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object self = chain.getThisObject();
                        if (self instanceof Activity) {
                            Activity a = (Activity) self;
                            if (Hosts.isCt(a.getPackageName())) {
                                MotionEvent ev = (MotionEvent) chain.getArg(0);
                                try {
                                    if (onTouch(a, ev)) {
                                        return Boolean.TRUE;
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                        return chain.proceed();
                    });
            return "ok";
        } catch (Throwable t) {
            diag("touch: hook failed " + t);
            return "fail";
        }
    }

    private static boolean onTouch(Activity a, MotionEvent ev) {
        // 悬浮球点选中：触摸归遮罩，这里不抢（穿透模式下照常）
        if (PickerOverlay.isCapturing()) {
            return false;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                sDownX = ev.getRawX();
                sDownY = ev.getRawY();
                sFired = false;
                sAct = a;
                int w = a.getResources().getDisplayMetrics().widthPixels;
                int h = a.getResources().getDisplayMetrics().heightPixels;
                if (sDownY > h * 0.90f && sDownX < w * 0.34f) {
                    sZone = ZONE_BOTTOM;
                } else if (sDownY < h * 0.08f && sDownX > w * 0.30f && sDownX < w * 0.70f) {
                    sZone = ZONE_TOP;
                } else {
                    sZone = ZONE_NONE;
                }
                if (sZone != ZONE_NONE) {
                    try {
                        a.getWindow().getDecorView().postDelayed(FIRE, LONG_PRESS_MS);
                    } catch (Throwable ignored) {
                    }
                }
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                if (sZone != ZONE_NONE && !sFired) {
                    int slop = Theme.dp(a, 20);
                    if (Math.abs(ev.getRawX() - sDownX) > slop
                            || Math.abs(ev.getRawY() - sDownY) > slop) {
                        cancel();
                    }
                }
                return false;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean fired = sFired;
                cancel();
                return fired;
            }
            default:
                return false;
        }
    }

    private static void cancel() {
        sZone = ZONE_NONE;
        if (sAct != null) {
            try {
                sAct.getWindow().getDecorView().removeCallbacks(FIRE);
            } catch (Throwable ignored) {
            }
        }
        sAct = null;
    }
}
