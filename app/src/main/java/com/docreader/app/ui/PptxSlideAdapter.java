package com.docreader.app.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
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
        android.content.Context context = holder.itemView.getContext();

        for (PptxParser.SlideItem item : slide.items) {
            if (item.type == PptxParser.SlideItem.Type.IMAGE) {
                ImageView iv = new ImageView(context);
                if (item.imageBytes != null) {
                    Bitmap bmp = BitmapFactory.decodeByteArray(item.imageBytes, 0, item.imageBytes.length);
                    iv.setImageBitmap(bmp);
                }
                iv.setAdjustViewBounds(true);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = 20;
                iv.setLayoutParams(lp);
                holder.container.addView(iv);
                continue;
            }

            TextView tv = new TextView(context);
            boolean isTitle = item.type == PptxParser.SlideItem.Type.TITLE;
            tv.setText(isTitle ? item.text : "•  " + item.text);
            tv.setTextColor(holder.itemView.getResources().getColor(
                    isTitle ? R.color.text_primary : R.color.text_secondary));

            if (isTitle) {
                tv.setTextSize(23f);
                tv.setTypeface(Typeface.DEFAULT_BOLD);
            } else {
                tv.setTextSize(16f);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = 14;
                tv.setLayoutParams(lp);
            }
            holder.container.addView(tv);
        }

        if (slide.items.isEmpty()) {
            TextView tv = new TextView(context);
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
