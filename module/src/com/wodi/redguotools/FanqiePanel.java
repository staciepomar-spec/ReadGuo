package com.wodi.redguotools;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

/**
 * 番茄免费小说的模块面板（长按底部「书城」标签唤出）。
 *
 * <h3>视觉规范（与红果内面板 / 模块 App 一致）</h3>
 * <pre>
 *   面板底      #F4F4F5   整块 20dp 圆角（不是直角铺满，浮在宿主界面上）
 *   卡片        白 #FFFFFF 16dp 圆角
 *   正文        #111111   17sp
 *   次要文字    #8E8E93   13sp / 12sp
 *   分割线      #E5E5EA   左缩进 16dp
 *   主按钮      #007AFF   12dp 圆角、白字
 *   开关（开）  #34C759   轨道与滑块都要手动上色
 *   开关（关）  #E5E5EA
 * </pre>
 *
 * <p><b>为什么 Switch 必须手动 tint</b>：<h3>系统的 {@code Switch} 默认取 Activity 主题的
 * colorAccent —— 番茄的主题色是红色，直接 new 出来的开关就是红色的，跟整块 UI 的蓝/灰
 * 完全不搭。iOS 的开关是「轨道染色、滑块恒白」，所以 thumb 和 track 各给一份
 * ColorStateList，check 时一起变绿。
 */
final class FanqiePanel {

    /* 规范色，不从 Palette 取：这几处是硬标准，宿主换皮也不该变。 */
    private static final int C_BG = 0xFFF4F4F5;
    private static final int C_CARD = 0xFFFFFFFF;
    private static final int C_TEXT = 0xFF111111;
    private static final int C_SUB = 0xFF8E8E93;
    private static final int C_DIVIDER = 0xFFE5E5EA;
    private static final int C_ACCENT = 0xFF007AFF;
    private static final int C_SWITCH_ON = 0xFF34C759;
    private static final int C_SWITCH_OFF = 0xFFE5E5EA;

    private static Dialog sDlg;

    private FanqiePanel() {
    }

    static boolean isShowing() {
        return sDlg != null && sDlg.isShowing();
    }

    /** 点选隐藏前先收面板 —— 悬浮球要能点到宿主页面本身。 */
    static void dismiss() {
        if (sDlg != null) {
            sDlg.dismiss();
        }
    }

    static void show(Activity a) {
        show(a, false);
    }

    /**
     * @param ct true = 番茄畅听（{@code com.xs.fm}）精简版面板；false = 番茄小说
     */
    static void show(Activity a, boolean ct) {
        if (a == null || a.isFinishing() || isShowing()) {
            return;
        }

        int pad = Theme.dp(a, 20);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, Theme.dp(a, 20), pad, Theme.dp(a, 16));

