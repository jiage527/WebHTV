package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.AdapterArrayBinding;
import com.fongmi.android.tv.utils.EpisodeSeasonGrouping;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

public class ArrayAdapter extends RecyclerView.Adapter<ArrayAdapter.ViewHolder> {

    private final OnClickListener mListener;
    private final List<String> mItems;
    /** 与 {@link #mItems} 一一对应的跳转目标下标；优先于标签文本解析。 */
    private final List<Integer> mStarts;
    private final String backward;
    private final String forward;
    private final String reverse;
    private View.OnKeyListener keyListener;
    private int nextFocusDown;
    private int nextFocusUp;
    private int segmentSize;

    public ArrayAdapter(OnClickListener listener) {
        mListener = listener;
        mItems = new ArrayList<>();
        mStarts = new ArrayList<>();
        forward = ResUtil.getString(R.string.play_forward);
        reverse = ResUtil.getString(R.string.play_reverse);
        backward = ResUtil.getString(R.string.play_backward);
        nextFocusUp = R.id.flag;
        nextFocusDown = R.id.episode;
        segmentSize = 40;
    }

    public void addAll(List<String> items) {
        mItems.clear();
        mStarts.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    /**
     * 写入带显式跳转目标的分组。
     *
     * <p>季标签（如「第一季」）没有可解析的数字范围，只有把目标下标直接传进来才能
     * 正确跳转。列表长度不一致时忽略跳转表，退回标签解析。
     */
    public void addAll(List<String> items, List<Integer> starts) {
        mItems.clear();
        mStarts.clear();
        mItems.addAll(items);
        if (starts != null && starts.size() == items.size()) mStarts.addAll(starts);
        notifyDataSetChanged();
    }

    public void clear() {
        mItems.clear();
        mStarts.clear();
        notifyDataSetChanged();
    }

    public int getStart(int position) {
        if (position < 0 || position >= mItems.size()) return 0;
        if (position < mStarts.size()) return Math.max(0, mStarts.get(position));
        String text = mItems.get(position);
        int index = text.indexOf("-");
        if (index <= 0) return 0;
        try {
            int start = Integer.parseInt(text.substring(0, index));
            int end = Integer.parseInt(text.substring(index + 1));
            return Math.max(0, start <= end ? start - 1 : (position - 2) * segmentSize);
        } catch (Exception e) {
            return 0;
        }
    }

    public void setSegmentSize(int segmentSize) {
        this.segmentSize = Math.max(1, segmentSize);
    }

    /**
     * 当前分组栏是否带显式跳转表（即按季分组）。
     *
     * <p>调用方据此决定要不要跳过「只有分页数超过一段才响应」的判断：季分组通常
     * 只有十几个条目，沿用分页的判断会导致点季标签没反应。
     */
    public boolean hasExplicitStarts() {
        return !mStarts.isEmpty();
    }

    /**
     * 按「季」构建分组标签；识别不出多季时返回 null。
     *
     * @param items 剧集列表，顺序与界面展示一致
     */
    public static List<String> seasonLabels(List<Episode> items) {
        List<EpisodeSeasonGrouping.Season> seasons = seasons(items);
        return seasons.isEmpty() ? null : EpisodeSeasonGrouping.labels(seasons);
    }

    /** 与 {@link #seasonLabels} 一一对应的跳转目标下标；两者必须同步使用。 */
    public static List<Integer> seasonStarts(List<Episode> items) {
        List<EpisodeSeasonGrouping.Season> seasons = seasons(items);
        return seasons.isEmpty() ? null : EpisodeSeasonGrouping.starts(seasons);
    }

    private static List<EpisodeSeasonGrouping.Season> seasons(List<Episode> items) {
        if (items == null || items.isEmpty()) return new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Episode item : items) names.add(item.getRawDisplayName());
        return EpisodeSeasonGrouping.group(names);
    }

    public void setNextFocus(int nextFocusUp, int nextFocusDown) {
        if (this.nextFocusUp == nextFocusUp && this.nextFocusDown == nextFocusDown) return;
        this.nextFocusUp = nextFocusUp;
        this.nextFocusDown = nextFocusDown;
        notifyDataSetChanged();
    }

    public void setOnKeyListener(View.OnKeyListener keyListener) {
        this.keyListener = keyListener;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterArrayBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String text = mItems.get(position);
        holder.binding.text.setText(text);
        holder.binding.text.setNextFocusUpId(nextFocusUp == 0 ? View.NO_ID : nextFocusUp);
        holder.binding.text.setNextFocusDownId(nextFocusDown == 0 ? View.NO_ID : nextFocusDown);
        holder.binding.text.setOnKeyListener(keyListener);
        if (text.equals(reverse)) holder.binding.getRoot().setOnClickListener(view -> mListener.onRevSort());
        else if (text.equals(backward) || text.equals(forward)) holder.binding.getRoot().setOnClickListener(view -> mListener.onRevPlay(holder.binding.text));
        else holder.binding.getRoot().setOnClickListener(null);
    }

    public interface OnClickListener {

        void onRevSort();

        void onRevPlay(TextView view);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterArrayBinding binding;

        ViewHolder(@NonNull AdapterArrayBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
