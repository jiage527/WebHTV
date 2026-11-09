package com.fongmi.android.tv.ui.dialog;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.common.C;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.PlayerHelper;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.subtitle.OnlineSubtitle;
import com.fongmi.android.tv.player.subtitle.OnlineSubtitleRepository;
import com.fongmi.android.tv.player.subtitle.SubtitleMatchPolicy;
import com.fongmi.android.tv.ui.custom.CustomRecyclerView;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.crawler.SpiderDebug;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textview.MaterialTextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Searches the online subtitle providers for the item being played and applies
 * the chosen result.
 *
 * <p>The dialog mirrors the danmaku search the viewer already knows (keyword
 * row, a row of language choices, a result list) so online subtitles need no new
 * interaction model. All network work happens on a background thread and only
 * the resulting list is posted back, because both providers are queried
 * synchronously; a search that is superseded by a newer one is discarded rather
 * than allowed to overwrite the newer results.
 *
 * <p>Applying a result goes through the same {@link Sub} path as a subtitle
 * chosen from a file or produced by the translator, so every playback engine
 * keeps its existing behaviour.
 */
public final class SubtitleSearchDialog extends DialogFragment {

    private final ResultAdapter adapter;
    private final List<MaterialTextView> languageViews;
    private TextInputEditText keywordView;
    private HorizontalScrollView languageScroller;
    private LinearLayout languageTabs;
    private MaterialTextView searchView;
    private CustomRecyclerView recycler;
    private CircularProgressIndicator progress;
    private TextView empty;
    private PlayerManager player;
    private List<String> profiles;
    private int profileIndex;
    private int requestSeq;
    private volatile boolean dismissed;

    public static SubtitleSearchDialog create() {
        return new SubtitleSearchDialog();
    }

    public SubtitleSearchDialog() {
        this.adapter = new ResultAdapter(this::onItemClick);
        this.languageViews = new ArrayList<>();
        this.profiles = SubtitleMatchPolicy.profiles();
        this.profileIndex = 0;
    }

    public SubtitleSearchDialog player(PlayerManager player) {
        this.player = player;
        return this;
    }

