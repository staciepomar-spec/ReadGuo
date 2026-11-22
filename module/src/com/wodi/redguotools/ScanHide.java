package com.wodi.redguotools;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 番茄小说 / 番茄畅听 的「扫描组件」：列清单 + 按规则隐藏、恢复。
 *
 * <h3>为什么不复用红果那套</h3>
 * 红果的 {@code UiController} 隐藏引擎跟宿主深度耦合（槽位 id、TAG_WEHID、清屏、
 * 界面透明度分层、暂停还原）。番茄与畅听连 Activity 级 hook 都没装，
 * 硬套只会互相干扰。但**扫描**本身是结构无关的（{@code UiController.scanBlocks}
 * 只做自顶向下的递归），所以扫描直接共用，隐藏这边只实现最小的一套：
 *
 * <pre>
 *   每 900ms 走一遍 decor → 命中规则的置 GONE（记进 sHidden）
 *   → 上一轮藏过但这轮不再命中的，恢复 VISIBLE
 * </pre>
 *
 * <p>为什么要循环：番茄/畅听都是列表流，页面上屏、翻页、下拉都会重建 View，
 * 一次性隐藏会在下一次 bind 后失效。900ms 是实测下「看不出延迟、又不至于太费」的折中。
 *
 * <p>用 {@link WeakHashMap} 记已隐藏的 View 而不是打 tag：宿主自己的适配器可能
 * 也会用 tag（含 {@code setTag(key, obj)}），抢了它的槽会出莫名其妙的复用错乱。
 */
final class ScanHide {

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final long GAP_MS = 900L;

    /** 被我们藏起来的 View → 隐藏前的 visibility（恢复时原样写回，不假设宿主原本是 VISIBLE）。 */
    private static final Map<View, Integer> sHidden = new WeakHashMap<>();

    private static WeakReference<Activity> sRef;
    private static boolean sLoop;

    private static final Runnable LOOP = new Runnable() {
        @Override
        public void run() {
            Activity a = sRef == null ? null : sRef.get();
            if (a == null || a.isFinishing()) {
                sLoop = false;
                return;
            }
            try {
                apply(a);
            } catch (Throwable t) {
                UiController.logFile("scanhide: apply failed " + t);
            }
            if (sLoop) {
                UI.postDelayed(this, GAP_MS);
            }
        }
    };

    private ScanHide() {
    }

