package com.wodi.redguotools;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 编辑模式的「草稿会话」状态机（跑在宿主进程里）：
 * 点选隐藏的改动先累积为草稿（改动实时生效，但随时可整体回滚），
 * 「保存并退出编辑」才定稿；未保存退出 / 宿主被杀则自动回滚到进入编辑前。
 *
 * <p>状态存独立 prefs（{@code rg_confirm_state}）：kind / snap / count / pid / time。
 * snap 是配置 prefs 全量键值的类型化快照（字符串值 base64，防分隔符），
 * 回滚 = editor.clear() 后把快照原样写回。
 */
public final class EditGuard {

    private static final String TAG = RGModule.TAG;

    /** 草稿状态专用 prefs 文件（MODE_PRIVATE）。 */
    private static final String STATE = "rg_confirm_state";

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private EditGuard() {
    }

    /** 进入编辑模式：先给配置 prefs 拍快照，之后的改动都算草稿。已在编辑则不动。 */
    public static void enter(Activity a) {
        try {
            if (a == null || isEditing(a)) {
                return;
            }
            String snap = snapshot(Config.prefs(a));
            st(a).edit()
                    .putString("kind", "edit")
                    .putString("snap", snap)
                    .putString("hist", b64(snap))   // 撤销历史：栈底 = 进入编辑前的状态
                    .putInt("count", 0)
                    .putInt("pid", android.os.Process.myPid())
                    .putLong("time", System.currentTimeMillis())
                    .apply();
            Toast.makeText(a, "已进入编辑模式：改动先累积，完成后点「保存并退出编辑」",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Log.e(TAG, "edit enter failed", t);
        }
    }

    /** 是否在编辑中：kind=="edit" 且记录的进程还活着（进程死了说明草稿没保存就没了）。 */
    public static boolean isEditing(Context c) {
        try {
            SharedPreferences s = st(c);
            if (!"edit".equals(s.getString("kind", ""))) {
                return false;
            }
            int pid = s.getInt("pid", 0);
            return pid > 0 && new File("/proc/" + pid).exists();
        } catch (Throwable t) {
            Log.e(TAG, "edit state failed", t);
            return false;
        }
    }

    /**
     * 草稿计数 +1，并把「操作后的状态」压进撤销历史（addOverlay 成功后调用）。
     *
     * <p>历史栈形如 [进入编辑前, 第1步后, 第2步后 …]，当前状态 = 栈顶。
     * 每步都存全量快照，撤销就是「弹掉栈顶、恢复到新的栈顶」——
     * 比记录增量简单，也不会因为某步改多个键而漏还原。
     */
    public static void onApplied(Activity a) {
        try {
            SharedPreferences s = st(a);
            String cur = b64(snapshot(Config.prefs(a)));
            String hist = s.getString("hist", "");
            if (hist == null || hist.isEmpty()) {
                // 兜底：没有历史就从进入编辑前的 snap 起底
                hist = b64(s.getString("snap", "")) + "," + cur;
            } else {
                hist = hist + "," + cur;
            }
            int n = histCount(hist);
            s.edit().putString("hist", hist).putInt("count", n).apply();
            Toast.makeText(a, "已加入草稿（" + n + " 条），可继续点选；完成后点「保存并退出编辑」",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Log.e(TAG, "edit applied failed", t);
        }
    }

    /**
     * 撤销上一步未保存的改动：弹掉栈顶，恢复到上一个状态。
     * 没有历史（还没隐藏过）时只提示，不动配置。
     */
    public static void undo(Activity a) {
        try {
            SharedPreferences s = st(a);
            if (!"edit".equals(s.getString("kind", ""))) {
                Toast.makeText(a, "当前没有未保存的改动", Toast.LENGTH_SHORT).show();
                return;
            }
            String hist = s.getString("hist", "");
            String[] parts = (hist == null || hist.isEmpty())
                    ? new String[0] : hist.split(",");
            if (parts.length < 2) {
                Toast.makeText(a, "没有可撤销的操作", Toast.LENGTH_SHORT).show();
                return;
            }
            // 恢复到倒数第二个状态，并把它作为新的栈顶
            restore(a, unb64(parts[parts.length - 2]));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(parts[i]);
            }
            int n = parts.length - 2;
            s.edit().putString("hist", sb.toString()).putInt("count", n).apply();
            refreshHost(a);
            Toast.makeText(a, n > 0 ? ("已撤销上一步，还剩 " + n + " 条草稿")
                    : "已撤销上一步", Toast.LENGTH_SHORT).show();
            Log.i(TAG, "edit: undo -> " + n + " drafts");
        } catch (Throwable t) {
            Log.e(TAG, "edit undo failed", t);
        }
    }

    /** 保存并退出编辑：草稿定稿，只清状态、不动配置。 */
    public static void save(Activity a) {
        try {
            st(a).edit().clear().apply();
            Toast.makeText(a, "已保存并退出编辑", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.e(TAG, "edit save failed", t);
        }
    }

    /** 未保存退出：快照整体回滚到进入编辑前，并刷新宿主界面。 */
    public static void abandon(Activity a) {
        try {
            SharedPreferences s = st(a);
            restore(a, s.getString("snap", ""));
            s.edit().clear().apply();
            refreshHost(a);
            Toast.makeText(a, "未保存就退出，已恢复到进入编辑前", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Log.e(TAG, "edit abandon failed", t);
        }
    }

    /**
     * 宿主启动时收尾：上次编辑没保存就退出了进程 → 回滚快照；
     * pid 还活着（别的进程在编辑）则不动。
     */
    public static void recover(final Context c) {
        try {
            SharedPreferences s = st(c);
            if (!"edit".equals(s.getString("kind", ""))) {
                return;
            }
            int pid = s.getInt("pid", 0);
            if (pid > 0 && new File("/proc/" + pid).exists()) {
                Log.i(TAG, "edit: owned by live pid " + pid + ", skip");
                return;
            }
            restore(c, s.getString("snap", ""));
            s.edit().clear().apply();
            Log.i(TAG, "edit: rolled back unsaved draft on startup");
            // 启动早期 Toast 还没就绪，post 到主线程稍后弹
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Toast.makeText(c.getApplicationContext(),
                                "上次编辑未保存，已恢复到进入编辑前", Toast.LENGTH_LONG).show();
                    } catch (Throwable t) {
                        Log.e(TAG, "edit toast failed", t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "edit recover failed", t);
        }
    }

    /** 当前草稿条数。 */
    public static int draftCount(Context c) {
        try {
            return st(c).getInt("count", 0);
        } catch (Throwable t) {
            Log.e(TAG, "edit count failed", t);
            return 0;
        }
    }

    /* ---------------- 内部 ---------------- */

    private static SharedPreferences st(Context c) {
        Context app = c.getApplicationContext();
        return (app != null ? app : c).getSharedPreferences(STATE, Context.MODE_PRIVATE);
    }

    /** 历史栈里的草稿条数（栈深 - 1 个基准）。 */
    private static int histCount(String hist) {
        if (hist == null || hist.isEmpty()) {
            return 0;
        }
        return Math.max(0, hist.split(",").length - 1);
    }

    /** 全量键值 → 类型化文本快照，一行一条：类型|键|值，值里的分隔符用 base64 挡掉。 */
    private static String snapshot(SharedPreferences sp) {
        StringBuilder sb = new StringBuilder();
        try {
            Map<String, ?> all = sp.getAll();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                String k = e.getKey();
                Object v = e.getValue();
                if (k == null || v == null) {
                    continue;
                }
                String line;
                if (v instanceof Boolean) {
                    line = "z|" + k + "|" + (((Boolean) v) ? "1" : "0");
                } else if (v instanceof Integer) {
                    line = "i|" + k + "|" + v;
                } else if (v instanceof Long) {
                    line = "l|" + k + "|" + v;
                } else if (v instanceof Float) {
                    line = "f|" + k + "|" + v;
                } else if (v instanceof String) {
                    line = "s|" + k + "|" + b64((String) v);
                } else if (v instanceof Set) {
                    StringBuilder es = new StringBuilder();
                    for (Object o : (Set<?>) v) {
                        if (es.length() > 0) {
                            es.append(',');
                        }
                        es.append(b64(String.valueOf(o)));
                    }
                    line = "e|" + k + "|" + es;
                } else {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
        } catch (Throwable t) {
            Log.e(TAG, "edit snapshot failed", t);
        }
        return sb.toString();
    }

    /** 把快照写回配置 prefs（先 clear 再全量写入），做到「回到进入编辑前」。 */
    private static void restore(Context c, String snap) {
        SharedPreferences.Editor ed = Config.prefs(c).edit();
        ed.clear();
        if (snap != null && !snap.isEmpty()) {
            for (String line : snap.split("\n")) {
                int p1 = line.indexOf('|');
                int p2 = line.indexOf('|', p1 + 1);
                if (p1 <= 0 || p2 < 0) {
                    continue;
                }
                String type = line.substring(0, p1);
                String key = line.substring(p1 + 1, p2);
                String val = line.substring(p2 + 1);
                try {
                    if ("z".equals(type)) {
                        ed.putBoolean(key, "1".equals(val));
                    } else if ("i".equals(type)) {
                        ed.putInt(key, Integer.parseInt(val));
                    } else if ("l".equals(type)) {
                        ed.putLong(key, Long.parseLong(val));
                    } else if ("f".equals(type)) {
                        ed.putFloat(key, Float.parseFloat(val));
                    } else if ("s".equals(type)) {
                        ed.putString(key, unb64(val));
                    } else if ("e".equals(type)) {
                        HashSet<String> set = new HashSet<>();
                        if (!val.isEmpty()) {
                            for (String o : val.split(",")) {
                                set.add(unb64(o));
                            }
                        }
                        ed.putStringSet(key, set);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "edit restore entry failed " + key, t);
                }
            }
        }
        ed.apply();
    }

    /** 回滚后重刷宿主界面：红果走 UiController，番茄/畅听走 ScanHide（同 PickerOverlay.otherHost 的分支）。 */
    private static void refreshHost(Activity a) {
        try {
            if (a == null) {
                return;
            }
            String p = a.getPackageName();
            if (Hosts.isFanqie(p) || Hosts.isCt(p)) {
                ScanHide.applyNow(a);
            } else {
                UiController.refresh(a);
            }
        } catch (Throwable t) {
            Log.e(TAG, "edit refresh failed", t);
        }
    }

    private static String b64(String s) {
        try {
            return Base64.encodeToString(s.getBytes(UTF8), Base64.NO_WRAP);
        } catch (Throwable t) {
            Log.e(TAG, "edit b64 failed", t);
            return "";
        }
    }

    private static String unb64(String s) {
        try {
            return new String(Base64.decode(s, Base64.NO_WRAP), UTF8);
        } catch (Throwable t) {
            Log.e(TAG, "edit unb64 failed", t);
            return "";
        }
    }
}
