package com.docreader.app.ui;

import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.docreader.app.R;

public class PdfPageAdapter extends RecyclerView.Adapter<PdfPageAdapter.VH> {

    private final PdfRenderer renderer;

    public PdfPageAdapter(PdfRenderer renderer) {
        this.renderer = renderer;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_pdf_page, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        synchronized (renderer) {
            PdfRenderer.Page page = renderer.openPage(position);
            int width = Math.min(1600, page.getWidth() * 3);
            int height = width * page.getHeight() / page.getWidth();
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(0xFFFFFFFF);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            holder.image.setImageBitmap(bitmap);
            page.close();
        }
    }

    @Override
    public int getItemCount() { return renderer.getPageCount(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView image;
        VH(View v) {
            super(v);
            image = v.findViewById(R.id.imagePage);
        }
    }
}
