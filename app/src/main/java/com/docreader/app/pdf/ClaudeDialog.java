package com.docreader.app.pdf;

import com.docreader.app.R;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * مربع حوار بأسلوب تطبيق Claude: بطاقة مدوّرة بعنوان عريض ونص، وأزرار كبسولة
 * (إلغاء بحدّ رفيع + تأكيد؛ ويتحول التأكيد للأحمر تلقائيًا لو كان حذفًا/مسحًا).
 *
 * واجهته مطابقة لـ MaterialAlertDialogBuilder في الدوال المستخدمة بالتطبيق
 * (setTitle / setMessage / setItems / setSingleChoiceItems / setView /
 * setPositiveButton / setNegativeButton / setNeutralButton / show) حتى يكون
 * الاستبدال مباشرًا.
 */
public class ClaudeDialog {

    private final Context context;
    private CharSequence title;
    private CharSequence message;
    private CharSequence positiveText;
    private CharSequence negativeText;
    private CharSequence neutralText;
    private DialogInterface.OnClickListener positiveListener;
    private DialogInterface.OnClickListener negativeListener;
    private DialogInterface.OnClickListener neutralListener;
    private CharSequence[] items;
    private int[] itemIcons;
    private int checkedItem = -1;
    private boolean singleChoice;
    private DialogInterface.OnClickListener itemsListener;
    private View customView;
    private Boolean destructive;

    public ClaudeDialog(Context context) {
        this.context = context;
    }

    public ClaudeDialog setTitle(CharSequence title) {
        this.title = title;
        return this;
    }

    public ClaudeDialog setMessage(CharSequence message) {
        this.message = message;
        return this;
    }

    public ClaudeDialog setView(View view) {
        this.customView = view;
        return this;
    }

    public ClaudeDialog setItems(CharSequence[] items, DialogInterface.OnClickListener listener) {
        this.items = items;
        this.itemsListener = listener;
        this.singleChoice = false;
        return this;
    }

    /** أيقونات اختيارية بجانب عناصر setItems (بنفس ترتيب العناصر). */
    public ClaudeDialog setItemIcons(int... iconRes) {
        this.itemIcons = iconRes;
        return this;
    }

    public ClaudeDialog setSingleChoiceItems(CharSequence[] items, int checked, DialogInterface.OnClickListener listener) {
        this.items = items;
        this.checkedItem = checked;
        this.itemsListener = listener;
        this.singleChoice = true;
        return this;
    }

    public ClaudeDialog setPositiveButton(CharSequence text, DialogInterface.OnClickListener listener) {
        this.positiveText = text;
        this.positiveListener = listener;
        return this;
    }

    public ClaudeDialog setNegativeButton(CharSequence text, DialogInterface.OnClickListener listener) {
        this.negativeText = text;
        this.negativeListener = listener;
        return this;
    }

    public ClaudeDialog setNeutralButton(CharSequence text, DialogInterface.OnClickListener listener) {
        this.neutralText = text;
        this.neutralListener = listener;
        return this;
    }

    /** فرض لون زر التأكيد (أحمر/عادي) بدل الاكتشاف التلقائي من نص الزر. */
    public ClaudeDialog setDestructive(boolean value) {
        this.destructive = value;
        return this;
    }

    public Dialog show() {
        Dialog dialog = create();
        dialog.show();
        return dialog;
    }