    /** 供 Fanqie / Ct 分支安装：只关心这两个包名的 Activity 生命周期。 */
    static void hook(XposedModule mod) {
        try {
            Method resume = Activity.class.getDeclaredMethod("onResume");
            mod.hook(resume)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object r = chain.proceed();
                        Object self = chain.getThisObject();
                        if (self instanceof Activity && target((Activity) self)) {
                            start((Activity) self);
                        }
                        return r;
                    });
        } catch (Throwable t) {
            UiController.logFile("scanhide: resume hook failed " + t);
        }
        try {
            Method pause = Activity.class.getDeclaredMethod("onPause");
            mod.hook(pause)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object r = chain.proceed();
                        Object self = chain.getThisObject();
                        if (self instanceof Activity) {
                            stop((Activity) self);
                        }
                        return r;
                    });
        } catch (Throwable t) {
            UiController.logFile("scanhide: pause hook failed " + t);
        }
    }

    private static boolean target(Activity a) {
        String p = a.getPackageName();
        return Hosts.isFanqie(p) || Hosts.isCt(p);
    }

    /* ------------------------------------------------------------------ */
    /* 隐藏 / 恢复                                                          */
    /* ------------------------------------------------------------------ */

    static void start(Activity a) {
        sRef = new WeakReference<>(a);
        if (!sLoop) {
            sLoop = true;
            UI.post(LOOP);
        }
    }

    static void stop(Activity a) {
        if (sRef != null && sRef.get() == a) {
            sLoop = false;
            UI.removeCallbacks(LOOP);
        }
    }

    /** 规则改动后立刻生效一次（面板里勾完就点应用，不必等下一轮）。 */
    static void applyNow(Activity a) {
        try {
            apply(a);
        } catch (Throwable t) {
            UiController.logFile("scanhide: applyNow failed " + t);
        }
    }

    /** 返回当前页面上被隐藏的组件数。 */
    static int apply(Activity a) {
        if (a == null || a.getWindow() == null) {
            return 0;
        }
        View decor = a.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) {
            return 0;
        }
        List<String[]> rules = Config.overlayList(a);
        Set<View> hits = Collections.newSetFromMap(new IdentityHashMap<View, Boolean>());
        if (!rules.isEmpty()) {
            walk((ViewGroup) decor, rules, hits, 0);
        }
        // 上一轮藏过、这轮不再命中的：恢复。规则被删/取消勾选就走这条路。
        List<View> stale = new ArrayList<>(sHidden.keySet());
        for (View v : stale) {
            if (v != null && !hits.contains(v)) {
                Integer was = sHidden.remove(v);
                v.setVisibility(was == null ? View.VISIBLE : was);
            }
        }
        for (View v : hits) {
            if (v.getVisibility() != View.GONE) {
                sHidden.put(v, v.getVisibility());      // 记住原状态再藏
                v.setVisibility(View.GONE);
            }
        }
        return hits.size();
    }

    private static void walk(ViewGroup g, List<String[]> rules, Set<View> hits, int depth) {
        if (depth > 40) {
            return;
        }
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c == null) {
                continue;
            }
            if (c.getClass().getName().startsWith("com.wodi.redguotools.")) {
                continue;               // 自家注入的 View 不参与
            }
            // 已经藏起来的 View 尺寸会归零（GONE 后 layout 过），必须照样参与匹配，
            // 否则下一轮就判定"不再命中"把它放出来 —— 表现为一闪一闪。
            if (sHidden.containsKey(c) || c.getWidth() > 0 || c.getHeight() > 0) {
                for (String[] r : rules) {
                    if (r != null && r.length > 0 && matches(c, r[0])) {
                        hits.add(c);
                        break;
                    }
                }
            }
            if (c instanceof ViewGroup) {
                walk((ViewGroup) c, rules, hits, depth + 1);
            }
        }
    }

    /** 番茄这边只认这三种：类名 / 资源 id / 文案（红果专有的 pcls:/bar:/pkg: 不匹配）。 */
    private static boolean matches(View v, String raw) {
        if (raw == null) {
            return false;
        }
        String rule = raw.trim();
        if (rule.isEmpty()) {
            return false;
        }
        if (rule.startsWith("cls:")) {
            String s = rule.substring(4).trim();
            return s.length() >= 4 && v.getClass().getName().endsWith(s);
        }
        if (rule.startsWith("id:")) {
            String s = rule.substring(3).trim();
            return !s.isEmpty() && s.equals(idName(v));
        }
        String expr = rule.startsWith("text:") ? rule.substring(5)
                : (rule.startsWith("@") ? rule.substring(1) : rule);
        if (!(v instanceof TextView)) {
            return false;
        }
        CharSequence cs = ((TextView) v).getText();
        if (cs == null) {
            return false;
        }
        String txt = cs.toString();
        if (txt.isEmpty()) {
            return false;
        }
        for (String k : expr.split("\\|")) {
            k = k.trim();
            if (!k.isEmpty() && txt.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private static String idName(View v) {
        int id = v.getId();
        if (id == View.NO_ID) {
            return "";
        }
        try {
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return "";
        }
    }

    /* ------------------------------------------------------------------ */
    /* 面板：扫描清单                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * 扫当前页面 → 弹勾选清单 → 应用。
     *
     * <p>扫描本身复用 {@code UiController.scanBlocks}（纯结构遍历，与红果无关）；
     * 勾选状态以「清单里已有这条规则」为准，所以再次扫描能继续在旧清单上增删。
     *
     * @param onApplied 应用后的回调（面板用来刷新「已隐藏 N 项」）
     */
    static void scanDialog(final Activity a, final Runnable onApplied) {
        if (a == null || a.isFinishing()) {
            return;
        }
        final List<String[]> blocks;
        try {
            blocks = UiController.scanBlocks(a);
        } catch (Throwable t) {
            UiController.logFile("scanhide: scan failed " + t);
            Toast.makeText(a, "扫描失败：" + t, Toast.LENGTH_SHORT).show();
            return;
        }
        if (blocks.isEmpty()) {
            new AlertDialog.Builder(a)
                    .setTitle("扫描当前页面")
                    .setMessage("没有扫描到可单独隐藏的组件。\n（Compose 绘制的区域在这一层拿不到节点。）")
                    .setPositiveButton("好", null)
                    .show();
            return;
        }

        final List<String[]> existing = Config.overlayList(a);
        final String[] labels = new String[blocks.size()];
        final boolean[] checked = new boolean[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) {
            String[] b = blocks.get(i);
            String[] n = Names.describe(b[1] + " " + b[0]);
            labels[i] = n[0] + " " + n[1] + "\n" + b[0];
            checked[i] = contains(existing, b[1]);
        }

        new AlertDialog.Builder(a)
                .setTitle("扫描到 " + blocks.size() + " 个组件（勾选即隐藏）")
                .setMultiChoiceItems(labels, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setNegativeButton("取消", null)
                .setPositiveButton("应用", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        save(a, blocks, checked, existing);
                        applyNow(a);
                        if (onApplied != null) {
                            onApplied.run();
                        }
                    }
                })
                .show();
    }

    private static void save(Activity a, List<String[]> blocks, boolean[] checked,
                             List<String[]> existing) {
        List<String[]> out = new ArrayList<>();
        // 旧清单里这次没扫到的规则原样保留（比如别的页面藏的），别一应用就清空
        Set<String> scanned = new java.util.HashSet<>();
        for (String[] b : blocks) {
            scanned.add(b[1]);
        }
        for (String[] it : existing) {
            if (it != null && it.length >= 3 && !scanned.contains(it[0])) {
                out.add(it);
            }
        }
        for (int i = 0; i < blocks.size(); i++) {
            if (checked[i]) {
                String rule = blocks.get(i)[1];
                if (contains(out, rule)) {
                    continue;
                }
                out.add(new String[]{rule, "1", "100"});
            }
        }
        Config.setOverlayList(a, out);
        UiController.logFile("scanhide: applied " + out.size() + " rules");
    }

    private static boolean contains(List<String[]> list, String rule) {
        for (String[] it : list) {
            if (it != null && it.length > 0 && TextUtils.equals(it[0], rule)) {
                return true;
            }
        }
        return false;
    }

    /** 面板显示「已隐藏 N 项」用。 */
    static int count(Activity a) {
        try {
            return Config.overlayList(a).size();
        } catch (Throwable t) {
            return 0;
        }
    }

    static void clear(Activity a) {
        Config.setOverlayList(a, new ArrayList<String[]>());
        applyNow(a);
        UiController.logFile("scanhide: cleared");
    }
}
