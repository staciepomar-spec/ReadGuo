package com.wodi.redguotools;

import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 「我的」页净化。
 *
 * <h3>为什么这里只做配置开关，不做 View 隐藏</h3>
 * 红果 7.3.9.32 的「我的」页面整块是 <b>Compose</b> 渲染的，实测视图树只有：
 * <pre>
 *   pw3.j > pw3.m > FrameLayout > androidx.compose.ui.platform.ComposeView(id=ck0, 959x2670)
 *                                  └─ AndroidComposeView / r0   （下面是空的，没有任何子 View）
 * </pre>
 * 右侧抽屉（☰ 菜单）同样如此：{@code NavigationView(id=gc5) > NavigationMenuView > ComposeView(ck0)}。
 * Compose 不管内部画了多少东西，在 View 层永远是「一个」节点 ——
 * 所以「按文案找节点 → GONE」这套机制（{@link UiController} 的槽位/自定义清单）
 * 对这两处<b>完全不适用</b>。
 *
 * <p>唯一干净的入口是宿主自己的 AB 配置：主题页的每个模块都由 settings 键控制，
 * 把它读出来的对象改成「关闭」即可，Compose 那一层根本不会去渲染。
 *
 * <h3>游戏中心（已确证）</h3>
 * <pre>
 *   settings key : eggplant_my_tab_game_center
 *   模型         : EggplantMyTabGame { @SerializedName("enable") boolean enable }
 *   读取入口     : IEggplantMyTabGame$$Impl.getConfig()
 *   注册         : SsConfigMgr.prepareAB("eggplant_my_tab_game_center", 模型, 接口)
 * </pre>
 * hook {@code getConfig()} 返回 {@code new EggplantMyTabGame(false)} 即可。
 * 模型类有 {@code public EggplantMyTabGame(boolean)} 构造器，直接用反射构造。
 *
 * <p>注意：拿不到 Context（配置未初始化）时按「不拦」处理 —— 这条路是纯 UI 净化，
 * 宁可漏拦也不要因为包内其它逻辑拿到 null 而出错。
 */
final class MineTab {

    private static final String CLS_GAME_IFACE_IMPL =
            "com.dragon.read.component.biz.impl.IEggplantMyTabGame$$Impl";
    private static final String CLS_GAME_MODEL =
            "com.dragon.read.component.biz.impl.EggplantMyTabGame";

    private static volatile boolean sInit;
    private static volatile String sStatus;

    private MineTab() {
    }

    static String status() {
        return sStatus;
    }

    static void hook(XposedModule mod, ClassLoader cl) {
        if (sInit) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("game=").append(hookGameCenter(mod, cl));
        sStatus = sb.toString();
        sInit = true;
    }

    private static String hookGameCenter(XposedModule mod, ClassLoader cl) {
        try {
            Class<?> impl = RGModule.tryLoad(cl, CLS_GAME_IFACE_IMPL);
            Class<?> model = RGModule.tryLoad(cl, CLS_GAME_MODEL);
            if (impl == null || model == null) {
                return "absent";
            }
            final Constructor<?> ctor = model.getConstructor(boolean.class);
            Method getConfig = impl.getDeclaredMethod("getConfig");
            getConfig.setAccessible(true);
            mod.hook(getConfig)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (!hideGameCenter()) {
                            return chain.proceed();
                        }
                        try {
                            // 每次都新建：模型是不可变对象（final 字段），且调用方可能会
                            // 把返回值缓存下来，返回同一个实例没有额外收益。
                            return ctor.newInstance(false);
                        } catch (Throwable t) {
                            UiController.logFile("mine: game ctor failed " + t);
                            return chain.proceed();
                        }
                    });
            return "ok";
        } catch (Throwable t) {
            UiController.logFile("mine: game hook failed " + t);
            return "fail";
        }
    }

    private static boolean hideGameCenter() {
        Context c = UiController.appCtx();
        return c != null && Config.hideGameCenter(c);
    }
}
