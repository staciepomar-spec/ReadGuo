package com.wodi.redguotools;

import android.app.Dialog;
import android.os.SystemClock;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 番茄免费小说：拦掉「一进软件」时弹出来的一切浮层。
 *
 * <h3>为什么用「启动后的时间窗」而不是内容白名单</h3>
 * 宿主每次活动都换一套文案（签到、限时福利、会员日、青少年提示、升级提示…），
 * 靠关键词列表永远追不上，而且写死的关键词还会误伤正常内容。反过来，这些弹出全部集中在
 * 进程起来后的头几秒 —— 用时间窗反而更准、更省事。
 *
 * <p>窗口取 10 秒：足够覆盖首页首屏 + 第一个信息流刷新那一段，又不至于把用户
 * 五分钟后主动点「分享」弹的 Dialog 也吞掉。
 *
 * <h3>不拦什么</h3>
 * <ul>
 *   <li>模块自己的面板（{@code com.wodi.redguotools.*}）—— 否则一点就自己消失</li>
 *   <li>窗口之外的 Dialog —— 用户主动触发的交互不能被吞</li>
 * </ul>
 *
 * <p>升级弹窗不靠这里：{@link NoUpdate} 走内容签名，任何时间都能认出来，
 * 这里只是顺带把启动期的升级弹层一起盖掉。
 */
final class FanqiePopup {

    /** 启动后的拦截窗口（毫秒）。 */
    private static final long WINDOW_MS = 10_000;

    private static long sBootAt;

    private FanqiePopup() {
    }

    static void hook(XposedModule mod) {
        sBootAt = SystemClock.uptimeMillis();
        try {
            Method show = Dialog.class.getDeclaredMethod("show");
            mod.hook(show)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object r = chain.proceed();
                        try {
                            Dialog d = (Dialog) chain.getThisObject();
                            if (d == null) {
                                return r;
                            }
                            if (!Config.blockFanqiePopups(UiController.appCtx())) {
                                return r;
                            }
                            String cn = d.getClass().getName();
                            // 模块自己的面板（番茄精简）不能拦，否则点开就没了
                            if (cn.startsWith("com.wodi.redguotools")) {
                                return r;
                            }
                            long since = SystemClock.uptimeMillis() - sBootAt;
                            if (since >= 0 && since <= WINDOW_MS) {
                                d.dismiss();
                                diag("dismiss popup +" + since + "ms " + cn);
                            }
                        } catch (Throwable ignored) {
                        }
                        return r;
                    });
        } catch (Throwable t) {
            diag("popup hook failed " + t);
        }
    }

    private static void diag(String msg) {
        java.io.FileOutputStream fos = null;
        try {
            // 路径跟着当前宿主走：同一套代码给番茄小说（com.dragon.read）和
            // 畅听（com.xs.fm）共用，硬编码包名会让另一边静默丢日志。
            java.io.File dir = null;
            android.content.Context c = UiController.appCtx();
            if (c != null) {
                dir = c.getFilesDir();
            }
            java.io.File f = dir != null
                    ? new java.io.File(dir, "rg_fq.txt")
                    : new java.io.File("/data/data/" + Hosts.modifiedBy() + "/files/rg_fq.txt");
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
}
