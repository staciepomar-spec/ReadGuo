package com.wodi.redguotools;

import android.app.Activity;
import android.content.Context;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 模块自身的入口：MIUI 风格四页（主页 / 功能 / 设置 / 关于）。
 *
 * <p>v2.80 重做：视觉换成 MIUI 观感（大标题、圆角白卡、绿色激活卡、胶囊底栏），
 * 视觉层全部走 {@link MiUi}；新增「设置」页承载模块自身设置 ——
 * 隐藏桌面图标、运行日志查看。
 *
 * <p>界面仍然<b>纯代码构建</b>：模块没有 {@code res/} 布局，也不用 Gradle，
 * 所有图标由 {@link MiUi.Icon} 用 Canvas 现画。
 *
 * <p>系统栏处理：targetSdk 35 起强制 edge-to-edge，{@code setStatusBarColor} 被忽略，
 * 所以靠 {@code OnApplyWindowInsetsListener} 给根布局补上下内边距，
 * 否则内容会压在状态栏 / 小白条底下。
 */
public class MainActivity extends Activity {

    private static final String TAG = RGModule.TAG;
    private static final String AUTHOR = "残話/紫米米粥";
    private static final String GITHUB = "github.com/staciepomar-spec/RedGuo";

    /**
     * 宿主日志目录。三个渠道包名不同（改版 / Play 版 / 官方），
     * 这里按「哪个包真的产出过 rgtools_log.txt」来判定当前在用的宿主。
     * 目录读取本身要 root（模块进程读不到别的 App 的 Android/data）。
     */
    private static String mHostPkg;

    static String hostPkgResolved(Context c) {
        if (mHostPkg != null) {
            return mHostPkg;
        }
        for (String p : Hosts.PKGS) {
            RootShell.Result r = RootShell.run(
                    "test -f '/sdcard/Android/data/" + p + "/files/rgtools_log.txt' && echo Y");
            if (r.ok() && r.out != null && r.out.contains("Y")) {
                mHostPkg = p;
                return mHostPkg;
            }
        }
        for (String p : Hosts.PKGS) {
            try {
                if (c == null) {
                    continue;
                }
                c.getPackageManager().getPackageInfo(p, 0);
                mHostPkg = p;
                return mHostPkg;
            } catch (Throwable ignored) {
            }
        }
        mHostPkg = Hosts.DEFAULT;
        return mHostPkg;
    }

    static String hostLogDir(Context c) {
        return "/sdcard/Android/data/" + hostPkgResolved(c) + "/files";
    }

    /** 桌面图标的 LAUNCHER 载体（alias）。隐藏图标 = 禁 alias；MainActivity 本体
     *  挂 MODULE_SETTINGS category（LSPosed「打开」入口），永远保持 enabled。 */
    private static final String LAUNCHER_ALIAS =
            "com.wodi.redguotools.LauncherAlias";

    private MiUi.Palette p;
    private LinearLayout root;
    private View[] mPages;
    private View[] mTabs;
    private int mTab = 0;

