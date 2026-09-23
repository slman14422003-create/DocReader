package com.docreader.app.ui;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.docreader.app.R;
import com.docreader.app.model.RecentFile;
import com.docreader.app.util.FileTypeUtils;

import java.util.List;

public class RecentFilesAdapter extends RecyclerView.Adapter<RecentFilesAdapter.VH> {

    public interface OnFileClick { void onClick(RecentFile file); }

    private final List<RecentFile> items;
    private final OnFileClick listener;

    public RecentFilesAdapter(List<RecentFile> items, OnFileClick listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recent_file, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        RecentFile file = items.get(position);
        holder.name.setText(file.displayName);
        FileTypeUtils.DocType type = FileTypeUtils.detect(file.displayName);
        holder.icon.setText(FileTypeUtils.iconFor(type));
        holder.date.setText(DateUtils.getRelativeTimeSpanString(file.lastOpened));
        holder.itemView.setOnClickListener(v -> listener.onClick(file));
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView icon, name, date;
        VH(View v) {
            super(v);
            icon = v.findViewById(R.id.textIcon);
            name = v.findViewById(R.id.textFileName);
            date = v.findViewById(R.id.textFileDate);
        }
    }
}
