package org.goathouse.kiosk;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * To-do rows shared by the controls and the hallway brief. Only the circle checks an item off (or back on); tapping
 * the text expands it to the full label plus due date and notes. Open items first, then a "Completed" section.
 */
final class TaskRows {
    private TaskRows() { }

    private static final Set<String> expanded = new HashSet<>();

    /** Fetches every item (open and completed) and renders them into {@code list}. */
    static void load(Context c, Ha ha, String todo, LinearLayout list, TextView count) { load(c, ha, todo, list, count, true); }

    /** showCompleted=false: only open items (the kitchen shopping list). */
    static void load(Context c, Ha ha, String todo, LinearLayout list, TextView count, boolean showCompleted) {
        if (todo == null || todo.isEmpty() || ha.states.get(todo) == null) { list.removeAllViews(); if (count != null) count.setText(""); return; }
        try {
            JSONObject q = showCompleted ? new JSONObject() : new JSONObject().put("status", new JSONArray().put("needs_action"));
            ha.callService("todo", "get_items", todo, q, true, (res, err) -> {
                JSONObject r = res == null ? null : res.optJSONObject("response");
                JSONObject mine = r == null ? null : r.optJSONObject(todo);
                render(c, ha, todo, mine == null ? new JSONArray() : mine.optJSONArray("items"), list, count);
            });
        } catch (Exception ignored) { }
    }

    static void render(Context c, Ha ha, String todo, JSONArray items, LinearLayout list, TextView count) {
        list.removeAllViews();
        int n = items == null ? 0 : items.length(), open = 0, done = 0;
        for (int i = 0; i < n; i++) if ("completed".equals(items.optJSONObject(i).optString("status"))) done++; else open++;
        if (count != null) count.setText(open == 1 ? "1 open" : open + " open");
        for (int pass = 0; pass < 2; pass++) {
            boolean completed = pass == 1;
            if (completed && done > 0) {
                TextView h = Style.tcaps(c, "Completed · " + done, 12, Style.WHITE60);
                h.setPadding(0, Style.u(16), 0, Style.u(4));
                list.addView(h);
            }
            for (int i = 0; i < n; i++) {
                JSONObject it = items.optJSONObject(i);
                if ("completed".equals(it.optString("status")) != completed) continue;
                list.addView(row(c, ha, todo, it, completed, list, count));
            }
        }
        if (n == 0) list.addView(Style.t(c, "Nothing here", 16, 400, Style.WHITE60));
    }

    private static View row(Context c, Ha ha, String todo, JSONObject it, boolean completed, LinearLayout list, TextView count) {
        String summary = it.optString("summary"), uid = it.optString("uid", summary);
        String due = it.optString("due", ""), notes = it.optString("description", "");
        boolean open = expanded.contains(uid);

        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.TOP);
        r.setPadding(0, Style.u(6), 0, Style.u(6));

        // the circle: its own big touch target, the only thing that checks/unchecks
        FrameLayout hit = new FrameLayout(c);
        View box = new View(c);
        GradientDrawable g = Style.fill(completed ? 0x8CFFFFFF : 0, Style.u(11));
        g.setStroke(Style.u(2), completed ? 0x8CFFFFFF : 0xBFFFFFFF);
        box.setBackground(g);
        hit.addView(box, new FrameLayout.LayoutParams(Style.u(22), Style.u(22), Gravity.CENTER));
        hit.setContentDescription(completed ? "Mark not done" : "Mark done");
        hit.setOnClickListener(v -> {
            g.setColor(completed ? 0 : Style.CREAM);
            r.animate().alpha(0.35f).setDuration(200).start();
            try {
                ha.callService("todo", "update_item", todo, new JSONObject().put("item", uid).put("status", completed ? "needs_action" : "completed"));
            } catch (Exception ignored) { }
            r.postDelayed(() -> load(c, ha, todo, list, count), 700);
        });
        r.addView(hit, new LinearLayout.LayoutParams(Style.u(44), Style.u(40)));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Style.u(4), Style.u(6), 0, Style.u(6));
        TextView name = Style.t(c, summary, 18, 400, completed ? Style.WHITE60 : Style.WHITE);
        if (completed) name.setPaintFlags(name.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        if (!open) { name.setMaxLines(1); name.setEllipsize(TextUtils.TruncateAt.END); }
        col.addView(name);
        if (open) {
            StringBuilder d = new StringBuilder();
            if (!due.isEmpty()) d.append("Due ").append(due.length() > 10 ? due.substring(0, 16).replace('T', ' ') : due);
            if (!notes.isEmpty()) d.append(d.length() > 0 ? "\n" : "").append(notes);
            if (d.length() == 0) d.append(completed ? "Completed" : "No details");
            TextView det = Style.t(c, d.toString(), 14, 400, Style.WHITE70);
            det.setPadding(0, Style.u(4), 0, 0);
            col.addView(det);
        } else if (!due.isEmpty() && !completed) {
            col.addView(Style.t(c, "due " + due.substring(0, Math.min(10, due.length())), 13, 500, Style.AMBER));
        }
        col.setOnClickListener(v -> {
            if (!expanded.remove(uid)) expanded.add(uid);
            int idx = list.indexOfChild(r);
            if (idx >= 0) { list.removeViewAt(idx); list.addView(row(c, ha, todo, it, completed, list, count), idx); }
        });
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        return r;
    }
}
