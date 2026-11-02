package com.temvra.app;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Filas de los widgets con lista. Todo son vistas de texto normales, así nada se estira al cambiar de tamaño. */
public class WidgetListService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        Context c = getApplicationContext();
        String type = intent.getStringExtra("type");
        int id = intent.getIntExtra("id", 0);
        if (Widgets.LINE.equals(type)) return new LineFactory(c, id, intent.getIntExtra("wdp", 300));
        if (Widgets.NOTES.equals(type)) return new NotesFactory(c, id);
        if (Widgets.ALARMS.equals(type)) return new AlarmsFactory(c, intent.getFloatExtra("scale", 1f));
        if (Widgets.REG.equals(type)) return new RegFactory(c, id);
        return new Factory(c, type, intent.getBooleanExtra("narrow", false));
    }

    private abstract static class Base implements RemoteViewsFactory {
        final Context c;
        Base(Context c) { this.c = c; }
        @Override public void onCreate() {}
        @Override public void onDestroy() {}
        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean hasStableIds() { return false; }
    }

    private static void setFill(RemoteViews v, int view, String action) {
        Intent fill = new Intent();
        fill.putExtra(TemvraPlugin.EXTRA_ACTION, action);
        fill.setAction("com.temvra.app.row." + action);
        v.setOnClickFillInIntent(view, fill);
    }

    /* ---------- Calendario y bloques: lista de hoy ---------- */
    private static final class Factory extends Base {
        private final String type;
        private final boolean narrow;
        private List<JSONObject> rows = new ArrayList<>();
        private int accent = Widgets.DEF_ACCENT;

        Factory(Context c, String type, boolean narrow) { super(c); this.type = type; this.narrow = narrow; }

        @Override public void onDataSetChanged() {
            JSONObject d = Widgets.data(c);
            accent = Widgets.accent(d);
            rows = Widgets.rows(d, type);
        }
        @Override public int getCount() { return rows.size(); }
        @Override public int getViewTypeCount() { return 2; }

        @Override public RemoteViews getViewAt(int position) {
            if (position >= rows.size()) return null;
            JSONObject r = rows.get(position);
            if (r.optBoolean("now")) {
                RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_row_now);
                v.setTextViewText(R.id.wr_now_time, r.optString("t"));
                return v;
            }
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_row);
            String time = r.optString("s");
            v.setViewVisibility(R.id.wr_time, time.isEmpty() && narrow ? View.GONE : View.VISIBLE);
            v.setTextViewText(R.id.wr_time, time.isEmpty() ? "Día" : time);
            v.setTextViewText(R.id.wr_title, r.optString("t"));
            String sub = r.optString("sub");
            v.setTextViewText(R.id.wr_sub, sub);
            v.setViewVisibility(R.id.wr_sub, sub.isEmpty() || narrow ? View.GONE : View.VISIBLE);
            int color = accent;
            try { if (!r.optString("c").isEmpty()) color = Color.parseColor(r.optString("c")); } catch (Exception ignored) {}
            v.setInt(R.id.wr_bar, "setBackgroundColor", color);
            boolean past = r.optBoolean("past"), cur = r.optBoolean("cur");
            v.setTextColor(R.id.wr_title, past ? 0x80F4F4F6 : 0xFFF4F4F6);
            v.setTextColor(R.id.wr_sub, past ? 0x66A0A0A8 : 0xFFA0A0A8);
            v.setTextColor(R.id.wr_time, cur ? accent : past ? 0x66A0A0A8 : 0xFFA0A0A8);
            v.setInt(R.id.wr_root, "setBackgroundResource", cur ? R.drawable.widget_row_cur : R.drawable.widget_row_bg);
            if (cur) {
                v.setViewVisibility(R.id.wr_prog, View.VISIBLE);
                v.setProgressBar(R.id.wr_prog, 100, r.optInt("prog"), false);
            } else v.setViewVisibility(R.id.wr_prog, View.GONE);
            setFill(v, R.id.wr_root, r.optString("action"));
            return v;
        }
    }

    /* ---------- Notas en árbol ---------- */
    private static final class NotesFactory extends Base {
        private final int id;
        private final List<JSONObject> rows = new ArrayList<>();
        private int accent = Widgets.DEF_ACCENT;
        private float zoom = 1f;

        NotesFactory(Context c, int id) { super(c); this.id = id; }

        @Override public void onDataSetChanged() {
            rows.clear();
            JSONObject d = Widgets.data(c);
            accent = Widgets.accent(d);
            zoom = Widgets.zoomOf(c, id);
            JSONObject v = Widgets.vaultOf(c, id, d);
            if (v != null) Widgets.flattenTree(c, id, v, "", 0, rows);
        }
        @Override public int getCount() { return rows.size(); }

        @Override public RemoteViews getViewAt(int position) {
            if (position >= rows.size()) return null;
            JSONObject r = rows.get(position);
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_row_tree);
            int depth = r.optInt("depth");
            v.setViewPadding(R.id.wt_root, (int) ((6 + depth * 14) * c.getResources().getDisplayMetrics().density), 0, 8, 0);
            boolean folder = r.optBoolean("folder");
            v.setTextViewText(R.id.wt_mark, folder ? (r.optBoolean("open") ? "▾" : "▸") : "•");
            v.setTextViewText(R.id.wt_title, r.optString("t"));
            v.setTextViewTextSize(R.id.wt_title, TypedValue.COMPLEX_UNIT_SP, (folder ? 14.5f : 14f) * zoom);
            v.setTextViewTextSize(R.id.wt_mark, TypedValue.COMPLEX_UNIT_SP, 14f * zoom);
            v.setTextViewText(R.id.wt_count, folder ? r.optString("n") : "");
            int color = 0xFFF4F4F6;
            try { if (!r.optString("c").isEmpty()) color = Color.parseColor(r.optString("c")); } catch (Exception ignored) {}
            v.setTextColor(R.id.wt_title, color);
            v.setTextColor(R.id.wt_mark, folder ? accent : 0xFF77777E);
            setFill(v, R.id.wt_root, r.optString("action"));
            return v;
        }
    }

    /* ---------- Alarmas ---------- */
    private static final class AlarmsFactory extends Base {
        private final float scale;
        private JSONArray list = new JSONArray();
        private int accent = Widgets.DEF_ACCENT;

        AlarmsFactory(Context c, float scale) { super(c); this.scale = scale; }

        @Override public void onDataSetChanged() {
            JSONObject d = Widgets.data(c);
            accent = Widgets.accent(d);
            JSONArray a = d.optJSONArray("alarms");
            list = a != null ? a : new JSONArray();
        }
        @Override public int getCount() { return list.length(); }

        @Override public RemoteViews getViewAt(int position) {
            JSONObject a = list.optJSONObject(position);
            if (a == null) return null;
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_row_alarm);
            boolean on = a.optBoolean("on");
            v.setTextViewText(R.id.wa_time, a.optString("t"));
            v.setTextViewTextSize(R.id.wa_time, TypedValue.COMPLEX_UNIT_SP, 30f * scale);
            v.setTextViewText(R.id.wa_label, a.optString("label"));
            v.setTextViewTextSize(R.id.wa_label, TypedValue.COMPLEX_UNIT_SP, 13f * scale);
            v.setTextViewText(R.id.wa_state, on ? "●" : "○");
            v.setTextViewTextSize(R.id.wa_state, TypedValue.COMPLEX_UNIT_SP, 20f * scale);
            v.setTextColor(R.id.wa_state, on ? accent : 0xFF55555C);
            v.setTextColor(R.id.wa_time, on ? 0xFFF4F4F6 : 0x70F4F4F6);
            v.setTextColor(R.id.wa_label, on ? 0xFFA0A0A8 : 0x60A0A0A8);
            setFill(v, R.id.wa_root, "alarms");
            return v;
        }
    }

    /* ---------- Registro: lo apuntado por días ---------- */
    private static final class RegFactory extends Base {
        private final int id;
        private final List<JSONObject> rows = new ArrayList<>();
        private String action = "regs";

        RegFactory(Context c, int id) { super(c); this.id = id; }

        @Override public void onDataSetChanged() {
            rows.clear();
            JSONObject r = Widgets.regOf(c, id, Widgets.data(c));
            if (r == null) return;
            action = "reg:" + r.optString("id");
            JSONArray l = r.optJSONArray("rows");
            for (int i = 0; l != null && i < l.length(); i++) { JSONObject o = l.optJSONObject(i); if (o != null) rows.add(o); }
        }
        @Override public int getCount() { return rows.size(); }

        @Override public RemoteViews getViewAt(int position) {
            if (position >= rows.size()) return null;
            JSONObject o = rows.get(position);
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_row_reg);
            v.setTextViewText(R.id.wg_day, o.optString("d"));
            v.setTextViewText(R.id.wg_text, o.optString("t"));
            setFill(v, R.id.wg_root, action);
            return v;
        }
    }

    /* ---------- Línea de tiempo: 96 franjas de 15 min, así las cifras y el texto conservan siempre su proporción ---------- */
    private static final class LineFactory extends Base {
        private static final int[] CELLS = {R.id.wl_c0, R.id.wl_c1, R.id.wl_c2, R.id.wl_c3, R.id.wl_c4, R.id.wl_c5};
        private final int id, wdp;
        private final List<List<Ev>> cols = new ArrayList<>();
        private int off = 0, accent = Widgets.DEF_ACCENT;

        private static final class Ev { int s, e, lane, color; String title, sub, action; }

        LineFactory(Context c, int id, int wdp) { super(c); this.id = id; this.wdp = wdp; }

        private static int minutes(String hm) {
            try { return Integer.parseInt(hm.substring(0, 2)) * 60 + Integer.parseInt(hm.substring(3, 5)); }
            catch (Exception e) { return -1; }
        }

        @Override public void onDataSetChanged() {
            cols.clear();
            JSONObject d = Widgets.data(c);
            accent = Widgets.accent(d);
            off = Widgets.lineOffset(c, id);
            int n = Widgets.lineDays(c, id, wdp);
            for (int k = 0; k < n; k++) {
                List<Ev> evs = new ArrayList<>();
                JSONArray it = Widgets.lineItems(d, Widgets.dayAt(off + k));
                for (int i = 0; i < it.length(); i++) {
                    JSONObject o = it.optJSONObject(i);
                    if (o == null) continue;
                    Ev e = new Ev();
                    e.s = minutes(o.optString("s"));
                    if (e.s < 0) continue;
                    e.e = minutes(o.optString("e"));
                    if (e.e <= e.s) e.e = Math.min(1440, e.s + 30);
                    e.title = o.optString("t");
                    e.sub = o.optString("s") + (o.optString("e").isEmpty() ? "" : " – " + o.optString("e"));
                    e.action = o.optString("action");
                    e.color = accent;
                    try { if (!o.optString("c").isEmpty()) e.color = Color.parseColor(o.optString("c")); } catch (Exception ignored) {}
                    evs.add(e);
                }
                evs.sort((x, y) -> x.s - y.s);
                int[] laneEnd = {0, 0};
                for (Ev e : evs) {
                    e.lane = laneEnd[0] <= e.s ? 0 : 1;
                    laneEnd[e.lane] = Math.max(laneEnd[e.lane], e.e);
                }
                cols.add(evs);
            }
        }

        @Override public int getCount() { return 96; }
        @Override public boolean hasStableIds() { return true; }

        @Override public RemoteViews getViewAt(int slot) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_row_line);
            int top = slot * 15, bottom = top + 15;
            Calendar now = Calendar.getInstance();
            int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
            boolean nowHere = off <= 0 && off > -Math.max(1, cols.size()) && nowMin >= top && nowMin < bottom;
            boolean hour = slot % 4 == 0;
            v.setInt(R.id.wl_rule, "setBackgroundColor", nowHere ? 0xFFFF5C60 : hour ? 0xFF2C2C32 : 0x00000000);
            v.setTextViewText(R.id.wl_hour, nowHere ? String.format(Locale.ROOT, "%02d:%02d", nowMin / 60, nowMin % 60) : hour ? String.format(Locale.ROOT, "%02d:00", slot / 4) : "");
            v.setTextColor(R.id.wl_hour, nowHere ? 0xFFFF5C60 : 0xFFA0A0A8);
            for (int i = 0; i < CELLS.length; i++) v.setViewVisibility(CELLS[i], View.GONE);
            for (int k = 0; k < cols.size() && k < 3; k++) {
                Ev[] lane = new Ev[2];
                boolean two = false;
                for (Ev e : cols.get(k)) {
                    if (e.e > top && e.s < bottom) lane[e.lane] = e;
                    if (e.lane == 1 && e.e > top - 240 && e.s < bottom + 240) two = true;
                }
                paint(v, CELLS[k * 2], lane[0], top);
                if (two) paint(v, CELLS[k * 2 + 1], lane[1], top);
            }
            return v;
        }

        private void paint(RemoteViews v, int cell, Ev e, int top) {
            v.setViewVisibility(cell, View.VISIBLE);
            if (e == null) {
                v.setTextViewText(cell, "");
                v.setInt(cell, "setBackgroundResource", 0);
                return;
            }
            boolean first = e.s >= top, last = e.e <= top + 15;
            int shape = first && last ? R.drawable.wl_solo : first ? R.drawable.wl_top : last ? R.drawable.wl_bot : R.drawable.wl_mid;
            v.setInt(cell, "setBackgroundResource", shape);
            int soft = (e.color & 0x00FFFFFF) | 0x66000000;
            if (Build.VERSION.SDK_INT >= 31) v.setColorStateList(cell, "setBackgroundTintList", ColorStateList.valueOf(soft));
            else v.setInt(cell, "setBackgroundColor", soft);
            int firstSlot = e.s / 15 * 15;
            String txt = top == firstSlot ? e.title : top == firstSlot + 15 ? e.sub : "";
            v.setTextViewText(cell, txt);
            v.setTextColor(cell, top == firstSlot ? 0xFFF4F4F6 : 0xFFC8C8CE);
            setFill(v, cell, e.action.isEmpty() ? "agenda" : e.action);
        }
    }
}