        /* ---------- 标题 ---------- */
        TextView title = new TextView(a);
        title.setText(ct ? "畅听精简" : "番茄精简");
        title.setTextSize(21);
        title.setTextColor(C_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setIncludeFontPadding(false);
        root.addView(title);

        TextView lead = new TextView(a);
        lead.setText(ct ? "开关即时生效，重进 App 后自动沿用" : "改动需重启番茄才生效");
        lead.setTextSize(13);
        lead.setTextColor(C_SUB);
        lead.setIncludeFontPadding(false);
        lead.setPadding(0, Theme.dp(a, 5), 0, Theme.dp(a, 16));
        root.addView(lead);

        /* ---------- 开关卡 ---------- */
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(C_CARD);
        cbg.setCornerRadius(Theme.dp(a, 16));
        card.setBackground(cbg);

        if (ct) {
            addSwitch(a, card, "底栏只留「首页 / 我的」",
                    "隐藏商城、听歌、领现金三格，其余平分宽度",
                    Config.ctHideTabs(a),
                    new FanqiePanel.OnToggle() {
                        @Override
                        public void onChanged(boolean v) {
                            Config.setCtHideTabs(a, v);
                            UiController.logFile("ct: hideTabs=" + v);
                        }
                    });
            addDivider(a, card);
            addSwitch(a, card, "精简「我的」页",
                    "去掉金刚位（消息/优惠券/购物车/订单/游戏）与「我的资产」",
                    Config.ctSlimMine(a),
                    new FanqiePanel.OnToggle() {
                        @Override
                        public void onChanged(boolean v) {
                            Config.setCtSlimMine(a, v);
                            UiController.logFile("ct: slimMine=" + v);
                        }
                    });
            addDivider(a, card);
            addSwitch(a, card, "去掉四宫格入口",
                    "隐藏「我的」页右上角的「全部功能」按钮",
                    Config.ctHideAllFunc(a),
                    new FanqiePanel.OnToggle() {
                        @Override
                        public void onChanged(boolean v) {
                            Config.setCtHideAllFunc(a, v);
                            UiController.logFile("ct: hideAllFunc=" + v);
                        }
                    });
            addDivider(a, card);
        } else {
            addSwitch(a, card, "屏蔽底部「赚钱」",
                    "让宿主的 tab 工厂返回 null，底栏不留空位",
                    Config.hideFanqieEarnTab(a),
                    new FanqiePanel.OnToggle() {
                        @Override
                        public void onChanged(boolean v) {
                            Config.setHideFanqieEarnTab(a, v);
                        }
                    });
            addDivider(a, card);
            addSwitch(a, card, "精简「我的」页右侧边栏",
                    "收起右上角 ☰ 抽屉里的订单 / 浏览历史 / 创作中心 / 预约与协议链接",
                    Config.hideFanqieMineCells(a),
                    new FanqiePanel.OnToggle() {
                        @Override
                        public void onChanged(boolean v) {
                            Config.setHideFanqieMineCells(a, v);
                        }
                    });
            addDivider(a, card);
        }
        if (ct) {
            addSwitch(a, card, "屏蔽广告",
                    "广告位判定恒 false：开屏 / 信息流 / 阅读中全关",
                    Config.ctBlockAds(a),
                    new FanqiePanel.OnToggle() {
                        @Override
                        public void onChanged(boolean v) {
                            Config.setCtBlockAds(a, v);
                            UiController.logFile("ct: blockAds=" + v);
                        }
                    });
            addDivider(a, card);
        }
        addSwitch(a, card, "拦掉启动弹窗",
                "进程起来后 10 秒内的活动 / 签到 / 福利浮层直接关闭",
                Config.blockFanqiePopups(a),
                new FanqiePanel.OnToggle() {
                    @Override
                    public void onChanged(boolean v) {
                        Config.setBlockFanqiePopups(a, v);
                    }
                });
        root.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* ---------- 页面组件：扫描 / 清空（番茄、畅听通用） ---------- */
        TextView compTitle = new TextView(a);
        compTitle.setText("页面组件");
        compTitle.setTextSize(13);
        compTitle.setTextColor(C_SUB);
        compTitle.setIncludeFontPadding(false);
        compTitle.setPadding(Theme.dp(a, 4), Theme.dp(a, 18), 0, Theme.dp(a, 7));
        root.addView(compTitle);

        LinearLayout compCard = new LinearLayout(a);
        compCard.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cbg2 = new GradientDrawable();
        cbg2.setColor(C_CARD);
        cbg2.setCornerRadius(Theme.dp(a, 16));
        compCard.setBackground(cbg2);

        final TextView[] clearSubRef = new TextView[1];
        addAction(a, compCard, "点选隐藏",
                "屏幕上出现小悬浮球，点哪个藏哪个（可切「穿透」正常操作）",
                new android.view.View.OnClickListener() {
                    @Override
                    public void onClick(android.view.View v) {
                        dismiss();
                        PickerOverlay.start(a);
                    }
                });
        addDivider(a, compCard);
        addAction(a, compCard, "扫描组件",
                "列出本页能单独隐藏的块，勾选即隐藏",
                new android.view.View.OnClickListener() {
                    @Override
                    public void onClick(android.view.View v) {
                        ScanHide.scanDialog(a, new Runnable() {
                            @Override
                            public void run() {
                                if (clearSubRef[0] != null) {
                                    clearSubRef[0].setText(hiddenLine(a));
                                }
                            }
                        });
                    }
                });
        addDivider(a, compCard);
        clearSubRef[0] = addAction(a, compCard, "清空隐藏",
                hiddenLine(a), new android.view.View.OnClickListener() {
                    @Override
                    public void onClick(android.view.View v) {
                        ScanHide.clear(a);
                        if (clearSubRef[0] != null) {
                            clearSubRef[0].setText(hiddenLine(a));
                        }
                        android.widget.Toast.makeText(a, "已恢复全部隐藏组件",
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
        root.addView(compCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* ---------- 已隐藏组件：逐条列出，可单独恢复（与红果的隐藏清单对齐） ---------- */
        final TextView hiddenTitle = new TextView(a);
        hiddenTitle.setText("已隐藏组件");
        hiddenTitle.setTextSize(13);
        hiddenTitle.setTextColor(C_SUB);
        hiddenTitle.setIncludeFontPadding(false);
        hiddenTitle.setPadding(Theme.dp(a, 4), Theme.dp(a, 18), 0, Theme.dp(a, 7));
        root.addView(hiddenTitle);

        final LinearLayout hiddenCard = new LinearLayout(a);
        hiddenCard.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable hbg = new GradientDrawable();
        hbg.setColor(C_CARD);
        hbg.setCornerRadius(Theme.dp(a, 16));
        hiddenCard.setBackground(hbg);

        // 删除一条后整卡重建：条目少，重建比局部删 View 稳妥
        final Runnable[] refreshHidden = new Runnable[1];
        refreshHidden[0] = new Runnable() {
            @Override
            public void run() {
                fillHiddenRows(a, hiddenCard, refreshHidden[0]);
                int n = ScanHide.count(a);
                hiddenTitle.setText(n <= 0 ? "已隐藏组件" : ("已隐藏组件（" + n + "）"));
                if (clearSubRef[0] != null) {
                    clearSubRef[0].setText(hiddenLine(a));
                }
            }
        };
        refreshHidden[0].run();
        root.addView(hiddenCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* ---------- 说明（卡外，小字） ---------- */
        TextView note = new TextView(a);
        note.setText(ct
                ? "底栏文案取自宿主下发的 tab 名。若某项没被去掉，点「抓取视图树」把树发我，按实际文案补词根。"
                : "「我的」页那排金刚位（我的订单 / 浏览历史 / 我的消息 / 创作中心 / 我的预约）"
                + "是宿主用 Compose 画的整张卡片，View 层拿不到节点，暂不支持单独隐藏。");
        note.setTextSize(12);
        note.setTextColor(C_SUB);
        note.setLineSpacing(Theme.dp(a, 2), 1f);
        note.setPadding(Theme.dp(a, 2), Theme.dp(a, 12), Theme.dp(a, 2), 0);
        root.addView(note);

        /* ---------- 主按钮 ---------- */
        TextView done = new TextView(a);
        done.setText("完成");
        done.setTextSize(17);
        done.setTextColor(0xFFFFFFFF);
        done.setTypeface(Typeface.DEFAULT_BOLD);
        done.setGravity(Gravity.CENTER);
        done.setPadding(0, Theme.dp(a, 14), 0, Theme.dp(a, 14));
        GradientDrawable btn = new GradientDrawable();
        btn.setColor(C_ACCENT);
        btn.setCornerRadius(Theme.dp(a, 12));
        done.setBackground(btn);
        done.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                if (sDlg != null) {
                    sDlg.dismiss();
                }
            }
        });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = Theme.dp(a, 18);
        root.addView(done, dlp);

        /* ---------- 诊断入口：放最底部一行淡字，不抢视觉 ---------- */
        TextView dump = new TextView(a);
        dump.setText("抓取视图树");
        dump.setTextSize(11);
        dump.setTextColor(C_SUB);
        dump.setGravity(Gravity.CENTER);
        dump.setPadding(0, Theme.dp(a, 12), 0, 0);
        dump.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                String path = UiController.logTree(a);
                android.widget.Toast.makeText(a,
                        path == null ? "写失败" : ("已写出：" + path),
                        android.widget.Toast.LENGTH_LONG).show();
            }
        });
        root.addView(dump);

        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        /*
         * 内容整体套 ScrollView：面板项越加越多，WRAP_CONTENT 高度一旦超出屏幕，
         * 底部就被切掉且滑不动（用户反馈）。窗口改成固定 92% x 82%（与红果面板一致），
         * 圆角背景挂在 ScrollView 上，滚动时四角始终是圆的。
         */
        ScrollView sv = new ScrollView(a);
        sv.setClipToPadding(false);
        sv.setVerticalScrollBarEnabled(false);
        GradientDrawable shell = new GradientDrawable();
        shell.setColor(C_BG);
        shell.setCornerRadius(Theme.dp(a, 20));
        sv.setBackground(shell);
        sv.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        d.setContentView(sv);
        Window w = d.getWindow();
        if (w != null) {
            // 透明窗底 + 圆角铺在 ScrollView 上，四角才能露出后面的宿主界面
            w.setBackgroundDrawable(new ColorDrawable(0));
            android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
            w.setLayout(Math.round(dm.widthPixels * 0.92f), Math.round(dm.heightPixels * 0.82f));
            w.setGravity(Gravity.CENTER);
            w.setDimAmount(0.45f);
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface di) {
                sDlg = null;
            }
        });
        sDlg = d;
        try {
            d.show();
        } catch (Throwable t) {
            sDlg = null;
            UiController.logFile("fanqie panel: show failed " + t);
        }
    }

    /** 一行开关：左侧标题 + 副标题，右侧 iOS 绿开关。 */
    private static void addSwitch(Activity a, LinearLayout card,
                                  String title, String sub, boolean checked,
                                  OnToggle l) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Theme.dp(a, 16), Theme.dp(a, 13), Theme.dp(a, 16), Theme.dp(a, 13));

        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(17);
        t.setTextColor(C_TEXT);
        t.setIncludeFontPadding(false);
        texts.addView(t);

        TextView s = new TextView(a);
        s.setText(sub);
        s.setTextSize(13);
        s.setTextColor(C_SUB);
        s.setIncludeFontPadding(false);
        s.setPadding(0, Theme.dp(a, 4), Theme.dp(a, 10), 0);
        texts.addView(s);
        row.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final View sw = makeSwitch(a, checked);
        sw.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                boolean now = !Boolean.TRUE.equals(v.getTag());
                applySwitch(v, now);
                if (l != null) {
                    l.onChanged(now);
                }
            }
        });
        row.addView(sw);
        card.addView(row);
    }

    /** 自绘开关的回调（不用系统 Switch，原因见 {@link #makeSwitch}）。 */
    interface OnToggle {
        void onChanged(boolean v);
    }

    /**
     * 自己画一个 iOS 风格开关。
     *
     * <p><b>为什么不用 {@code android.widget.Switch}</b>：HyperOS 4 上实测，
     * 无论是先 setChecked 再 jumpDrawablesToCurrentState，还是 post 到 attach 之后再设，
     * 滑块都会停在左端（只有轨道跟着变色）—— 表现为「绿轨道 + 左滑块」的错位形态，
     * 用户看不出到底开没开。这条路试了三种时序都没修好，索性用两个 View 画：
     * 一个圆角矩形当轨道，一个白圆当滑块，位置按状态直接写 leftMargin，不受厂商主题影响。
     */
    private static View makeSwitch(Activity a, boolean on) {
        int w = Theme.dp(a, 51);
        int h = Theme.dp(a, 31);
        FrameLayout box = new FrameLayout(a);
        box.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        box.setTag(on);

        View thumb = new View(a);
        GradientDrawable td = new GradientDrawable();
        td.setShape(GradientDrawable.OVAL);
        td.setColor(0xFFFFFFFF);
        thumb.setBackground(td);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                Theme.dp(a, 27), Theme.dp(a, 27));
        tlp.leftMargin = on ? w - Theme.dp(a, 29) : Theme.dp(a, 2);
        tlp.topMargin = Theme.dp(a, 2);
        thumb.setLayoutParams(tlp);
        box.addView(thumb);

        paintTrack(a, box, on);
        return box;
    }

    private static void applySwitch(View box, boolean on) {
        box.setTag(on);
        paintTrack(box.getContext(), box, on);
        if (!(box instanceof ViewGroup) || ((ViewGroup) box).getChildCount() == 0) {
            return;
        }
        View thumb = ((ViewGroup) box).getChildAt(0);
        int w = box.getWidth() > 0 ? box.getWidth() : Theme.dp(box.getContext(), 51);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) thumb.getLayoutParams();
        lp.leftMargin = on ? w - Theme.dp(box.getContext(), 29) : Theme.dp(box.getContext(), 2);
        thumb.setLayoutParams(lp);
    }

    private static void paintTrack(android.content.Context c, View box, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Theme.dp(c, 15.5f));
        g.setColor(on ? C_SWITCH_ON : C_SWITCH_OFF);
        box.setBackground(g);
    }

    /** 一行动作：标题 + 副标题 + 右侧箭头，整行可点。返回副标题（便于回调里改文案）。 */
    private static TextView addAction(Activity a, LinearLayout card, String title, String sub,
                                      android.view.View.OnClickListener l) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Theme.dp(a, 16), Theme.dp(a, 13), Theme.dp(a, 16), Theme.dp(a, 13));
        row.setClickable(true);
        row.setOnClickListener(l);

        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(17);
        t.setTextColor(C_TEXT);
        t.setIncludeFontPadding(false);
        texts.addView(t);

        TextView s = new TextView(a);
        s.setText(sub);
        s.setTextSize(13);
        s.setTextColor(C_SUB);
        s.setIncludeFontPadding(false);
        s.setPadding(0, Theme.dp(a, 4), Theme.dp(a, 8), 0);
        texts.addView(s);
        row.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chev = new TextView(a);
        chev.setText("›");
        chev.setTextSize(20);
        chev.setTextColor(C_SUB);
        chev.setIncludeFontPadding(false);
        row.addView(chev);
        card.addView(row);
        return s;
    }

    /** 「已隐藏 N 项」的副标题文案。 */
    private static String hiddenLine(Activity a) {
        int n = ScanHide.count(a);
        return n <= 0 ? "当前没有隐藏任何组件" : ("已隐藏 " + n + " 项，点此全部恢复");
    }

    /** 重建「已隐藏组件」清单：空态一行灰字，否则逐条（名称 + 规则 + 恢复）。 */
    private static void fillHiddenRows(final Activity a, LinearLayout card, final Runnable refresh) {
        card.removeAllViews();
        List<String[]> list;
        try {
            list = Config.overlayList(a);
        } catch (Throwable t) {
            list = new java.util.ArrayList<String[]>();
        }
        if (list.isEmpty()) {
            TextView empty = new TextView(a);
            empty.setText("当前没有隐藏任何组件");
            empty.setTextSize(13);
            empty.setTextColor(C_SUB);
            empty.setPadding(Theme.dp(a, 16), Theme.dp(a, 14), Theme.dp(a, 16), Theme.dp(a, 14));
            card.addView(empty);
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                addDivider(a, card);
            }
            addHiddenRow(a, card, list.get(i), refresh);
        }
    }

    /** 一条已隐藏规则：名称 + 原始规则小字 + 右侧「恢复」（只删这一条）。 */
    private static void addHiddenRow(final Activity a, LinearLayout card, final String[] item,
                                     final Runnable refresh) {
        String[] n = Names.describe(item[0]);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Theme.dp(a, 16), Theme.dp(a, 12), Theme.dp(a, 16), Theme.dp(a, 12));

        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(a);
        t.setText(n[0] + " " + n[1]);
        t.setTextSize(15);
        t.setTextColor(C_TEXT);
        t.setIncludeFontPadding(false);
        texts.addView(t);

        // 原始规则作为小字副标题（排查用，与红果清单一致）
        TextView rule = new TextView(a);
        rule.setText(item[0]);
        rule.setTextSize(11);
        rule.setTextColor(C_SUB);
        rule.setIncludeFontPadding(false);
        rule.setPadding(0, Theme.dp(a, 2), 0, 0);
        texts.addView(rule);
        row.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView restore = new TextView(a);
        restore.setText("恢复");
        restore.setTextSize(13);
        restore.setTextColor(C_ACCENT);
        restore.setIncludeFontPadding(false);
        restore.setPadding(Theme.dp(a, 10), Theme.dp(a, 5), Theme.dp(a, 10), Theme.dp(a, 5));
        restore.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                List<String[]> now = Config.overlayList(a);
                for (int i = 0; i < now.size(); i++) {
                    if (now.get(i)[0].equals(item[0])) {
                        now.remove(i);
                        break;
                    }
                }
                Config.setOverlayList(a, now);
                ScanHide.applyNow(a);
                android.widget.Toast.makeText(a, "已恢复：" + Names.describe(item[0])[1],
                        android.widget.Toast.LENGTH_SHORT).show();
                if (refresh != null) {
                    refresh.run();
                }
            }
        });
        row.addView(restore);
        card.addView(row);
    }

    /** 卡内分割线，左侧缩进 16dp。 */
    private static void addDivider(Activity a, LinearLayout card) {
        android.view.View v = new android.view.View(a);
        v.setBackgroundColor(C_DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Theme.dp(a, 0.5f)));
        lp.leftMargin = Theme.dp(a, 16);
        card.addView(v, lp);
    }
}
