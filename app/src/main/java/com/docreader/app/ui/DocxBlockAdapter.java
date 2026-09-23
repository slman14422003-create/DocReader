package com.docreader.app.ui;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.docreader.app.R;
import com.docreader.app.viewer.DocxParser;

import java.util.List;

public class DocxBlockAdapter extends RecyclerView.Adapter<DocxBlockAdapter.VH> {

    private final List<DocxParser.Block> blocks;

    public DocxBlockAdapter(List<DocxParser.Block> blocks) {
        this.blocks = blocks;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_text_block, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        DocxParser.Block block = blocks.get(position);
        holder.text.setText(block.text);
        if (block.type == DocxParser.Block.Type.HEADING) {
            holder.text.setTypeface(Typeface.DEFAULT_BOLD);
            holder.text.setTextSize(19f);
        } else if (block.type == DocxParser.Block.Type.TABLE_ROW) {
            holder.text.setTypeface(Typeface.DEFAULT);
            holder.text.setTextSize(14f);
            holder.text.setBackgroundColor(holder.itemView.getResources().getColor(R.color.surface_elevated));
        } else {
            holder.text.setTypeface(Typeface.DEFAULT);
            holder.text.setTextSize(16f);
            holder.text.setBackgroundColor(0);
        }
    }

    @Override
    public int getItemCount() { return blocks.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView text;
        VH(View v) {
            super(v);
            text = v.findViewById(R.id.textBlock);
        }
    }
}
