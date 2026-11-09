package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.AdapterEpisodeGroupBinding;
import com.fongmi.android.tv.utils.EpisodeSeasonGrouping;

import java.util.ArrayList;
import java.util.List;

public class EpisodeGroupAdapter extends RecyclerView.Adapter<EpisodeGroupAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<Group> items;

    public EpisodeGroupAdapter(OnClickListener listener) {
        this.listener = listener;
        this.items = new ArrayList<>();
    }

    public interface OnClickListener {

        void onItemClick(Group item);
    }

    public void addAll(List<Group> items) {
        this.items.clear();
        this.items.addAll(items);
        notifyDataSetChanged();
    }

    public List<Group> getItems() {
        return items;
    }

    public int getPosition() {
        for (int i = 0; i < items.size(); i++) if (items.get(i).selected) return i;
        return 0;
    }

    public void setSelected(Group group) {
        for (Group item : items) item.selected = item == group;
        notifyItemRangeChanged(0, getItemCount());
    }

    public boolean isEmpty() {
        return getItemCount() == 0;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterEpisodeGroupBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Group item = items.get(position);
        holder.binding.text.setText(item.name);
        holder.binding.text.setSelected(item.selected);
        holder.binding.text.setOnClickListener(v -> listener.onItemClick(item));
    }

    public static List<Group> build(int size, int selectedIndex, boolean reverse) {
        List<Group> groups = new ArrayList<>();
        if (size <= 0) return groups;
        int groupSize = getGroupSize(size);
        int count = (int) Math.ceil(size / (float) groupSize);
        for (int i = 0; i < count; i++) {
            int start = i * groupSize;
            int end = Math.min(start + groupSize, size);
            int labelStart = reverse ? size - start : start + 1;
            int labelEnd = reverse ? size - end + 1 : end;
            Group group = new Group(Math.max(labelStart, labelEnd) == Math.min(labelStart, labelEnd) ? String.valueOf(labelStart) : labelStart + "-" + labelEnd, start, end);
            group.selected = selectedIndex >= start && selectedIndex < end;
            groups.add(group);
        }
        if (groups.stream().noneMatch(group -> group.selected)) groups.get(0).selected = true;
        return groups;
    }

    /**
     * 构建分组栏。
     *
     * <p>优先按「季」分组（片名里有两个及以上不同季时），否则退回按序号等分翻页：
     * 多数片源并不提供季结构，保持原行为才不会让这些片源失去快速跳转能力。
     *
     * @param items         剧集列表，顺序与界面展示一致
     * @param selectedIndex 当前选中条目的下标
     */
    public static List<Group> build(List<Episode> items, int selectedIndex, boolean reverse) {
        return build(items, selectedIndex, reverse, true);
    }

    /**
     * 构建分组栏。
     *
     * <p>优先按「季」分组（片名里有两个及以上不同季时），否则退回按序号等分翻页：
     * 多数片源并不提供季结构，保持原行为才不会让这些片源失去快速跳转能力。
     *
     * @param items         剧集列表，顺序与界面展示一致
     * @param selectedIndex 当前选中条目的下标
     * @param allowSeasons  是否允许按季分组；音频/音乐列表应传 false，
     *                      「季」对它们没有意义，片名里的 S01 之类反而会误判
     */
    public static List<Group> build(List<Episode> items, int selectedIndex, boolean reverse, boolean allowSeasons) {
        if (items == null || items.isEmpty()) return new ArrayList<>();
        List<Group> groups = new ArrayList<>();
        if (allowSeasons) for (EpisodeSeasonGrouping.Season season : seasons(items)) {
            Group group = new Group(season.label(), season.start(), season.end());
            group.selected = season.contains(selectedIndex);
            groups.add(group);
        }
        if (groups.isEmpty()) return build(items.size(), selectedIndex, reverse);
        // 未选中任何分组时（例如选中项落在无季标记的条目上）与实际显示保持一致。
        if (groups.stream().noneMatch(group -> group.selected)) groups.get(0).selected = true;
        return groups;
    }

    /** 按原季标签分组；无法识别出多季时返回空列表。 */
    private static List<EpisodeSeasonGrouping.Season> seasons(List<Episode> items) {
        List<String> names = new ArrayList<>();
        for (Episode item : items) names.add(item.getRawDisplayName());
        return EpisodeSeasonGrouping.group(names);
    }

    private static int getGroupSize(int size) {
        if (size <= 60) return 20;
        if (size > 2500) return 300;
        if (size > 1500) return 200;
        if (size > 1000) return 150;
        if (size > 500) return 100;
        if (size > 300) return 50;
        return 40;
    }

    public static class Group {

        public final String name;
        public final int start;
        public final int end;
        public boolean selected;

        public Group(String name, int start, int end) {
            this.name = name;
            this.start = start;
            this.end = end;
        }
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterEpisodeGroupBinding binding;

        ViewHolder(@NonNull AdapterEpisodeGroupBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