    public MainActivity() {
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 模块 App 自己也会崩（设置页开关、root 调用…），同样要留下堆栈，
        // 否则「App 内闪退」依旧查不到。装在 super 之后、任何界面构建之前。
        CrashGuard.install(getApplicationContext());
        p = MiUi.pal(this);
        lightSystemBars();

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(p.bg);
        setContentView(root);
        applyInsets(root);

        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        mPages = new View[]{homePage(), featurePage(), aboutPage()};
        for (int i = 0; i < mPages.length; i++) {
            content.addView(mPages[i], new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            mPages[i].setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        }

        root.addView(bottomBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        select(0);
        Log.i(TAG, "MainActivity created");
    }

    /* ==================== 系统栏 ==================== */

    @SuppressWarnings("deprecation")
    private void lightSystemBars() {
        Window w = getWindow();
        if (w == null) {
            return;
        }
        int flags = w.getDecorView().getSystemUiVisibility()
                | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        w.getDecorView().setSystemUiVisibility(flags);
    }

    /** edge-to-edge 下用内边距把内容让开系统栏（API 29 与 30+ 两套取值）。 */
    private void applyInsets(final View v) {
        v.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                int top;
                int bottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    top = bars.top;
                    bottom = bars.bottom;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                view.setPadding(view.getPaddingLeft(), top, view.getPaddingRight(), bottom);
                return insets;
            }
        });
        v.requestApplyInsets();
    }

    /* ==================== 骨架 ==================== */

    /** 页面外壳：Large Title + 可选右上角动作按钮 + 滚动内容列。 */
    private LinearLayout page(String title, int rightIcon, Runnable rightAction) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        FrameLayout bar = new FrameLayout(this);
        int h = dp(64);
        bar.setPadding(dp(16), dp(4), dp(8), dp(4));
        col.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h));

        TextView tv = MiUi.bigTitle(this, p, title);
        bar.addView(tv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START | Gravity.CENTER_VERTICAL));

        if (rightIcon >= 0) {
            FrameLayout btn = new FrameLayout(this);
            btn.setBackground(MiUi.pressable(this, p, 22));
            MiUi.Icon ic = new MiUi.Icon(this);
            ic.setType(rightIcon);
            ic.setColor(p.title);
            FrameLayout.LayoutParams icLp = new FrameLayout.LayoutParams(dp(22), dp(22));
            icLp.gravity = Gravity.CENTER;
            btn.addView(ic, icLp);
            FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(dp(44), dp(44));
            bLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            bLp.rightMargin = dp(4);
            bar.addView(btn, bLp);
            final Runnable act = rightAction;
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (act != null) {
                        act.run();
                    }
                }
            });
        }

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        sv.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        col.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), 0, dp(16), dp(28));
        sv.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        holder.put(col, body);
        return col;
    }

    /** 页面外壳 → 该页的滚动内容列（构建页时记下，方便调用方往里塞卡片）。 */
    private final java.util.Map<View, View> holder = new java.util.HashMap<>();

    /** iOS 底部标签栏：白底 + 顶部 1px 分割线，选中项只做蓝色高亮。 */
    private View bottomBar() {
        FrameLayout wrap = new FrameLayout(this);

        View line = new View(this);
        line.setBackgroundColor(p.divider);
        wrap.addView(line, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, MiUi.hairline(this)));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(p.card);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = MiUi.hairline(this);
        bar.setPadding(0, dp(6), 0, dp(4));
        wrap.addView(bar, blp);

        // v3.07 起只保留三页：所有设置都在红果内的模块面板里改。
        // 模块 App 原先也有一个「设置」页，但它要把配置送到红果只能靠公共目录下的
        // 一个文件，而那条通道在 Android 11+ 已经死了（详见 CfgBridge 被移除的说明），
        // 留着只会让人以为改了就生效 —— 一个静默失效的入口比没有更糟。
        String[] names = {"主页", "功能", "关于"};
        int[] icons = {MiUi.I_HOME, MiUi.I_SLIDERS, MiUi.I_INFO};
        mTabs = new View[names.length];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            FrameLayout cell = new FrameLayout(this);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    select(idx);
                }
            });

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            // 左右留出 padding，选中时这块内缩的矩形才会形成 iOS 那种胶囊
            item.setPadding(dp(16), dp(3), dp(16), dp(3));

            MiUi.Icon ic = new MiUi.Icon(this);
            ic.setType(icons[i]);
            ic.setColor(p.sub);
            item.addView(ic, new LinearLayout.LayoutParams(dp(25), dp(25)));

            TextView label = new TextView(this);
            label.setText(names[i]);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            label.setTextColor(p.sub);
            label.setGravity(Gravity.CENTER);
            label.setIncludeFontPadding(false);
            label.setPadding(0, dp(2), 0, 0);
            item.addView(label);

            cell.addView(item, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            cell.setLayoutParams(lp);
            bar.addView(cell);

            item.setTag(new Object[]{ic, label});
            cell.setTag(item);
            mTabs[i] = cell;
        }
        return wrap;
    }

    private void select(int idx) {
        mTab = idx;
        for (int i = 0; i < mPages.length; i++) {
            mPages[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
        }
        for (int i = 0; i < mTabs.length; i++) {
            View inner = (View) mTabs[i].getTag();
            MiUi.Icon ic = (MiUi.Icon) ((Object[]) inner.getTag())[0];
            TextView label = (TextView) ((Object[]) inner.getTag())[1];
            boolean on = i == idx;
            ic.setColor(on ? p.blue : p.sub);
            label.setTextColor(on ? p.blue : p.sub);
            // iOS 18 那种选中胶囊：淡蓝底 + 大圆角，只包住图标和文字这一小块，
            // 不是把整格铺满 —— 铺满会变成 Android 老式的「选中变底色」。
            if (on) {
                inner.setBackground(MiUi.round(0x1F007AFF, 14, this));
            } else {
                inner.setBackground(null);
            }
        }
    }

    /* ==================== 主页 ==================== */

    /**
     * 三个适配应用：包名、显示名、面板入口说明、图标。
     *
     * <p>番茄畅听还没做，但位置先占上 —— 主页和功能页都按这张表渲染，
     * 以后接畅听时只要把它的规则补上、把 {@link #suported(String)} 改成 true 即可，
     * 不用再动 UI。
     */
    private static final String[][] APPS = {
            {"com.phoenix.read", "红果免费短剧", "长按底部「首页」标签唤出面板"},
            {"com.dragon.read", "番茄免费小说", "长按底部「书城」标签唤出面板"},
            {"com.xs.fm", "番茄畅听", "长按底部「首页」或顶部中央唤出面板"},
    };

    /** 三个宿主都已接入实际功能。 */
    private static boolean suported(String pkg) {
        return true;
    }

    /** 已安装则返回 versionName，否则 null。 */
    private String installedVersion(String pkg) {
        try {
            return getPackageManager().getPackageInfo(pkg, 0).versionName;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 一个应用在主页里的副标题：版本 + 状态。 */
    private String appSub(String pkg) {
        String ver = installedVersion(pkg);
        if (ver == null) {
            return "未安装";
        }
        return "v" + ver + (suported(pkg) ? " · 已适配" : " · 暂未支持");
    }

    private View homePage() {
        LinearLayout col = page("主页", MiUi.I_REFRESH, new Runnable() {
            @Override
            public void run() {
                showRestartMenu();
            }
        });
        LinearLayout body = (LinearLayout) holder.get(col);
        buildHome(body);
        return col;
    }

    /**
     * 右上角按钮：弹菜单选一个应用重启。
     *
     * <p>原来这里是无条件重启红果。现在适配了三个应用，各家的面板入口和生效方式
     * 都不一样，需要哪个重启哪个，所以先让用户选。
     */
    /**
     * 右上角按钮：底部弹出 ActionSheet，选一个应用重启。
     *
     * <p>原来是系统的 AlertDialog（居中、直角、白底黑字），跟整页 iOS 风格完全不搭。
     * 改成底部弹层：圆角卡 + 居中蓝色选项 + 独立的「取消」块，左右各留 10dp。
     */
    private void showRestartMenu() {
        final java.util.List<String> pkgs = new java.util.ArrayList<>();
        final java.util.List<String> labels = new java.util.ArrayList<>();
        for (String[] e : APPS) {
            if (installedVersion(e[0]) != null) {
                pkgs.add(e[0]);
                labels.add(e[1]);
            }
        }
        if (pkgs.isEmpty()) {
            android.widget.Toast.makeText(this, "没有已安装的适配应用",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        final Dialog d = new Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), 0, dp(10), dp(10));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(MiUi.round(p.card, 16, this));

        TextView title = new TextView(this);
        title.setText("重启哪个应用");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTextColor(p.sub);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(14), 0, dp(12));
        card.addView(title);

        for (int i = 0; i < labels.size(); i++) {
            final int idx = i;
            MiUi.divider(this, p, card);
            TextView row = new TextView(this);
            row.setText(labels.get(i));
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            row.setTextColor(p.blue);
            row.setGravity(Gravity.CENTER);
            row.setPadding(0, dp(16), 0, dp(16));
            row.setBackground(MiUi.pressable(this, p, 0));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    d.dismiss();
                    restartApp(MainActivity.this, pkgs.get(idx), labels.get(idx));
                }
            });
            card.addView(row);
        }
        root.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        cancel.setTextColor(p.blue);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, dp(16), 0, dp(16));
        cancel.setBackground(MiUi.pressable(this, p, 16));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(8);
        root.addView(cancel, clp);

        d.setContentView(root);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(0));
            d.getWindow().setGravity(Gravity.BOTTOM);
            d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
    }

    /**
     * 强制停止并重新拉起指定应用（需 root；无 root 时只强停）。
     *
     * <p>先 force-stop 再 monkey 拉起，和红果那套重启流程一致 ——
     * Xposed 的 hook 是进程启动时装上的，不重启进程改了配置也不生效。
     */
    static void restartApp(final Activity a, final String pkg, final String label) {
        if (a == null) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                RootShell.run("am force-stop " + pkg);
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ignored) {
                }
                RootShell.run("monkey -p " + pkg
                        + " -c android.intent.category.LAUNCHER 1");
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        android.widget.Toast.makeText(a, "已重启" + label,
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "rg-restart-app").start();
    }

    /**
     * 右上角按钮：强制停止红果并重新拉起（需 root；无 root 时退化为仅强停）。
     *
     * <p>三个渠道包名可能同时装在同一台机器上（改版 / Play 版 / 官方），
     * 全部停掉以免残留进程占用；只重新拉起**当前在用**的那个
     * （判定见 {@link #hostPkgResolved(a)}）。
     */
    private void restartHost() {
        restartHostFrom(this);
    }

    /**
     * 强制停止红果并重新拉起（需 root；无 root 时退化为仅强停）。
     *
     * <p>做成静态是为了给模块内的设置页复用 —— 配置改完点一下就能重启，
     * 不用切回主页。
     */
    static void restartHostFrom(final Activity a) {
        if (a == null) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                String primary = hostPkgResolved(a);
                int n = 0;
                for (String p : Hosts.PKGS) {
                    try {
                        a.getPackageManager().getPackageInfo(p, 0);
                    } catch (Throwable ignored) {
                        continue;   // 没装就跳过
                    }
                    RootShell.run("am force-stop " + p);
                    n++;
                }
                final String msg;
                if (n == 0) {
                    msg = "未找到已安装的应用";
                } else {
                    RootShell.run("monkey -p " + primary
                            + " -c android.intent.category.LAUNCHER 1");
                    msg = "已重启（" + primary + "）";
                }
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        android.widget.Toast.makeText(a, msg,
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "rg-restart").start();
    }

    /**
     * 主页只列「适配了哪些应用」——用户明确要求别的都不放。
     *
     * <p>原来这一页有状态卡、在哪改设置、设备与版本、捐赠入口。改版后：
     * 应用清单提到第一位（这是用户唯一关心的信息），
     * 设置入口挪到功能页每个应用的详情里，设备信息整个去掉（对使用者没价值）。
     */
    private void buildHome(LinearLayout body) {
        LinearLayout apps = MiUi.group(this, p, body, "适配应用");
        for (int i = 0; i < APPS.length; i++) {
            if (i > 0) {
                MiUi.divider(this, p, apps);
            }
            String pkg = APPS[i][0];
            MiUi.row(this, p, apps, APPS[i][1], appSub(pkg),
                    installedVersion(pkg) == null ? MiUi.I_INFO : MiUi.I_APPS);
        }

        TextView hint = MiUi.footer(this, p,
                "在 LSPosed 中启用本模块，并把上面用到的应用勾进作用域。"
                        + "改完点右上角按钮重启对应应用，hook 才会重新挂上。");
        hint.setPadding(MiUi.dp(this, 4), MiUi.dp(this, 10),
                MiUi.dp(this, 4), MiUi.dp(this, 4));
        body.addView(hint);

        /* ---------- 设备与版本 ---------- */
        LinearLayout dev = MiUi.group(this, p, body, "设备与版本");
        valueRow(dev, "设备", Build.MODEL);
        MiUi.divider(this, p, dev);
        valueRow(dev, "型号", Build.MANUFACTURER + " " + Build.DEVICE);
        MiUi.divider(this, p, dev);
        valueRow(dev, "系统", hyperOsVersion());
        MiUi.divider(this, p, dev);
        valueRow(dev, "Android", Build.VERSION.RELEASE);
        MiUi.divider(this, p, dev);
        valueRow(dev, "模块版本", versionName() + " (" + versionCode() + ")");

        /* ---------- 支持 ---------- */
        LinearLayout open = MiUi.group(this, p, body, null);
        LinearLayout dl = MiUi.row(this, p, open, "捐赠支持", null, MiUi.I_HEART);
        dl.addView(MiUi.chevron(this, p));
        dl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDonate();
            }
        });
    }

    /**
     * iOS 标准 label / value 行：左标签、右取值（灰色、单行、过长截断）。
     * 不加图标 —— 设备信息这类纯文本项在原生设置里本来就没有图标列，
     * 强行加图标反而会让整页密度失衡。
     */
    private void valueRow(LinearLayout card, String label, String value) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(dp(52));
        line.setPadding(dp(16), 0, dp(16), 0);

        TextView l = MiUi.rowTitle(this, p, label);
        line.addView(l, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = MiUi.value(this, p, value);
        v.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        v.setMaxLines(1);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.leftMargin = dp(12);
        line.addView(v, vlp);

        card.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 捐赠弹窗：支付宝 / 微信收款码（assets 内置图），纵向可滚。 */
    private void showDonate() {
        Dialog dlg = new Dialog(this);
        ScrollView sc = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(18);
        col.setPadding(pad, pad, pad, pad);
        col.setBackground(MiUi.round(p.card, 16, this));

        TextView tip = new TextView(this);
        tip.setText("扫码请作者喝杯咖啡");
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tip.setTextColor(p.title);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, 0, 0, dp(10));
        col.addView(tip);

        String[][] items = {
                {"donate_alipay.jpg", "支付宝"},
                {"donate_wechat.jpg", "微信"},
        };
        for (String[] it : items) {
            TextView label = new TextView(this);
            label.setText(it[1]);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            label.setTextColor(p.sub);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(8), 0, dp(6));
            col.addView(label);

            ImageView iv = new ImageView(this);
            try {
                java.io.InputStream is = getAssets().open(it[0]);
                android.graphics.Bitmap bmp =
                        android.graphics.BitmapFactory.decodeStream(is);
                is.close();
                iv.setImageBitmap(bmp);
            } catch (Throwable t) {
                Log.w(TAG, "load donate image failed: " + it[0], t);
                continue;
            }
            int w = dp(280);
            iv.setAdjustViewBounds(true);
            col.addView(iv, new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        Button close = new Button(this);
        close.setText("关闭");
        close.setTextColor(p.blue);
        close.setBackgroundColor(0x00000000);
        close.setPadding(0, dp(10), 0, 0);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
            }
        });
        col.addView(close);

        sc.addView(col);
        dlg.setContentView(sc);
        android.view.Window win = dlg.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            win.setLayout(dp(340), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dlg.show();
    }

    /* ==================== 功能页 ==================== */

    private View featurePage() {
        LinearLayout col = page("功能", -1, null);
        LinearLayout body = (LinearLayout) holder.get(col);

        LinearLayout list = MiUi.group(this, p, body, "按应用查看");
        for (int i = 0; i < APPS.length; i++) {
            if (i > 0) {
                MiUi.divider(this, p, list);
            }
            final int idx = i;
            LinearLayout row = MiUi.row(this, p, list, APPS[i][1], APPS[i][2], MiUi.I_APPS);
            row.addView(MiUi.chevron(this, p));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showAppDetail(idx);
                }
            });
        }
        TextView hint = MiUi.footer(this, p, "点任意一项查看该应用的具体功能与面板入口。");
        hint.setPadding(MiUi.dp(this, 4), MiUi.dp(this, 10),
                MiUi.dp(this, 4), MiUi.dp(this, 4));
        body.addView(hint);

        /* ---------- 重置：清空某应用里本模块保存的配置并重启它（需 root） ---------- */
        LinearLayout reset = MiUi.group(this, p, body, "重置");
        for (int i = 0; i < APPS.length; i++) {
            if (i > 0) {
                MiUi.divider(this, p, reset);
            }
            final String pkg = APPS[i][0];
            final String name = APPS[i][1];
            boolean installed = installedVersion(pkg) != null;
            LinearLayout row = MiUi.row(this, p, reset, name,
                    installed ? "清空本模块在该应用里保存的配置并重启它（需 root）" : "未安装",
                    MiUi.I_APPS);
            row.addView(dangerBtn("重置", installed, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmReset(java.util.Collections.singletonList(pkg),
                            "将清空本模块在" + name + "里保存的全部配置并恢复默认，"
                                    + "然后重启" + name + "一次。\n\n此操作不可撤销。");
                }
            }));
        }
        MiUi.divider(this, p, reset);
        TextView allBtn = dangerBtn("重置所有模块设置", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                java.util.List<String> pkgs = new java.util.ArrayList<>();
                for (String[] e : APPS) {
                    if (installedVersion(e[0]) != null) {
                        pkgs.add(e[0]);
                    }
                }
                if (pkgs.isEmpty()) {
                    toast("没有已安装的适配应用");
                    return;
                }
                confirmReset(pkgs,
                        "将清空本模块在全部应用里保存的配置并恢复默认，"
                                + "然后重启各应用一次。\n\n此操作不可撤销。");
            }
        });
        reset.addView(allBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView resetHint = MiUi.footer(this, p,
                "配置保存在各应用自己的空间里，重置只影响所选应用。需要 root。");
        resetHint.setPadding(MiUi.dp(this, 4), MiUi.dp(this, 10),
                MiUi.dp(this, 4), MiUi.dp(this, 4));
        body.addView(resetHint);
        return col;
    }

    /** 危险色（iOS 红）：MiUi 调色板没有危险色，这里补一个给重置类动作用。 */
    private static final int DANGER = 0xFFFF3B30;

    /** 危险色文字按钮：正常红字，未安装时置灰禁用。 */
    private TextView dangerBtn(String text, boolean enabled, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setTextColor(enabled ? DANGER : p.hint);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        t.setBackground(MiUi.pressable(this, p, 10));
        t.setEnabled(enabled);
        if (enabled && l != null) {
            t.setOnClickListener(l);
        }
        return t;
    }

    /** 重置确认框：确认后清空所选应用里本模块的配置并重启（需 root）。 */
    private void confirmReset(final java.util.List<String> pkgs, String message) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("确认重置")
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("重置", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        doReset(pkgs);
                    }
                })
                .show();
    }

    /**
     * 后台执行重置（需 root）：逐个目标包强停 → 清掉模块配置（含 .bak）→ 重新拉起。
     * 配置在各应用自己的 shared_prefs 里，模块进程写不了别人的私有目录，只能走 su。
     */
    private void doReset(final java.util.List<String> pkgs) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = true;
                for (final String pkg : pkgs) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            toast("已清空模块配置，正在重启" + appName(pkg));
                        }
                    });
                    if (!RootShell.run(resetShell(pkg)).ok()) {
                        ok = false;
                    }
                }
                if (!ok) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            toast("重置失败：请确认已授予 root 权限");
                        }
                    });
                }
            }
        }, "rg-reset").start();
    }

    /** 重置一个目标包的 shell：强停 → 清配置（含 .bak，user / user_de 两处）→ 重新拉起。 */
    private static String resetShell(String pkg) {
        StringBuilder sb = new StringBuilder("am force-stop " + pkg);
        for (String f : new String[]{"rg_tools_cfg.xml", "rg_confirm_state.xml"}) {
            for (String dir : new String[]{"/data/user/0/", "/data/user_de/0/"}) {
                sb.append("; rm -f ").append(dir).append(pkg)
                        .append("/shared_prefs/").append(f);
                sb.append("; rm -f ").append(dir).append(pkg)
                        .append("/shared_prefs/").append(f).append(".bak");
            }
        }
        sb.append("; monkey -p ").append(pkg)
                .append(" -c android.intent.category.LAUNCHER 1");
        return sb.toString();
    }

    /** 包名 → 显示名（重置提示里用）。 */
    private static String appName(String pkg) {
        for (String[] e : APPS) {
            if (e[0].equals(pkg)) {
                return e[1];
            }
        }
        return pkg;
    }

    /** 某个应用的详细功能：全屏弹层，排版复用 featureCard。 */
    private void showAppDetail(int idx) {
        final String pkg = APPS[idx][0];
        String name = APPS[idx][1];

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(p.bg);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(8), dp(16), dp(8));
        bar.setMinimumHeight(dp(56));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        back.setTextColor(p.title);
        back.setPadding(dp(12), 0, dp(12), 0);
        bar.addView(back);

        TextView title = MiUi.rowTitle(this, p, name);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        bar.addView(title);
        root.addView(bar);

        final ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, dp(16));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        fillAppDetail(body, pkg);

        /* 全屏 Dialog 也要自己补系统栏内边距。
         *
         * 这个模块 App 是 targetSdk 35，系统强制 edge-to-edge，`setStatusBarColor`
         * 已经不起作用 —— 不补内边距的话标题会被状态栏压住，而且窗口顶部会露出
         * 主题自带的那条深灰（就是「右上角一块颜色不对」的来源）。
         * 做法和主页面一致：让内容延伸进系统栏，再用 insets 把标题行往下推。 */
        final LinearLayout fbar = bar;
        final int barPadTop = dp(8);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                int top;
                int bottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    top = bars.top;
                    bottom = bars.bottom;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                fbar.setPadding(fbar.getPaddingLeft(), top + barPadTop,
                        fbar.getPaddingRight(), barPadTop);
                sv.setPadding(0, 0, 0, bottom);
                return insets;
            }
        });

        final Dialog d = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(p.bg));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            if (Build.VERSION.SDK_INT >= 30) {
                w.setDecorFitsSystemWindows(false);
            }
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
            // 状态栏变透明后，图标默认是浅色（白），在浅底上几乎看不见 ——
            // 按当前配色亮度切成深色图标。深色主题下保持浅色。
            boolean lightBg = brightness(p.bg) > 128;
            if (Build.VERSION.SDK_INT >= 30 && w.getInsetsController() != null) {
                int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
                w.getInsetsController().setSystemBarsAppearance(lightBg ? mask : 0, mask);
            } else if (Build.VERSION.SDK_INT >= 23) {
                w.getDecorView().setSystemUiVisibility(lightBg
                        ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0);
            }
        }
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        d.show();
        root.requestApplyInsets();
    }

    /**
     * 按包名填该应用的详细功能。
     *
     * <p>v3.21 起三个应用统一成同一套分组（同名、同顺序、同排版），按功能归组，
     * 不再按「已实现 / 暂未实现」分：界面显示 / 播放与交互 / 净化 / 组件隐藏 /
     * 面板入口 / 已知限制 —— 某应用没有该类功能就不建这张卡。
     */
    private void fillAppDetail(LinearLayout body, String pkg) {
        if ("com.phoenix.read".equals(pkg)) {
            featureCard(body, "界面显示",
                    "状态栏：隐藏系统状态栏（不再自绘底色，藏掉后露出的是宿主自己的界面）",
                    "底部标签栏：按标签文字勾选隐藏，剩余标签自动摊满整行",
                    "底部导航栏：软件内全程隐藏小白条，退出软件才恢复",
                    "界面透明度：首页/二级页上视频以外的浮层统一调透明，重置才还原");
            featureCard(body, "播放与交互",
                    "默认最高画质：起播自动选最高可用档",
                    "自定义倍速：1.0 ~ 2.0 倍无级调节（0.1 步进），不依赖宿主档位",
                    "倍速上限 2.0x：native 引擎把超过 2.0 的请求降档，官方面板的 3.0x 实测约 1.5x",
                    "定时关闭：到点停播并直接熄屏（需给 ReadGuo 授予 root）",
                    "双击屏幕：直接打开评论区（横屏三击），可吞掉第二次点击避免误触点赞",
                    "清屏省心：清屏后 10 秒不碰屏幕自动收起其余控件，触摸立即恢复");
            featureCard(body, "净化",
                    "净化：屏蔽「发现新版本」升级弹窗、广告位、福袋挂件");
            featureCard(body, "组件隐藏",
                    "组件显隐：12 个常用组件（顶栏 / 右侧互动 / 底部条 / 标题 / 热评 / 进度条）逐项开关",
                    "自定义隐藏清单：「扫描当前页面」自动列出，或手写规则（cls: / @文案 / text: / id:）",
                    "悬浮球点选：球拖到哪、点哪个控件就藏哪个，支持上一级 / 下一级 / 列表模式 / 编辑模式（多步改动保存时一次确认）");
            featureCard(body, "面板入口",
                    "长按底部「首页」标签直接唤出面板",
                    "播放页：右上角 ⋮ → 播放设置 → 最下方「ReadGuo 设置」");
        } else if ("com.dragon.read".equals(pkg)) {
            featureCard(body, "界面显示",
                    "屏蔽底部「赚钱」：让宿主的 tab 工厂返回 null，底栏不留空位",
                    "精简「我的」页右侧边栏：收起右上角 ☰ 抽屉里的订单 / 浏览历史 / 创作中心 / 预约与协议链接");
            featureCard(body, "组件隐藏",
                    "悬浮球点选：球拖到哪、点哪个控件就藏哪个，支持上一级 / 下一级 / 列表模式 / 编辑模式（多步改动保存时一次确认）",
                    "扫描组件：自动列出当前页面可隐藏的组件，一键隐藏",
                    "已隐藏组件：逐条列出当前隐藏项，可单独点「恢复」，也可一键全部恢复");
            featureCard(body, "面板入口",
                    "长按底部「书城」标签约 0.6 秒，弹出「番茄精简」面板",
                    "面板里的开关改完需重启番茄生效");
            featureCard(body, "已知限制",
                    "金刚位那排（我的订单 / 浏览历史 / 我的消息 / 创作中心 / 我的预约）：整页是 Compose 渲染，View 层拿不到节点，需要 hook 卡片分发器，混淆名每版都变");
        } else if ("com.xs.fm".equals(pkg)) {
            featureCard(body, "界面显示",
                    "底栏：只留「首页」「我的」，商城 / 听歌 / 领现金三格隐藏，剩余两格平分整行",
                    "精简「我的」页：去掉金刚位（消息 / 优惠券 / 购物车 / 订单 / 游戏）与「我的资产」两块",
                    "去掉四宫格：隐藏右上角的「全部功能」入口，红点一并消失");
            featureCard(body, "净化",
                    "广告全关：广告位判定恒 false，开屏 / 首页信息流 / 听书贴片一并拦下",
                    "启动弹窗：进程起来后 10 秒内的活动 / 签到 / 福利浮层直接关闭");
            featureCard(body, "组件隐藏",
                    "悬浮球点选：球拖到哪、点哪个控件就藏哪个，支持上一级 / 下一级 / 列表模式 / 编辑模式（多步改动保存时一次确认）",
                    "扫描组件：自动列出当前页面可隐藏的组件，一键隐藏",
                    "已隐藏组件：逐条列出当前隐藏项，可单独点「恢复」，也可一键全部恢复");
            featureCard(body, "面板入口",
                    "长按底部「首页」格（左侧 1/3）约 0.6 秒",
                    "二级页没有底栏，长按顶部中央同样能唤出");
            featureCard(body, "已知限制",
                    "6.7.8 是另一代代码：暗色、DPI、界面透明度、播放器倍速、定时熄屏这些在红果 7.x 上的锚点，在畅听里都不存在，需要逐个重新逆向",
                    "「全部功能」页本身没清空 —— 入口已经拿掉，页面留着不影响使用");
        } else {
            featureCard(body, "番茄畅听",
                    "状态：暂未支持");
        }
    }

    /**
     * 一个分组：分组标题（卡外）+ 白卡，卡内每条要点一行，行间一条缩进分割线。
     *
     * <p>v3.13 起改成两行结构：原文案都是「标题：说明」的长段落，15sp 一大坨挤在一起
     * 完全看不出层次。这里按第一个全角冒号拆开 —— 冒号前当行标题（17sp 正文色），
     * 冒号后当副标题（13sp 次要色）。没有冒号或冒号太靠后的（说明标题本身很长），
     * 保持单行标题。
     */
    private void featureCard(LinearLayout body, String title, String... points) {
        LinearLayout card = MiUi.group(this, p, body, title);
        for (int i = 0; i < points.length; i++) {
            if (i > 0) {
                MiUi.divider(this, p, card);
            }
            String raw = points[i];
            int idx = raw.indexOf('：');
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            if (idx > 0 && idx <= 12) {
                row.addView(MiUi.rowTitle(this, p, raw.substring(0, idx)));
                TextView sub = MiUi.rowSub(this, p, raw.substring(idx + 1));
                sub.setPadding(0, dp(3), 0, 0);
                sub.setLineSpacing(dp(2), 1f);
                row.addView(sub);
            } else {
                row.addView(MiUi.rowTitle(this, p, raw));
            }
            card.addView(row);
        }
    }

    /* ==================== 设置页（v3.07 已移除） ==================== */
    /*
     * 原先这里挂 ModuleSettings.build()。
     * 删除原因见 bottomBar() 处的注释：那条配置通道在 Android 11+ 已经死了，
     * 页面留着只会让用户以为「改了就生效」。
     */

    /** 卡片内一行开关：标题 + 副标题 + 右侧开关（行高与普通列表行一致）。 */
    private void switchRow(LinearLayout card, String title, String sub, boolean on,
                           final MiUi.OnToggle l) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(dp(sub == null || sub.isEmpty() ? 52 : 62));
        line.setPadding(dp(16), 0, dp(16), 0);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        texts.addView(MiUi.rowTitle(this, p, title));
        if (sub != null && !sub.isEmpty()) {
            TextView s = MiUi.rowSub(this, p, sub);
            s.setPadding(0, dp(2), 0, 0);
            texts.addView(s);
        }
        line.addView(texts, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final MiUi.MiSwitch sw = new MiUi.MiSwitch(this, p, on);
        sw.setTag(l);
        line.addView(sw);
        card.addView(line);
    }

    /**
     * 行副标题。
     *
     * <p><b>不能在这里报文件大小</b>：日志在红果的私有目录下，普通应用连 stat
     * 都被SELinux 挡掉（{@code File.exists()} 恒 false），只有走 root 才读得到。
     * 所以副标题只给文件名，大小/时间放进对话框里用 root 读。
     */
    private String logDesc(String name) {
        return name;
    }

    /* ---- 桌面图标显隐 ---- */

    /**
     * 桌面图标挂在 {@code LauncherAlias} 上（见 AndroidManifest），隐藏时禁alias、
     * {@code MainActivity} 本体保持 enabled —— 否则 LSPosed 管理器按组件名启动模块时
     * 会一起失效（v2.80 初版踩过：禁 MainActivity 后 LSPosed 里点不进模块）。
     */
    /**
     * 桌面图标与 LSPosed「打开」入口分离（v2.89）：
     * 桌面图标挂在 alias（LAUNCHER）上，「隐藏桌面图标」只禁 alias；
     * MainActivity 本体挂 {@code de.robv.android.xposed.category.MODULE_SETTINGS}
     * （LSPosed 管理器 AppHelper.getSettingsIntent 的首选入口），永远 enabled ——
     * 隐藏图标后 LSPosed 模块列表的「打开」照常可用。
     * （v2.84~2.88 曾禁本体：实测 LSPosed FAB 消失 + 系统直接 force stop 进程。）
     */
    private ComponentName launcherAlias() {
        return new ComponentName(this, LAUNCHER_ALIAS);
    }

    private boolean iconHidden() {
        try {
            return getPackageManager().getComponentEnabledSetting(launcherAlias())
                    == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void applyIconHidden(final boolean hide) {
        if (hide) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("隐藏桌面图标？")
                    .setMessage("桌面将不再显示 ReadGuo。\n"
                            + "随时可从 LSPosed 模块列表的「打开」"
                            + "进入本设置页恢复。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("隐藏", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            doSetIcon(false);
                            toast(iconHidden() ? "已隐藏桌面图标，可在设置页恢复" : "隐藏失败");
                        }
                    })
                    .show();
            return;
        }
        doSetIcon(true);
        toast(iconHidden() ? "恢复失败" : "已恢复桌面图标");
    }

    private void doSetIcon(boolean visible) {
        try {
            getPackageManager().setComponentEnabledSetting(
                    launcherAlias(),
                    visible ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Throwable t) {
            Log.w(TAG, "setComponentEnabledSetting failed", t);
            toast("系统拒绝了图标显隐：" + t.getMessage());
        }
    }

    /* ---- 日志查看 ---- */

    private void showLog(final String name) {
        final Dialog dlg = new Dialog(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackground(MiUi.round(p.card, 16, this));

        TextView title = new TextView(this);
        title.setText(name);
        title.setTextColor(p.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(18), dp(16), dp(18), dp(2));
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText(hostLogDir(this) + "/" + name);
        sub.setTextColor(p.hint);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        sub.setPadding(dp(18), 0, dp(18), dp(8));
        col.addView(sub);

        final TextView body = new TextView(this);
        body.setTextColor(p.sub);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        body.setTypeface(Typeface.MONOSPACE);
        body.setPadding(dp(18), 0, dp(18), dp(8));
        body.setText("正在读取…");

        ScrollView sv = new ScrollView(this);
        sv.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        btns.setPadding(dp(12), 0, dp(12), dp(12));
        TextView again = MiUi.ghostButton(this, p, "重新读取", null);
        again.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load(body, name);
            }
        });
        btns.addView(again);
        TextView copy = MiUi.ghostButton(this, p, "复制", null);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyText(body.getText().toString());
            }
        });
        btns.addView(copy);
        TextView close = MiUi.ghostButton(this, p, "关闭", null);
        btns.addView(close);
        col.addView(btns);

        FrameLayout wrapper = new FrameLayout(this);
        int gap = dp(10);
        wrapper.setPadding(gap, gap, gap, gap);
        wrapper.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.92f),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.8f));
        }
        dlg.setContentView(wrapper);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
            }
        });
        try {
            dlg.show();
        } catch (Throwable t) {
            toast("无法打开日志窗口：" + t.getMessage());
            return;
        }
        load(body, name);
    }

    /** root 读文件（后台线程）：只取末尾若干行，避免把几 MB 的树快照塞进内存。 */
    private void load(final TextView view, final String name) {
        view.setText("正在读取…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String text = readTail(name);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        view.setText(text);
                        view.setTextIsSelectable(true);
                    }
                });
            }
        }, "rg-log-read").start();
    }

    private String readTail(String name) {
        String path = hostLogDir(this) + "/" + name;
        int lines = "rgtools_tree.txt".equals(name) ? 400 : 600;
        // 先问一句文件状态：大小/时间/是否存在都拿得到，用户一眼知道是不是还没生成
        RootShell.Result st = RootShell.run("ls -l '" + path + "' 2>&1");
        String stat = st.ok() && st.out != null && !st.out.isEmpty()
                ? st.out.trim() : "（文件不存在，宿主跑起来之后才会生成）";

        RootShell.Result r = RootShell.run(
                "tail -n " + lines + " '" + path + "' 2>/dev/null", 200000);
        if (!r.ok()) {
            return stat + "\n\n读取失败：" + r.describe()
                    + "\n\n若提示找不到 su，说明 ReadGuo 还没被授予 root（KernelSU 里放行一下即可）。"
                    + "\n也可以用 adb 手动拉取：\nadb pull " + path;
        }
        String out = r.out;
        return stat + "\n——— 以下为末尾 " + lines + " 行 ———\n"
                + ((out == null || out.isEmpty()) ? "（文件为空）" : out);
    }

    private void copyText(String s) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("ReadGuo", s));
                toast("已复制");
                return;
            }
        } catch (Throwable ignored) {
        }
        toast("复制失败");
    }

    /* ==================== 关于页 ==================== */

    private View aboutPage() {
        LinearLayout col = page("关于", -1, null);
        LinearLayout body = (LinearLayout) holder.get(col);

        LinearLayout card = MiUi.group(this, p, body, "应用信息");
        valueRow(card, "模块名称", "ReadGuo");
        MiUi.divider(this, p, card);
        valueRow(card, "版本", versionName() + " (" + versionCode() + ")");
        MiUi.divider(this, p, card);
        valueRow(card, "作者", AUTHOR);
        MiUi.divider(this, p, card);
        valueRow(card, "作用域", "红果免费短剧 / 番茄免费小说 / 番茄畅听");
        MiUi.divider(this, p, card);
        valueRow(card, "运行框架", "LSPosed / libxposed 100+");
        MiUi.divider(this, p, card);
        valueRow(card, "构建时间", buildTime());

        LinearLayout open = MiUi.group(this, p, body, null);
        LinearLayout line = MiUi.row(this, p, open, "源码仓库", null, MiUi.I_BOOK);
        line.addView(MiUi.chevron(this, p));
        line.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUrl("https://" + GITHUB);
            }
        });

        LinearLayout dis = MiUi.group(this, p, body, "免责声明");
        TextView disBody = MiUi.body(this, p,
                "本模块仅供学习交流与个人技术研究使用，请勿用于任何商业或非法用途。\n\n"
                        + "本模块不修改、不破解、不绕过任何付费、会员或版权保护机制；"
                        + "所有功能仅调整本机的界面显示与个人偏好设置。\n\n"
                        + "请在遵守法律法规及平台用户协议的前提下使用；请支持正版，"
                        + "相关内容、商标与软件版权归原作者及平台所有。\n\n"
                        + "本模块按「现状」提供，不附带任何明示或默示的担保；"
                        + "因下载、安装或使用本模块而产生的一切后果，由使用者自行承担。\n\n"
                        + "若本模块侵犯了相关方的合法权益，请联系作者删除。");
        disBody.setPadding(dp(16), dp(14), dp(16), dp(14));
        dis.addView(disBody);

        /* --- 模块自身 --- */
        LinearLayout self = MiUi.group(this, p, body, "模块自身");
        switchRow(self, "隐藏桌面图标",
                "关闭后从 LSPosed 模块列表的「打开」进入",
                iconHidden(), new MiUi.OnToggle() {
            @Override
            public void onChanged(boolean on) {
                applyIconHidden(on);
            }
        });
        MiUi.divider(this, p, self);
        LinearLayout l1 = MiUi.row(this, p, self, "运行日志",
                "当前宿主进程里的 hook 记录", MiUi.I_LOG);
        l1.addView(MiUi.chevron(this, p));
        l1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLog("rgtools_log.txt");
            }
        });
        MiUi.divider(this, p, self);
        LinearLayout l2 = MiUi.row(this, p, self, "视图树快照",
                "最近一次导出的视图树", MiUi.I_BOOK);
        l2.addView(MiUi.chevron(this, p));
        l2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLog("rgtools_tree.txt");
            }
        });

        return col;
    }

    /* ==================== 小工具 ==================== */

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private int versionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    private String hostVersion() {
        try {
            return getPackageManager().getPackageInfo(hostPkgResolved(this), 0).versionName;
        } catch (Throwable t) {
            return "未安装";
        }
    }

    /**
     * HyperOS 版本：小米把 {@code Build.DISPLAY} 换成了基线版本号（CP2A.…），
     * 真正的 OS 版本在 {@code ro.mi.os.version.incremental} 里，反射读一下。
     * 读不到就退回 DISPLAY / RELEASE。
     */
    private String hyperOsVersion() {
        String s = sysProp("ro.mi.os.version.incremental");
        if (s == null || s.isEmpty()) {
            s = sysProp("ro.miui.ui.version.name");
        }
        if (s != null && !s.isEmpty()) {
            return s;
        }
        String d = Build.DISPLAY;
        return (d != null && !d.isEmpty()) ? d : Build.VERSION.RELEASE;
    }

    /** 读系统属性。走反射，失败返回 null（不能因取不到版本号就崩）。 */
    private String sysProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method m = sp.getMethod("get", String.class);
            Object v = m.invoke(null, key);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    private String buildTime() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return new SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.CHINA)
                    .format(new Date(pi.lastUpdateTime));
        } catch (Throwable t) {
            return "?";
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            toast("没有可用的浏览器");
        }
    }

    private void toast(String msg) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private int dp(float v) {
        return MiUi.dp(this, v);
    }

    /** 颜色亮度（0~255），用来决定状态栏图标用深色还是浅色。 */
    private static int brightness(int c) {
        return (((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF)) / 3;
    }

    @SuppressWarnings("unused")
    private static void keepWindowFlagRef() {
        // 仅为记录：曾在调试里用过 WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS，
        // 现在改成 insets 内边距方案（edge-to-edge 下 setStatusBarColor 已失效）。
    }
}