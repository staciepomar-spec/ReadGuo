package com.wodi.redguotools;

/**
 * 宿主包名清单。
 *
 * <p><b>为什么单独一个类</b>：这些常量既要给宿主进程里的 hook 用，也要给
 * **模块自己的 App** 用（设置页显示版本、重启按钮、日志目录）。而
 * {@link RGModule} 继承自 {@code io.github.libxposed.api.XposedModule}，
 * 这个类只在 LSPosed 注入过的进程里存在 —— 在模块 App 进程里
 * 一引用 {@code RGModule.HOST_PKGS} 就会触发 RGModule 类加载，
 * 抛 {@code NoClassDefFoundError: Lio/github/libxposed/api/XposedModule;}
 * 直接把 App 崩掉。所以纯数据放这里，两边都能安全读。
 */
final class Hosts {

    /**
     * 三个包名，业务类名完全一致（同一套 com.dragon.read.* 代码库）：
     * <ul>
     *   <li>{@code com.phoenix.read} —— <b>红果免费短剧 官方（国内版）</b>，主力测试机型</li>
     *   <li>{@code com.phoenix.read.oversea.gp} —— 红果 <b>Google Play 版</b>
     *       （同 7.3.9.32，少 2 个 dex，无 Reparo 热更新）</li>
     *   <li>{@code com.dragon.read} —— <b>番茄免费小说</b>，不是红果。
     *       两者共用 com.dragon.read.* 代码库，所以类名结构一致、hook 能挂上，
     *       但界面与业务不同（书城/听书/书架）。它出现在这里只是历史遗留，
     *       副作用是模块会给番茄也打 Activity/View 级 hook —— 若不需要请移出 scope。</li>
     * </ul>
     *
     * <p>判断依据（2026-11-20 实测）：com.phoenix.read = 7.3.9.32 红果；
     * com.dragon.read = 7.4.5.32，启动后是番茄小说书城页。
     */
    static final String[] PKGS = {
            "com.phoenix.read",
            "com.phoenix.read.oversea.gp",
            "com.dragon.read",
            "com.xs.fm",
    };

    /** 默认（主测试）宿主包名。 */
    static final String DEFAULT = PKGS[0];

    /**
     * 番茄免费小说的包名。
     *
     * <p>它就在 {@link #PKGS} 里（和红果共用 com.dragon.read.* 代码库），但**不是红果** ——
     * 界面、业务、BottomTabBarItemType 各枚举值的含义都不同。凡是"只在红果生效"的逻辑
     * 都要用 {@link #isFanqie} 排掉它，反之亦然。
     */
    static final String FANQIE = "com.dragon.read";

    /**
     * 番茄畅听（{@code com.xs.fm}，实测 6.7.8.32）。
     *
     * <p>和前两个**不是同一代代码**：它虽然也带 {@code com.dragon.read.*} 包名，
     * 但版本只有 6.7.8，没有 {@code BottomTabBarItemType}、没有 {@code NsAdImpl}、
     * 没有 {@code SkinManager} —— 红果 7.x 那套锚点在它身上全部落空。
     * 底栏走的是 {@code pages.main.tab.AbsBottomTab} + {@code RadioGroup} 老架构，
     * 详情见 {@link Ct}。
     */
    static final String CT = "com.xs.fm";

    static boolean isFanqie(String pkg) {
        return FANQIE.equals(pkg);
    }

    static boolean isCt(String pkg) {
        return CT.equals(pkg);
    }

    /** 红果（含 Google Play 渠道），既不是番茄小说也不是番茄畅听。 */
    static boolean isHongguo(String pkg) {
        return pkg != null && !FANQIE.equals(pkg) && !CT.equals(pkg) && isHost(pkg);
    }

    static boolean isHost(String pkg) {
        if (pkg == null) {
            return false;
        }
        for (String p : PKGS) {
            if (p.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    /* ---------------- 运行环境快照（崩溃取证用） ---------------- */
    /*
     * 同样不能放 RGModule：CrashGuard 要在**模块 App 进程**里也能跑（App 自己也会闪退），
     * 而那里拿不到 libxposed。所以框架信息由 RGModule 在宿主进程里写入这里，
     * 两个进程都只读这个纯数据类。
     */

    private static volatile String fwName = "?";
    private static volatile String fwVer = "?";
    private static volatile int apiVer = -1;
    private static volatile String curPkg;

    static void setFramework(String name, String ver, int api) {
        fwName = name == null ? "?" : name;
        fwVer = ver == null ? "?" : ver;
        apiVer = api;
    }

    static void setCurPkg(String pkg) {
        curPkg = pkg;
    }

    static String frameworkName() {
        return fwName;
    }

    static String frameworkVersion() {
        return fwVer;
    }

    static int apiVersion() {
        return apiVer;
    }

    /** 当前进程对应的宿主包名；不在宿主进程里时为 null（App 进程内即 null）。 */
    static String modifiedBy() {
        return curPkg == null ? "?" : curPkg;
    }

    private Hosts() {
    }
}
