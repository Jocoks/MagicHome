package com.dsh.magichome;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首次进入：指定「上滑要跳过去的第三方桌面」包名。
 * 全部界面用代码搭（模块没有 res 资源目录），保持单文件、构建简单。
 */
public class SettingsActivity extends Activity {

    private EditText mInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("MagicHome · 目标桌面");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("首次使用请指定「上滑后要进入的第三方桌面」的包名。\n"
                + "从下面列表点选，或手动输入包名后保存；保存后立即生效，无需重启。");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setTextColor(Color.DKGRAY);
        hint.setPadding(0, dp(8), 0, dp(12));
        root.addView(hint);

        mInput = new EditText(this);
        mInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        mInput.setSingleLine(true);
        mInput.setHint("com.teslacoilsw.launcher");
        SharedPreferences sp = getSharedPreferences(SettingsProvider.PREFS, MODE_PRIVATE);
        mInput.setText(sp.getString(SettingsProvider.KEY_TARGET, ""));
        root.addView(mInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button save = new Button(this);
        save.setText("保存并生效");
        save.setOnClickListener(v -> save());
        root.addView(save);

        TextView listTitle = new TextView(this);
        listTitle.setText("本机已安装的桌面：");
        listTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        listTitle.setPadding(0, dp(16), 0, dp(6));
        root.addView(listTitle);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Map<String, String> homes = installedHomes();
        if (homes.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("（没查到桌面应用，请手动输入包名）");
            empty.setTextColor(Color.GRAY);
            list.addView(empty);
        } else {
            for (Map.Entry<String, String> e : homes.entrySet()) {
                final String pkg = e.getKey();
                Button b = new Button(this);
                b.setAllCaps(false);
                b.setText(e.getValue() + "\n" + pkg);
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                b.setOnClickListener(v -> {
                    mInput.setText(pkg);
                    Toast.makeText(this, "已选择 " + pkg, Toast.LENGTH_SHORT).show();
                });
                list.addView(b, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }

        setContentView(root);
    }

    private Map<String, String> installedHomes() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> ris = getPackageManager()
                    .queryIntentActivities(home, PackageManager.MATCH_ALL);
            for (ResolveInfo ri : ris) {
                if (ri.activityInfo == null) {
                    continue;
                }
                String pkg = ri.activityInfo.packageName;
                String label = String.valueOf(ri.loadLabel(getPackageManager()));
                out.put(pkg, label);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private void save() {
        String pkg = mInput.getText() == null ? "" : mInput.getText().toString().trim();
        if (pkg.isEmpty()) {
            Toast.makeText(this, "请填写目标桌面包名", Toast.LENGTH_SHORT).show();
            return;
        }
        if (SettingsProvider.resolveHomeComponent(this, pkg) == null) {
            Toast.makeText(this, "没找到该包名的桌面组件：\n" + pkg, Toast.LENGTH_LONG).show();
            return;
        }
        getSharedPreferences(SettingsProvider.PREFS, MODE_PRIVATE)
                .edit().putString(SettingsProvider.KEY_TARGET, pkg).apply();
        Toast.makeText(this, "已保存：" + pkg + "\n回桌面后上滑即生效", Toast.LENGTH_LONG).show();
        finish();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
