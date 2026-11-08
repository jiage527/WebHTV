package com.fongmi.android.tv.ui.custom;

import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Adds a search box to the top of a settings page and hides the rows that do
 * not match the current query.
 *
 * <p>Rows are discovered from the layout rather than registered by hand, so a
 * page needs only one call and rows added later keep working. A row is matched
 * on two fields, both lowercased: its view id, which is the English preference
 * key (e.g. {@code seekStep}, {@code audioPassThrough}), and the text it
 * renders, which is the title in the language currently in use. A query is
 * split on whitespace and every word must match, so "audio pass" and "ab loop"
 * work. Since the key is the English identifier, an English query also matches
 * while the UI is in Chinese; a Chinese query matches through the title.
 *
 * <p>Non-matching rows are hidden with {@code GONE} and matching rows are shown
 * again; the rows stay in the container, so a page that reorders its own rows
 * (the enhance page does) keeps working. The visibility each row had before the
 * query was typed is captured as a baseline and restored exactly, so rows a
 * page hides for its own reasons come back hidden rather than becoming visible.
 * The box is inserted at the top but does not take focus, so the page's own
 * initial focus (a row, on TV) is unaffected.
 */
public final class SettingsSearchBar {

    private SettingsSearchBar() {
    }

    /**
     * Installs the search box at the top of the scrollable content inside
     * {@code pageRoot}. Does nothing when the page has no scrollable content or
     * too few rows to be worth searching. Safe to call again after the page has
     * re-initialised its rows; the box is reused, the query is cleared and the
     * rows are restored.
     */
    public static void attachToPage(View pageRoot) {
        if (pageRoot == null) return;
        ViewGroup content = findScrollContent(pageRoot);
        if (content == null) return;
        State existing = content.getTag() instanceof State state ? state : null;
        if (existing != null) {
            existing.refresh();
            return;
        }
        if (content.getChildCount() < 2) return;
        install(content);
    }

    private static void install(ViewGroup content) {
        EditText input = new EditText(content.getContext());
        input.setSingleLine(true);
        input.setHint(R.string.search_keyword);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setBackgroundResource(R.drawable.shape_danmaku_search_keyword);
        input.setTextColor(ResUtil.getColor(R.color.white));
        input.setHintTextColor(ResUtil.getColor(R.color.white_80));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = ResUtil.dp2px(16);
        input.setLayoutParams(params);
        // On a TV the on-screen keyboard is not shown automatically, so open it
        // when the box takes focus; on a phone this matches the usual behaviour.
        input.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) Util.showKeyboard(input);
        });

        State state = new State(content, input);
        content.addView(input, 0);
        content.setTag(state);
        state.capture();
        input.addTextChangedListener(state.watcher);
    }

    /** Finds the content container of the first scroll view in the page. */
    private static ViewGroup findScrollContent(View view) {
        if (view instanceof NestedScrollView || view instanceof ScrollView) {
            if (view instanceof ViewGroup group && group.getChildCount() > 0) {
                View child = group.getChildAt(0);
                if (child instanceof ViewGroup content) return content;
            }
            return null;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                ViewGroup found = findScrollContent(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean matches(View row, String query) {
        String key = keyOf(row);
        String haystack = (key == null ? "" : key) + ' ' + titleOf(row);
        haystack = normalize(haystack);
        for (String word : query.split("\\s+")) {
            if (!word.isEmpty() && !haystack.contains(normalize(word))) return false;
        }
        return true;
    }

    /** Folds case and separators so "a-b", "A_B" and "ab" all compare equal. */
    private static String normalize(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c)) out.append(Character.toLowerCase(c));
        }
        return out.toString();
    }

    /** The android:id name, which is the preference key, e.g. {@code seekStep}. */
    private static String keyOf(View row) {
        if (row.getId() == View.NO_ID) return null;
        try {
            return row.getResources().getResourceEntryName(row.getId());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** The rendered text of the row, collected from its text-bearing children. */
    private static String titleOf(View row) {
        StringBuilder text = new StringBuilder();
        collectText(row, text);
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private static void collectText(View view, StringBuilder out) {
        if (view instanceof TextView label) {
            if (label.getText() != null) out.append(label.getText()).append(' ');
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) collectText(group.getChildAt(i), out);
        }
    }

    private static final class State {

        final ViewGroup content;
        final EditText input;
        final TextWatcher watcher;
        List<View> rows = new ArrayList<>();
        /** Per row: the visibility to restore, or {@link View#NO_ID} when the search does not own it. */
        int[] saved = new int[0];

        State(ViewGroup content, EditText input) {
            this.content = content;
            this.input = input;
            this.watcher = new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    apply(s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT));
                }
            };
        }

        /** Re-snapshots the rows after a page re-init and restores all of them. */
        void refresh() {
            input.removeTextChangedListener(watcher);
            apply("");
            input.setText(null);
            capture();
            input.addTextChangedListener(watcher);
        }

        void capture() {
            rows = new ArrayList<>();
            for (int i = 0; i < content.getChildCount(); i++) {
                View child = content.getChildAt(i);
                if (child != input) rows.add(child);
            }
            saved = new int[rows.size()];
            reset();
        }

        /** Drops ownership of every row, leaving each one as the page left it. */
        void reset() {
            for (int i = 0; i < saved.length; i++) saved[i] = View.NO_ID;
        }

        /**
         * Hides rows that do not match and restores exactly the rows this query
         * hid, so a visibility the page changes while searching is not lost.
         */
        void apply(String query) {
            for (int i = 0; i < rows.size(); i++) {
                View row = rows.get(i);
                if (query.isEmpty() || matches(row, query)) {
                    if (i < saved.length && saved[i] != View.NO_ID) {
                        row.setVisibility(saved[i]);
                        saved[i] = View.NO_ID;
                    }
                } else if (i >= saved.length || saved[i] == View.NO_ID) {
                    if (row.getVisibility() == View.VISIBLE) {
                        if (i < saved.length) saved[i] = View.VISIBLE;
                        row.setVisibility(View.GONE);
                    }
                }
            }
        }
    }
}