    public void show(FragmentActivity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof SubtitleSearchDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = new Dialog(requireContext());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(createContentView());
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnShowListener(d -> {
            bindEvents();
            setKeyword(initialKeyword());
            if (!getKeywordText().isEmpty()) keywordView.post(this::search);
            else Util.showKeyboard(keywordView);
        });
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        Window window = dialog == null ? null : dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Util.isFullscreen(getActivity())) Util.hideSystemUI(window);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        boolean landscape = metrics.widthPixels > metrics.heightPixels;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.copyFrom(window.getAttributes());
        params.width = landscape || Util.isLeanback() ? Math.min((int) (metrics.widthPixels * 0.76f), dp(920)) : Math.min(metrics.widthPixels - dp(32), dp(560));
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        window.setAttributes(params);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // A search in flight must not touch views that no longer exist.
        dismissed = true;
        requestSeq++;
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        dismissed = true;
    }

    private LinearLayout createContentView() {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(22), dp(24), dp(24));
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.setBackground(round(Color.parseColor("#F226282C"), 10, Color.TRANSPARENT));
        root.addView(createTitleView(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)));
        root.addView(createSearchRow(), createTopParams(16));
        root.addView(createLanguageScroller(), createTopParams(14));
        root.addView(createResultFrame(), createTopParams(16));
        return root;
    }

    private MaterialTextView createTitleView() {
        MaterialTextView title = new MaterialTextView(requireContext());
        title.setText(R.string.subtitle_search);
        title.setTextColor(Color.WHITE);
        title.setTextSize(Util.isLeanback() ? 20 : 18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        return title;
    }

    private LinearLayout createSearchRow() {
        keywordView = createKeywordView();
        searchView = createSearchButton();
        keywordView.setId(View.generateViewId());
        searchView.setId(View.generateViewId());
        keywordView.setNextFocusRightId(searchView.getId());
        searchView.setNextFocusLeftId(keywordView.getId());

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(keywordView, new LinearLayout.LayoutParams(0, dp(Util.isLeanback() ? 48 : 44), 1));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(dp(Util.isLeanback() ? 112 : 92), dp(Util.isLeanback() ? 48 : 44));
        actionParams.setMarginStart(dp(Util.isLeanback() ? 14 : 10));
        row.addView(searchView, actionParams);
        return row;
    }

    private TextInputEditText createKeywordView() {
        TextInputEditText edit = new TextInputEditText(requireContext());
        edit.setSingleLine(true);
        edit.setMaxLines(1);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        edit.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        edit.setTextColor(Color.WHITE);
        edit.setHintTextColor(Color.parseColor("#99FFFFFF"));
        edit.setTextSize(Util.isLeanback() ? 16 : 14);
        edit.setHint(R.string.subtitle_search_keyword);
        edit.setPadding(dp(14), 0, dp(14), 0);
        edit.setBackground(round(Color.parseColor("#263A3C41"), 9, Color.parseColor("#33FFFFFF")));
        return edit;
    }

    private MaterialTextView createSearchButton() {
        MaterialTextView view = new MaterialTextView(requireContext());
        view.setText(R.string.play_search);
        view.setTextColor(Color.WHITE);
        view.setTextSize(Util.isLeanback() ? 16 : 14);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setClickable(true);
        view.setFocusable(true);
        view.setBackground(buttonBackground("#FF1A73E8", "#CC1A73E8", 7));
        return view;
    }

    private HorizontalScrollView createLanguageScroller() {
        languageTabs = new LinearLayout(requireContext());
        languageTabs.setOrientation(LinearLayout.HORIZONTAL);
        languageTabs.setGravity(Gravity.CENTER_VERTICAL);

        for (int i = 0; i < profiles.size(); i++) {
            MaterialTextView view = createLanguageView(profiles.get(i), i);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(Util.isLeanback() ? 42 : 36), 1);
            if (i > 0) params.setMarginStart(dp(Util.isLeanback() ? 10 : 8));
            languageTabs.addView(view, params);
            languageViews.add(view);
        }
        updateLanguageTabs();

        languageScroller = new HorizontalScrollView(requireContext());
        languageScroller.setHorizontalScrollBarEnabled(false);
        languageScroller.setFillViewport(true);
        languageScroller.setOverScrollMode(HorizontalScrollView.OVER_SCROLL_NEVER);
        languageScroller.addView(languageTabs, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        return languageScroller;
    }

    private MaterialTextView createLanguageView(String profile, int index) {
        MaterialTextView view = new MaterialTextView(requireContext());
        view.setText(SubtitleMatchPolicy.languageLabel(SubtitleMatchPolicy.openSubtitlesIds(List.of(profile)).get(0)));
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setTextSize(Util.isLeanback() ? 15 : 13);
        view.setTextColor(languageTextColor());
        view.setBackground(sourceBackground());
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(v -> onLanguageClick(index));
        return view;
    }

    private FrameLayout createResultFrame() {
        FrameLayout frame = new FrameLayout(requireContext());
        frame.setClipChildren(false);
        frame.setClipToPadding(false);

        progress = new CircularProgressIndicator(requireContext());
        progress.setIndeterminate(true);
        progress.setIndicatorColor(Color.parseColor("#E6FFFFFF"));
        progress.setIndicatorSize(dp(Util.isLeanback() ? 36 : 32));
        progress.setTrackThickness(dp(Util.isLeanback() ? 3 : 2));
        progress.setVisibility(GONE);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        progressParams.setMargins(0, dp(24), 0, dp(24));
        frame.addView(progress, progressParams);

        empty = new TextView(requireContext());
        empty.setGravity(Gravity.CENTER);
        empty.setText(R.string.subtitle_search_empty);
        empty.setTextColor(Color.parseColor("#99FFFFFF"));
        empty.setTextSize(Util.isLeanback() ? 16 : 14);
        empty.setVisibility(GONE);
        frame.addView(empty, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(Util.isLeanback() ? 96 : 72), Gravity.CENTER));

        recycler = new CustomRecyclerView(requireContext());
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setItemAnimator(null);
        recycler.setHasFixedSize(false);
        recycler.setMaxHeight(dp(Util.isLeanback() ? 312 : 260));
        recycler.setAdapter(adapter);
        recycler.setVisibility(GONE);
        recycler.setClipChildren(false);
        recycler.setClipToPadding(false);
        recycler.setId(View.generateViewId());
        keywordView.setNextFocusDownId(recycler.getId());
        searchView.setNextFocusDownId(recycler.getId());
        frame.addView(recycler, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        return frame;
    }

    private LinearLayout.LayoutParams createTopParams(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMargin);
        return params;
    }

    private void bindEvents() {
        keywordView.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH && !getKeywordText().isEmpty()) search();
            return true;
        });
        keywordView.setOnKeyListener((view, keyCode, event) -> focusResultOnDown(event));
        searchView.setOnKeyListener((view, keyCode, event) -> focusResultOnDown(event));
        searchView.setOnClickListener(view -> {
            if (!getKeywordText().isEmpty()) search();
        });
    }

    private boolean focusResultOnDown(android.view.KeyEvent event) {
        if (KeyUtil.isActionDown(event) && KeyUtil.isDownKey(event) && recycler.getVisibility() == VISIBLE) {
            recycler.requestFocus();
            return true;
        }
        return false;
    }

    /** The title and episode the item is playing, from the metadata the player already holds. */
    private SubtitleMatchPolicy.MediaQuery query() {
        androidx.media3.common.MediaMetadata metadata = player == null ? null : player.getMetadata();
        String title = metadata == null || metadata.title == null ? "" : metadata.title.toString();
        String episode = metadata == null || metadata.artist == null ? "" : metadata.artist.toString();
        boolean series = looksLikeEpisode(episode);
        double duration = player == null ? 0 : player.getDuration() / 1000.0;
        return new SubtitleMatchPolicy.MediaQuery(title, episode, series, duration);
    }

    /**
     * A series is recognised from the episode label the metadata carries, e.g.
     * "S01E02" or "第 12 集". A movie has an empty label or the title repeated.
     */
    private boolean looksLikeEpisode(String episode) {
        if (TextUtils.isEmpty(episode)) return false;
        return episode.matches("(?i).*\\bS\\d{1,2}E\\d{1,3}\\b.*") || episode.matches(".*第\\s*\\d+\\s*[集話话].*");
    }

    private String initialKeyword() {
        return SubtitleMatchPolicy.buildQuery(query());
    }

    private String getKeywordText() {
        return keywordView == null || keywordView.getText() == null ? "" : keywordView.getText().toString().trim();
    }

    private void setKeyword(CharSequence text) {
        CharSequence value = text == null ? "" : text;
        keywordView.setText(value);
        keywordView.setSelection(value.length());
    }

    private void onLanguageClick(int index) {
        if (index == profileIndex) return;
        profileIndex = index;
        updateLanguageTabs();
        if (!getKeywordText().isEmpty()) search();
    }

    private void updateLanguageTabs() {
        for (int i = 0; i < languageViews.size(); i++) languageViews.get(i).setSelected(i == profileIndex);
    }

    private void showProgress() {
        adapter.clear();
        empty.setVisibility(GONE);
        recycler.setVisibility(GONE);
        progress.setVisibility(VISIBLE);
    }

    private void search() {
        String keyword = getKeywordText();
        if (keyword.isEmpty()) {
            // Nothing to search for: return focus to the field instead of firing a
            // request that the providers would answer with their whole catalogue.
            Util.showKeyboard(keywordView);
            return;
        }
        Util.hideKeyboard(keywordView);
        showProgress();
        int seq = ++requestSeq;
        // The keyword is used as typed: the automatic keyword comes from the
        // playing item, and a viewer who edits it expects exactly that text to be
        // searched for.
        SubtitleMatchPolicy.MediaQuery media = query();
        List<String> selected = List.of(profiles.get(profileIndex));
        Task.execute(() -> {
            List<OnlineSubtitle> items = OnlineSubtitleRepository.search(new SubtitleMatchPolicy.MediaQuery(
                    keyword, media.episode(), media.series(), media.durationSec()), selected);
            App.post(() -> onResults(seq, items));
        });
    }

    private void onResults(int seq, List<OnlineSubtitle> items) {
        if (dismissed || seq != requestSeq) return;
        progress.setVisibility(GONE);
        adapter.replaceAll(items);
        boolean nothing = items.isEmpty();
        recycler.setVisibility(nothing ? GONE : VISIBLE);
        empty.setVisibility(nothing ? VISIBLE : GONE);
        if (!nothing && Util.isLeanback()) recycler.requestFocus();
    }

    /**
     * Downloads the chosen result and applies it. The download is a second
     * network round trip (and, for SubHD, a small handshake), so it runs on the
     * same background thread; playback position is preserved across the track
     * rebuild exactly as the translator preserves it.
     */
    private void onItemClick(OnlineSubtitle item) {
        if (player == null) return;
        Notify.show(R.string.subtitle_search_downloading);
        long position = player.getPosition();
        Task.execute(() -> {
            String url = OnlineSubtitleRepository.download(item);
            App.post(() -> applyResult(url, item, position));
        });
    }

    private void applyResult(String url, OnlineSubtitle item, long position) {
        Notify.dismiss();
        if (dismissed) return;
        if (url == null || url.isEmpty()) {
            Notify.show(R.string.subtitle_search_download_failed);
            return;
        }
        if (SpiderDebug.isEnabled()) SpiderDebug.log("subtitle", "online apply source=%s name=%s url=%s", item.getSource(), item.getName(), url);
        Sub sub = Sub.create(TextUtils.isEmpty(item.getName()) ? getString(R.string.subtitle_search) : item.getName(),
                url, SubtitleMatchPolicy.languageLabel(item.getLanguage()), PlayerHelper.getSubtitleMimeType(url));
        sub.setFlag(C.SELECTION_FLAG_DEFAULT);
        player.setSub(sub);
        if (position > 0) App.post(() -> player.seekTo(position), 800);
        Notify.show(R.string.subtitle_search_applied);
        dismissAllowingStateLoss();
    }

    private Drawable round(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private Drawable buttonBackground(String focused, String normal, int radius) {
        GradientDrawable focusedDrawable = new GradientDrawable();
        focusedDrawable.setColor(Color.parseColor(focused));
        focusedDrawable.setCornerRadius(dp(radius));
        focusedDrawable.setStroke(dp(2), Color.WHITE);

        GradientDrawable normalDrawable = new GradientDrawable();
        normalDrawable.setColor(Color.parseColor(normal));
        normalDrawable.setCornerRadius(dp(radius));
        return new android.graphics.drawable.StateListDrawable() {{
            addState(new int[]{android.R.attr.state_focused}, focusedDrawable);
            addState(new int[]{}, normalDrawable);
        }};
    }

    private Drawable sourceBackground() {
        GradientDrawable selected = new GradientDrawable();
        selected.setColor(Color.parseColor("#FF1A73E8"));
        selected.setCornerRadius(dp(7));
        selected.setStroke(dp(1), Color.parseColor("#806FA8FF"));

        GradientDrawable focused = new GradientDrawable();
        focused.setColor(Color.parseColor("#663A7AFE"));
        focused.setCornerRadius(dp(7));
        focused.setStroke(dp(2), Color.WHITE);

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(Color.parseColor("#2EFFFFFF"));
        normal.setCornerRadius(dp(7));
        normal.setStroke(dp(1), Color.parseColor("#24FFFFFF"));

        return new android.graphics.drawable.StateListDrawable() {{
            addState(new int[]{android.R.attr.state_focused, android.R.attr.state_selected}, focused);
            addState(new int[]{android.R.attr.state_selected}, selected);
            addState(new int[]{android.R.attr.state_focused}, focused);
            addState(new int[]{}, normal);
        }};
    }

    private android.content.res.ColorStateList languageTextColor() {
        return new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_selected}, new int[]{android.R.attr.state_focused}, new int[]{}},
                new int[]{Color.WHITE, Color.WHITE, Color.parseColor("#E6FFFFFF")});
    }

    private int dp(int value) {
        return ResUtil.dp2px(value);
    }

    private static final class ResultAdapter extends RecyclerView.Adapter<ResultAdapter.ViewHolder> {

        private final OnClickListener listener;
        private final List<OnlineSubtitle> items;

        private interface OnClickListener {

            void onItemClick(OnlineSubtitle item);
        }

        private ResultAdapter(OnClickListener listener) {
            this.listener = listener;
            this.items = new ArrayList<>();
        }

        private void clear() {
            int size = items.size();
            items.clear();
            if (size > 0) notifyItemRangeRemoved(0, size);
        }

        private void replaceAll(List<OnlineSubtitle> values) {
            clear();
            if (values == null || values.isEmpty()) return;
            items.addAll(values);
            notifyItemRangeInserted(0, values.size());
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, 0, dp(parent.getContext(), Util.isLeanback() ? 12 : 10));
            row.setLayoutParams(params);
            row.setPadding(dp(parent.getContext(), Util.isLeanback() ? 18 : 12), dp(parent.getContext(), 8), dp(parent.getContext(), Util.isLeanback() ? 18 : 12), dp(parent.getContext(), 8));

            MaterialTextView title = new MaterialTextView(parent.getContext());
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            title.setTextSize(Util.isLeanback() ? 16 : 14);
            title.setTextColor(Color.parseColor("#E6FFFFFF"));
            row.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            MaterialTextView detail = new MaterialTextView(parent.getContext());
            detail.setSingleLine(true);
            detail.setEllipsize(TextUtils.TruncateAt.END);
            detail.setTextSize(Util.isLeanback() ? 13 : 12);
            detail.setTextColor(Color.parseColor("#99FFFFFF"));
            LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            detailParams.topMargin = dp(parent.getContext(), 4);
            row.addView(detail, detailParams);

            row.setClickable(true);
            row.setFocusable(true);
            return new ViewHolder(row, title, detail);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.bind(items.get(position));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        private final class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {

            private final MaterialTextView title;
            private final MaterialTextView detail;

            private ViewHolder(@NonNull LinearLayout row, MaterialTextView title, MaterialTextView detail) {
                super(row);
                this.title = title;
                this.detail = detail;
                row.setOnClickListener(this);
            }

            private void bind(OnlineSubtitle item) {
                String name = item.getRelease().isEmpty() ? item.getName() : item.getRelease();
                title.setText(TextUtils.isEmpty(name) ? item.getSource() : name);
                String describe = item.describe();
                detail.setText(describe.isEmpty() ? item.getSource() : item.getSource() + " · " + describe);
                itemView.setBackground(rowBackground(itemView.getContext()));
            }

            @Override
            public void onClick(View view) {
                int position = getBindingAdapterPosition();
                if (position == RecyclerView.NO_POSITION) return;
                listener.onItemClick(items.get(position));
            }
        }

        private static Drawable rowBackground(Context context) {
            GradientDrawable focused = row(Color.parseColor("#333A7AFE"), Color.WHITE, context);
            GradientDrawable normal = row(Color.parseColor("#18FFFFFF"), Color.parseColor("#24FFFFFF"), context);
            return new android.graphics.drawable.StateListDrawable() {{
                addState(new int[]{android.R.attr.state_focused}, focused);
                addState(new int[]{android.R.attr.state_pressed}, focused);
                addState(new int[]{}, normal);
            }};
        }

        private static GradientDrawable row(int color, int stroke, Context context) {
            GradientDrawable drawable = new GradientDrawable();
            drawable.setColor(color);
            drawable.setCornerRadius(dp(context, 7));
            drawable.setStroke(dp(context, stroke == Color.WHITE ? 2 : 1), stroke);
            return drawable;
        }

        private static int dp(Context context, int value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }
    }
}
