package com.docreader.app.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.docreader.app.R;
import com.docreader.app.viewer.PptxParser;

import java.util.List;

public class PptxSlideAdapter extends RecyclerView.Adapter<PptxSlideAdapter.VH> {

    private final List<PptxParser.Slide> slides;

    public PptxSlideAdapter(List<PptxParser.Slide> slides) {
        this.slides = slides;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_slide, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        PptxParser.Slide slide = slides.get(position);
        holder.container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(holder.itemView.getContext());
        boolean first = true;
        for (String line : slide.textLines) {
            TextView tv = new TextView(holder.itemView.getContext());
            tv.setText(line);
            tv.setTextColor(holder.itemView.getResources().getColor(R.color.text_primary));
            if (first) {
                tv.setTextSize(22f);
                tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                first = false;
            } else {
                tv.setTextSize(16f);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = 16;
                tv.setLayoutParams(lp);
            }
            holder.container.addView(tv);
        }
        if (slide.textLines.isEmpty()) {
            TextView tv = new TextView(holder.itemView.getContext());
            tv.setText("(شريحة بدون نص)");
            tv.setTextColor(holder.itemView.getResources().getColor(R.color.text_tertiary));
            holder.container.addView(tv);
        }
    }

    @Override
    public int getItemCount() { return slides.size(); }

    static class VH extends RecyclerView.ViewHolder {
        LinearLayout container;
        VH(View v) {
            super(v);
            container = v.findViewById(R.id.slideTextContainer);
        }
    }
}
