package com.docreader.app.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.docreader.app.R;
import com.docreader.app.viewer.DocxParser;

import java.util.List;

public class DocxBlockAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_TEXT = 0;
    private static final int TYPE_TABLE = 1;
    private static final int TYPE_IMAGE = 2;

    private final List<DocxParser.Block> blocks;

    public DocxBlockAdapter(List<DocxParser.Block> blocks) {
        this.blocks = blocks;
    }

    @Override
    public int getItemViewType(int position) {
        DocxParser.Block.Type type = blocks.get(position).type;
        if (type == DocxParser.Block.Type.TABLE) return TYPE_TABLE;
        if (type == DocxParser.Block.Type.IMAGE) return TYPE_IMAGE;
        return TYPE_TEXT;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_TABLE) {
            return new TableVH(inflater.inflate(R.layout.item_doc_table, parent, false));
        } else if (viewType == TYPE_IMAGE) {
            return new ImageVH(inflater.inflate(R.layout.item_doc_image, parent, false));
        }
        return new TextVH(inflater.inflate(R.layout.item_text_block, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        DocxParser.Block block = blocks.get(position);
        if (holder instanceof TextVH) {
            bindText((TextVH) holder, block);
        } else if (holder instanceof TableVH) {
            bindTable((TableVH) holder, block);
        } else if (holder instanceof ImageVH) {
            bindImage((ImageVH) holder, block);
        }
    }

    private void bindText(TextVH holder, DocxParser.Block block) {
        CharSequence content = buildSpannable(block.runs, defaultSizeFor(block));
        if (block.type == DocxParser.Block.Type.LIST_ITEM) {
            SpannableStringBuilder withBullet = new SpannableStringBuilder(content);
            withBullet.insert(0, "•  ");
            content = withBullet;
        }
        holder.text.setText(content);
        holder.text.setGravity(gravityFor(block.align));
        holder.text.setTextSize(block.type == DocxParser.Block.Type.HEADING ? 19f : 16f);
    }

    private float defaultSizeFor(DocxParser.Block block) {
        return block.type == DocxParser.Block.Type.HEADING ? 19f : 16f;
    }

    private CharSequence buildSpannable(List<DocxParser.Run> runs, float defaultSizeSp) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (DocxParser.Run run : runs) {
            int start = sb.length();
            sb.append(run.text);
            int end = sb.length();
            if (end == start) continue;

            int style = Typeface.NORMAL;
            if (run.bold && run.italic) style = Typeface.BOLD_ITALIC;
            else if (run.bold) style = Typeface.BOLD;
            else if (run.italic) style = Typeface.ITALIC;
            if (style != Typeface.NORMAL) {
                sb.setSpan(new StyleSpan(style), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (run.underline) {
                sb.setSpan(new UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (run.strike) {
                sb.setSpan(new StrikethroughSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (run.colorHex != null) {
                try {
                    sb.setSpan(new ForegroundColorSpan(Color.parseColor("#" + run.colorHex)),
                            start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                } catch (Exception ignored) {
                }
            }
        }
        return sb;
    }

    private int gravityFor(int align) {
        switch (align) {
            case DocxParser.Block.ALIGN_CENTER: return Gravity.CENTER_HORIZONTAL;
            case DocxParser.Block.ALIGN_END: return Gravity.END;
            default: return Gravity.START;
        }
    }

    private void bindTable(TableVH holder, DocxParser.Block block) {
        holder.table.removeAllViews();
        if (block.tableRows == null) return;
        boolean firstRow = true;
        for (List<DocxParser.Cell> row : block.tableRows) {
            TableRow tableRow = new TableRow(holder.itemView.getContext());
            for (DocxParser.Cell cell : row) {
                TextView cellView = new TextView(holder.itemView.getContext());
                cellView.setText(buildSpannable(cell.runs, 13f));
                cellView.setPadding(20, 16, 20, 16);
                cellView.setTextColor(holder.itemView.getResources().getColor(R.color.text_primary));
                cellView.setTextSize(13f);
                cellView.setBackgroundResource(R.drawable.bg_table_cell);
                if (firstRow) cellView.setTypeface(null, Typeface.BOLD);
                tableRow.addView(cellView);
            }
            holder.table.addView(tableRow);
            firstRow = false;
        }
    }

    private void bindImage(ImageVH holder, DocxParser.Block block) {
        if (block.imageBytes != null) {
            Bitmap bmp = BitmapFactory.decodeByteArray(block.imageBytes, 0, block.imageBytes.length);
            holder.image.setImageBitmap(bmp);
        }
    }

    @Override
    public int getItemCount() { return blocks.size(); }

    static class TextVH extends RecyclerView.ViewHolder {
        TextView text;
        TextVH(View v) { super(v); text = v.findViewById(R.id.textBlock); }
    }

    static class TableVH extends RecyclerView.ViewHolder {
        TableLayout table;
        TableVH(View v) { super(v); table = v.findViewById(R.id.tableBlock); }
    }

    static class ImageVH extends RecyclerView.ViewHolder {
        ImageView image;
        ImageVH(View v) { super(v); image = v.findViewById(R.id.imageBlock); }
    }
}
