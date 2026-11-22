package com.wodi.redguotools;

import android.app.Activity;
import android.app.Dialog;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 番茄免费小说（{@code com.dragon.read}）的界面精简。
 *
 * <h3>和红果的关系</h3>
 * 两个 App 出自同一套 {@code com.dragon.read.*} 代码库，类名方法名完全一致，
 * **机制可以共用、策略必须分开** —— 同一个 {@code BottomTabBarItemType} 枚举值
 * 在两边指的不是同一个 tab：
 * <pre>
 *   BookStore          番茄=书城     红果=剧场
 *   VideoSeriesFeedTab 番茄=短剧     红果=首页
 *   BookShelf          番茄=书架     红果=不显示
 *   LuckyBenefit       番茄=赚钱     红果=赚钱/福利
 *   ShopMall           番茄=商城     红果=商城
 *   MyProfile          番茄=我的     红果=我的
 * </pre>
 * （映射来自 ClearApp 的实测表，开源：github.com/wuqiao309/clear-tomato）
 *
 * <h3>为什么走「让宿主不创建」而不是事后再隐藏</h3>
 * 底栏每个 tab 都先经 {@code pages.main.m#c(ViewGroup, BottomTabBarItemType)} 建出来。
 * 这个方法**本来就允许返回 null**（商城在插件没装、福利在活动关掉时就是这么返回的），
 * 调用方已经能接住。返回 null 等于"这个 tab 不存在"，底栏不会留空位。
 *
 * <p>「我的」页的功能入口同理：宿主自己会逐个问
 * {@code IBsMineTabCellService#isCellTypeShowable(int)}，答 false 就根本不建那一格。
 *
 * <h3>版本相关性</h3>
 * <ul>
 *   <li>{@code com.dragon.read.pages.main.m}、{@code BottomTabBarItemType}、
 *       {@code IBsMineTabCellService} 都是**长类名**，跨版本稳定；</li>
 *   <li>卡片分发器（我的页那些 POLARIS 卡）是混淆短名，每版都变 —— 本类暂不碰，
 *       需要时按版本补。</li>
 * </ul>
 */
final class Fanqie {

    private static final String CLS_MAIN = "com.dragon.read.pages.main.m";
    private static final String CLS_TAB_TYPE = "com.dragon.read.rpc.model.BottomTabBarItemType";
    private static final String CLS_CELL_IFACE =
            "com.dragon.read.component.biz.impl.mine.card.IBsMineTabCellService";

    /** 「赚钱」tab 的枚举名（番茄）。 */
    private static final String TAB_EARN = "LuckyBenefit";

    private static volatile boolean sInit;
    private static volatile String sStatus;

    /** 已打过日志的 cell 编号，避免重组时刷屏。 */
    private static final java.util.Set<Integer> sLoggedCells = new java.util.HashSet<>();

    private Fanqie() {
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
        sb.append(" cell=").append(hookMineCells(mod, cl));
        sb.append(" touch=").append(hookTouch(mod));
        sb.append(" txt=").append(hookTextFilter(mod));
        // 这三项复用红果那套现成实现：类名都在 com.dragon.read.* 下，两边通用。
        // 红果分支原本就挂了它们，番茄之前漏了 —— 所以番茄一直会弹升级、还会拉热更新补丁。
        try {
            HotfixBlock.hook(mod, cl);
            sb.append(" hotfix=").append(HotfixBlock.status());
        } catch (Throwable t) {
            sb.append(" hotfix=x");
            diag("hotfix hook failed " + t);
        }
        try {
            NoUpdate.hook(mod, cl);
            sb.append(" noupdate=ok");
        } catch (Throwable t) {
            sb.append(" noupdate=x");
            diag("noupdate hook failed " + t);
        }
        try {
            FanqiePopup.hook(mod);
            sb.append(" popup=ok");
        } catch (Throwable t) {
            sb.append(" popup=x");
            diag("popup hook failed " + t);
        }
        // 扫描组件（清单 + 按规则隐藏）：番茄/畅听共用同一套实现
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

    /**
     * 早期诊断。
     *
     * <p>为什么不用 {@link UiController#logFile}：{@code Fanqie.hook} 在
     * {@code onPackageReady} 里跑，比 Application.onCreate 还早，那时 appCtx 还没写进
     * UiController，logFile 会静默丢掉。这里直接往 App 私有目录写 —— 那个路径在任何时机
     * 都有写权限，root 也读得到。
     */
    private static void diag(String msg) {
        java.io.FileOutputStream fos = null;
        try {
            java.io.File f = new java.io.File(
                    "/data/data/com.dragon.read/files/rg_fq.txt");
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

    /**
     * 底部 tab：屏蔽「赚钱」。
     *
     * @return ok / absent / fail
     */
    private static String hookBottomTab(XposedModule mod, ClassLoader cl) {
        try {
            Class<?> main = RGModule.tryLoad(cl, CLS_MAIN);
            Class<?> type = RGModule.tryLoad(cl, CLS_TAB_TYPE);
            if (main == null || type == null) {
                UiController.logFile("fanqie: tab classes absent main=" + (main != null)
                        + " type=" + (type != null));
                return "absent";
            }
            Method c = main.getDeclaredMethod("c", ViewGroup.class, type);
            c.setAccessible(true);
            mod.hook(c)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (!Config.hideFanqieEarnTab(UiController.appCtx())) {
                            return chain.proceed();
                        }
                        Object t = chain.getArg(1);
                        String name = (t instanceof Enum) ? ((Enum<?>) t).name()
                                : String.valueOf(t);
                        if (TAB_EARN.equals(name)) {
                            UiController.logFile("fanqie: blocked bottom tab " + name);
                            return null;
                        }
                        return chain.proceed();
                    });
            return "ok";
        } catch (Throwable t) {
            UiController.logFile("fanqie: tab hook failed " + t);
            return "fail";
        }
    }

    /**
     * 「我的」页的功能入口格：答 false = 宿主不建这一格。
     *
     * <p>注意这是**全量**关闭（除了保留项由 {@link Config#fanqieMineKeep} 决定）。
     * 宿主对每个 cell 调一次，返回 false 时连视图都不创建，不会留空行。
     *
     * @return ok / absent / fail
     */
    private static String hookMineCells(XposedModule mod, ClassLoader cl) {
        try {
            Class<?> iface = RGModule.tryLoad(cl, CLS_CELL_IFACE);
            if (iface == null) {
                diag("cell: iface absent");
                return "absent";
            }
            // 实现是匿名类 IBsMineTabCellService$Companion$IMPL$1，挂在接口的静态字段
            // IMPL 上。匿名类名带 $Companion$IMPL$1，跨版本不算稳，所以顺着字段拿实例，
            // 再按方法名找 —— 只要宿主不改这套 SPI 结构就一直有效。
            java.lang.reflect.Field implField = iface.getDeclaredField("IMPL");
            implField.setAccessible(true);
            Object impl = implField.get(null);
            if (impl == null) {
                diag("cell: IMPL null");
                return "absent(impl)";
            }
            Class<?> implCls = impl.getClass();
            diag("cell: impl=" + implCls.getName());

            int n = 0;
            // 1) 功能格是否显示（视频创作 / 兴趣偏好 / 活动中心 … 都属于这一类）
            try {
                Method show = implCls.getMethod("isCellTypeShowable", int.class);
                show.setAccessible(true);
                mod.hook(show)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            int cell = (Integer) chain.getArg(0);
                            // 只记第一次遇到的每种编号，否则每次重组都会刷屏
                            if (sLoggedCells.add(cell)) {
                                diag("cell query " + cell);
                            }
                            if (!Config.hideFanqieMineCells(UiController.appCtx())) {
                                return chain.proceed();
                            }
                            if (Config.fanqieMineKeep(cell)) {
                                return chain.proceed();
                            }
                            return Boolean.FALSE;
                        });
                n++;
            } catch (Throwable t) {
                diag("cell: show hook failed " + t);
            }
            // 2) 「常玩游戏」那张卡：宿主要不要显示小游戏模块的总开关
            try {
                Method mini = implCls.getMethod("hideMiniGame");
                mini.setAccessible(true);
                mod.hook(mini)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            if (Config.hideFanqieMineCells(UiController.appCtx())) {
                                return Boolean.TRUE;
                            }
                            return chain.proceed();
                        });
                n++;
            } catch (Throwable t) {
                diag("cell: mini hook failed " + t);
            }
            diag("cell: hooks=" + n + "/2");
            return n == 2 ? "ok" : ("partial(" + n + "/2)");
        } catch (Throwable t) {
            diag("cell: failed " + t);
            return "fail";
        }
    }

    /* ---------------- 侧边栏（右上角 ☰ 打开的抽屉） ---------------- */

    /**
     * 要按文案隐藏的条目。
     *
     * <p>用户要求：头像、右上角设置、「我的消息」保留，其余全去。
     * 这里只列「要去掉」的 —— 保留项根本不需要动。
     */
    private static final String[] SIDEBAR_LINKS = {
            "个人信息清单", "三方信息共享清单",   // 侧边栏底部协议链接
            "我的订单", "浏览历史", "创作中心", "我的预约",  // 金刚位（「我的消息」要留，不列）
    };

    /**
     * 按文案隐藏侧边栏底部那两条协议链接。
     *
     * <p>为什么不 hook Dialog.show：实测抽屉**没有独立窗口**（{@code dumpsys window} 里
     * 只有一个 MainFragmentActivity 的 BASE_APPLICATION），它就在 Activity 的 decorView 里，
     * 但层次很深 —— {@code UiController.logTree} 的遍历有 12 层深度上限，抓不到。
     *
     * <p>所以改成拦文本设置：文案命中的瞬间把**整行**藏掉。比事后扫树稳，也不依赖
     * 任何混淆类名。{@code setText} 调用极频繁，这里先用长度（协议名 6~8 字）做粗筛，
     * 再 contains，开销可以忽略。
     */
    private static String hookTextFilter(XposedModule mod) {
        try {
            Method setText = TextView.class.getDeclaredMethod("setText", CharSequence.class);
            mod.hook(setText)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object r = chain.proceed();
                        try {
                            CharSequence t = (CharSequence) chain.getArg(0);
                            if (t != null && t.length() <= 16) {
                                String s = t.toString();
                                boolean hit = false;
                                for (String k : SIDEBAR_LINKS) {
                                    if (s.contains(k)) {
                                        hit = true;
                                        break;
                                    }
                                }
                                if (hit && Config.hideFanqieMineCells(UiController.appCtx())) {
                                    final View v = (View) chain.getThisObject();
                                    final String key = s;
                                    v.post(() -> {
                                        View row = rowOf(v);
                                        if (row != null && row.getVisibility() != View.GONE) {
                                            row.setVisibility(View.GONE);
                                            diag("hide sidebar link: " + key);
                                        }
                                    });
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return r;
                    });
            return "ok";
        } catch (Throwable t) {
            diag("text filter hook failed " + t);
            return "fail";
        }
    }

    /** 从文案节点向上找「一整行」的容器：宽度够大、高度只有一行。 */
    private static View rowOf(View tv) {
        View last = null;
        ViewParent p = tv.getParent();
        int guard = 0;
        int sw = tv.getResources().getDisplayMetrics().widthPixels;
        while (p instanceof View && guard++ < 6) {
            View pv = (View) p;
            if (pv.getWidth() > sw * 0.4f && pv.getHeight() > 0
                    && pv.getHeight() < Theme.dp(tv.getContext(), 140)) {
                return pv;
            }
            last = pv;
            p = pv.getParent();
        }
        return last;
    }

    /* ---------------- 长按底部「书城」标签打开面板 ---------------- */

    /** 长按判定的按住时长（毫秒）。 */
    private static final long LONG_PRESS_MS = 600;

    private static float sDownX;
    private static float sDownY;
    private static boolean sInZone;
    private static boolean sFired;
    private static Activity sAct;

    private static final Runnable FIRE = new Runnable() {
        @Override
        public void run() {
            if (sInZone && !sFired && sAct != null && !sAct.isFinishing()) {
                sFired = true;
                UiController.logFile("fanqie: long press bookstore tab -> panel");
                FanqiePanel.show(sAct);
            }
        }
    };

    /**
     * 挂 {@code Activity.dispatchTouchEvent}，识别「长按底部『书城』标签」。
     *
     * <p>为什么在 DOWN 时就 postDelayed、而不是等 UP 再判断时间差：长按判定要在手指
     * 仍按住时就弹面板，等松手才弹体验不对。MOVE 超出阈值就撤掉定时器（那是滑动不是长按）。
     *
     * <p>长按一旦触发，这次的 UP 必须吞掉 —— 否则抬指还会落到底栏上，切页跟着发生。
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
                            if (Hosts.isFanqie(a.getPackageName())) {
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
            UiController.logFile("fanqie: touch hook failed " + t);
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
                // 底栏 4 格等分：「书城」是最左一格（x 0~25%）。
                // 这里 x 放宽到 28%，y 取屏幕底部 14%（覆盖 tab 条与全面屏手势区上沿）；
                // 系统手势区里的按下不会传进 Activity，所以不需要设下界。
                sInZone = sDownX < w * 0.28f && sDownY > h * 0.86f;
                if (sInZone) {
                    try {
                        a.getWindow().getDecorView().postDelayed(FIRE, LONG_PRESS_MS);
                    } catch (Throwable ignored) {
                    }
                }
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                if (sInZone && !sFired) {
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
        sInZone = false;
        if (sAct != null) {
            try {
                sAct.getWindow().getDecorView().removeCallbacks(FIRE);
            } catch (Throwable ignored) {
            }
        }
        sAct = null;
    }
}