    public Dialog create() {
        final Dialog dialog = new Dialog(context, R.style.ClaudeDialogTheme);
        Context dctx = dialog.getContext();
        View root = LayoutInflater.from(dctx).inflate(R.layout.dialog_claude, null, false);

        TextView titleView = root.findViewById(R.id.cd_title);
        TextView messageView = root.findViewById(R.id.cd_message);
        FrameLayout customBox = root.findViewById(R.id.cd_custom);
        LinearLayout itemsBox = root.findViewById(R.id.cd_items);
        View buttonsRow = root.findViewById(R.id.cd_buttons);
        TextView negativeBtn = root.findViewById(R.id.cd_negative);
        TextView positiveBtn = root.findViewById(R.id.cd_positive);
        TextView neutralBtn = root.findViewById(R.id.cd_neutral);

        if (title != null && title.length() > 0) {
            titleView.setText(title);
        } else {
            titleView.setVisibility(View.GONE);
        }

        if (message != null && message.length() > 0) {
            messageView.setText(message);
            messageView.setVisibility(View.VISIBLE);
        }

        if (customView != null) {
            if (customView.getParent() instanceof ViewGroup) {
                ((ViewGroup) customView.getParent()).removeView(customView);
            }
            customBox.addView(customView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            customBox.setVisibility(View.VISIBLE);
        }

        if (items != null && items.length > 0) {
            itemsBox.setVisibility(View.VISIBLE);
            for (int i = 0; i < items.length; i++) {
                final int index = i;
                TextView row = new TextView(dctx);
                row.setText(items[i]);
                row.setTextColor(dctx.getColor(R.color.pdf_text_primary));
                row.setTextSize(16f);
                row.setTextDirection(View.TEXT_DIRECTION_RTL);
                row.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setMinHeight(dp(dctx, 56));
                row.setPadding(0, dp(dctx, 8), 0, dp(dctx, 8));
                row.setCompoundDrawablePadding(dp(dctx, 14));

                int startIcon = (itemIcons != null && i < itemIcons.length) ? itemIcons[i] : 0;
                int endIcon = (singleChoice && i == checkedItem) ? R.drawable.ic_check : 0;
                row.setCompoundDrawablesRelativeWithIntrinsicBounds(startIcon, 0, endIcon, 0);
                row.setCompoundDrawableTintList(ColorStateList.valueOf(
                        dctx.getColor(endIcon != 0 ? R.color.primary_cyan : R.color.pdf_text_secondary)));

                android.util.TypedValue tv = new android.util.TypedValue();
                if (dctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
                    row.setBackgroundResource(tv.resourceId);
                }
                row.setClickable(true);
                row.setFocusable(true);
                row.setOnClickListener(v -> {
                    if (itemsListener != null) itemsListener.onClick(dialog, index);
                    dialog.dismiss();
                });
                itemsBox.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                if (i < items.length - 1) {
                    View divider = new View(dctx);
                    divider.setBackgroundColor(dctx.getColor(R.color.glass_border_soft));
                    itemsBox.addView(divider, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(dctx, 1)));
                }
            }
        }

        boolean hasPositive = positiveText != null && positiveText.length() > 0;
        boolean hasNegative = negativeText != null && negativeText.length() > 0;
        if (hasPositive || hasNegative) {
            buttonsRow.setVisibility(View.VISIBLE);
        }

        if (hasNegative) {
            negativeBtn.setVisibility(View.VISIBLE);
            negativeBtn.setText(negativeText);
            negativeBtn.setOnClickListener(v -> {
                if (negativeListener != null) negativeListener.onClick(dialog, DialogInterface.BUTTON_NEGATIVE);
                dialog.dismiss();
            });
        }

        if (hasPositive) {
            positiveBtn.setVisibility(View.VISIBLE);
            positiveBtn.setText(positiveText);
            boolean danger = destructive != null ? destructive : looksDestructive(positiveText);
            if (danger) {
                positiveBtn.setBackgroundResource(R.drawable.bg_pill_danger);
                positiveBtn.setTextColor(dctx.getColor(R.color.white));
            }
            positiveBtn.setOnClickListener(v -> {
                if (positiveListener != null) positiveListener.onClick(dialog, DialogInterface.BUTTON_POSITIVE);
                dialog.dismiss();
            });
        }

        if (neutralText != null && neutralText.length() > 0) {
            neutralBtn.setVisibility(View.VISIBLE);
            neutralBtn.setText(neutralText);
            neutralBtn.setOnClickListener(v -> {
                if (neutralListener != null) neutralListener.onClick(dialog, DialogInterface.BUTTON_NEUTRAL);
                dialog.dismiss();
            });
        }

        dialog.setContentView(root);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            DisplayMetrics dm = dctx.getResources().getDisplayMetrics();
            int width = Math.min(dm.widthPixels - dp(dctx, 40), dp(dctx, 420));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        return dialog;
    }

    /** أزرار الحذف/المسح/الإزالة تظهر بالأحمر تلقائيًا. */
    private static boolean looksDestructive(CharSequence text) {
        String s = text.toString().trim();
        return s.startsWith("حذف") || s.startsWith("مسح") || s.startsWith("إزالة");
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
