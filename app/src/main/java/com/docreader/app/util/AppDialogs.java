package com.docreader.app.util;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.docreader.app.R;

/** حوارات مخصصة بنفس هوية التطبيق (بطاقة داكنة/فاتحة بحواف دائرية وأزرار كبسولية) بدل حوار النظام الافتراضي. */
public class AppDialogs {

    public interface OnConfirm { void run(); }
    public interface OnModeSelected { void onSelected(String mode); }

    public static void showConfirm(Context context, String title, String message,
                                    String positiveText, String negativeText, OnConfirm onPositive) {
        Dialog dialog = buildBaseDialog(context, R.layout.dialog_confirm);

        TextView titleView = dialog.findViewById(R.id.dialogTitle);
        TextView messageView = dialog.findViewById(R.id.dialogMessage);
        TextView btnPositive = dialog.findViewById(R.id.btnPositive);
        TextView btnNegative = dialog.findViewById(R.id.btnNegative);

        titleView.setText(title);
        if (message == null || message.isEmpty()) {
            messageView.setVisibility(View.GONE);
        } else {
            messageView.setText(message);
        }

        btnPositive.setText(positiveText);
        btnPositive.setOnClickListener(v -> {
            dialog.dismiss();
            if (onPositive != null) onPositive.run();
        });

        if (negativeText == null) {
            btnNegative.setVisibility(View.GONE);
        } else {
            btnNegative.setText(negativeText);
            btnNegative.setOnClickListener(v -> dialog.dismiss());
        }

        dialog.show();
    }

    public static void showColorModePicker(Context context, String currentMode, OnModeSelected callback) {
        Dialog dialog = buildBaseDialog(context, R.layout.dialog_color_mode);

        LinearLayout rowSystem = dialog.findViewById(R.id.rowSystem);
        LinearLayout rowLight = dialog.findViewById(R.id.rowLight);
        LinearLayout rowDark = dialog.findViewById(R.id.rowDark);
        ImageView radioSystem = dialog.findViewById(R.id.radioSystem);
        ImageView radioLight = dialog.findViewById(R.id.radioLight);
        ImageView radioDark = dialog.findViewById(R.id.radioDark);

        setRadioState(context, radioSystem, ThemeUtils.MODE_SYSTEM.equals(currentMode));
        setRadioState(context, radioLight, ThemeUtils.MODE_LIGHT.equals(currentMode));
        setRadioState(context, radioDark, ThemeUtils.MODE_DARK.equals(currentMode));

        rowSystem.setOnClickListener(v -> { dialog.dismiss(); callback.onSelected(ThemeUtils.MODE_SYSTEM); });
        rowLight.setOnClickListener(v -> { dialog.dismiss(); callback.onSelected(ThemeUtils.MODE_LIGHT); });
        rowDark.setOnClickListener(v -> { dialog.dismiss(); callback.onSelected(ThemeUtils.MODE_DARK); });

        TextView btnCancel = dialog.findViewById(R.id.btnCancelColorMode);
        btnCancel.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private static void setRadioState(Context context, ImageView radio, boolean selected) {
        radio.setImageResource(selected ? R.drawable.ic_radio_selected : R.drawable.ic_radio_unselected);
        int color = ContextCompat.getColor(context, selected ? R.color.accent_primary : R.color.text_tertiary);
        radio.setImageTintList(ColorStateList.valueOf(color));
    }

    private static Dialog buildBaseDialog(Context context, int layoutRes) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(layoutRes);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }
}
