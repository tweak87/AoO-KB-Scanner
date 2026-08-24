package com.tweak87.aookbscanner.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.tweak87.aookbscanner.db.ScannerDatabase;
import com.tweak87.aookbscanner.db.ScannerDatabase.ReportRow;
import com.tweak87.aookbscanner.util.Ui;

import java.util.ArrayList;
import java.util.List;

public final class ReportsActivity extends Activity {
    private final List<ReportRow> rows = new ArrayList<>();
    private ArrayAdapter<ReportRow> adapter;
    private ScannerDatabase database;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Erfasste Berichte");
        database = new ScannerDatabase(this);
        ListView list = new ListView(this);
        list.setBackgroundColor(Ui.NAVY);
        list.setDividerHeight(Ui.dp(this, 8));
        list.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        adapter = new ArrayAdapter<ReportRow>(this, android.R.layout.simple_list_item_1, rows) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(Ui.WHITE);
                view.setTextSize(16);
                view.setPadding(Ui.dp(ReportsActivity.this, 14), Ui.dp(ReportsActivity.this, 12),
                        Ui.dp(ReportsActivity.this, 14), Ui.dp(ReportsActivity.this, 12));
                view.setBackground(Ui.rounded(Ui.PANEL, Ui.dp(ReportsActivity.this, 10), 0, 0));
                return view;
            }
        };
        list.setAdapter(adapter);
        TextView empty = emptyView();
        FrameLayout listRoot = new FrameLayout(this);
        listRoot.setBackgroundColor(Ui.NAVY);
        listRoot.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams emptyParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        emptyParams.gravity = android.view.Gravity.CENTER;
        listRoot.addView(empty, emptyParams);
        list.setEmptyView(empty);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Intent detail = new Intent(this, ReportDetailActivity.class);
            detail.putExtra(ReportDetailActivity.EXTRA_REPORT_ID, rows.get(position).id);
            startActivity(detail);
        });
        LinearLayout page = Ui.verticalPage(this);
        page.addView(Ui.backHeader(this, "Erfasste Berichte"));
        page.addView(listRoot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(page);
    }

    private TextView emptyView() {
        TextView empty = Ui.text(this, "Noch keine Berichte erfasst.", 17, Ui.MUTED);
        return empty;
    }

    @Override protected void onResume() {
        super.onResume();
        rows.clear();
        rows.addAll(database.listReports());
        adapter.notifyDataSetChanged();
    }
}
