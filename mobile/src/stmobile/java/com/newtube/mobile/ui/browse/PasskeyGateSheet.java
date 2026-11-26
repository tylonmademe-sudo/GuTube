package com.newtube.mobile.ui.browse;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.transition.TransitionManager;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.AppFonts;
import com.newtube.mobile.ui.common.Haptics;
import com.newtube.mobile.ui.common.MobileSnackbar;
import com.newtube.mobile.ui.common.SheetGlass;

import java.security.SecureRandom;

/**
 * GUTUBE(portal-auth): Live SheetGlass bottom sheet gate that verifies the user's email
 * with a unique 6-digit passkey for a 30-minute {@code Browse} session.
 */
public final class PasskeyGateSheet {

    private static final String PREFS = "gutube_portal_auth";
    private static final String KEY_UNLOCKED = "unlocked";
    private static final String KEY_UNLOCKED_UNTIL = "unlocked_until";
    private static final String KEY_VERIFIED_EMAIL = "verified_email";
    private static final String KEY_PENDING_EMAIL = "pending_email";
    private static final String KEY_PENDING_PASSKEY = "pending_passkey";
    private static final String KEY_PENDING_EXPIRES_AT = "pending_expires_at";
    private static final long PASSKEY_TTL_MS = 15L * 60L * 1000L;
    private static final long SESSION_TTL_MS = 30L * 60L * 1000L;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasskeyGateSheet() {
    }

    public static boolean isUnlocked(@NonNull Context context) {
        SharedPreferences sp = prefs(context);
        long until = sp.getLong(KEY_UNLOCKED_UNTIL, 0L);
        long now = System.currentTimeMillis();
        if (until > 0L && now < until) {
            return true;
        }
        if (until > 0L && now >= until) {
            sp.edit().remove(KEY_UNLOCKED_UNTIL).apply();
        }
        return false;
    }

    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void show(@NonNull Activity activity, @NonNull Runnable onUnlocked) {
        BottomSheetDialog sheet = Haptics.watch(new BottomSheetDialog(activity));
        ViewGroup content = (ViewGroup) LayoutInflater.from(activity)
                .inflate(R.layout.sheet_mobile_passkey_gate, null);

        TextView titleView = content.findViewById(R.id.passkey_gate_title);
        TextView subtitleView = content.findViewById(R.id.passkey_gate_subtitle);
        EditText emailInput = content.findViewById(R.id.passkey_gate_email);
        View generateContainer = content.findViewById(R.id.passkey_gate_generate_container);
        ProgressBar generateProgress = content.findViewById(R.id.passkey_gate_generate_progress);
        TextView generateLabel = content.findViewById(R.id.passkey_gate_generate_label);
        View statusRow = content.findViewById(R.id.passkey_gate_status_row);
        TextView statusText = content.findViewById(R.id.passkey_gate_status);
        TextView resendBtn = content.findViewById(R.id.passkey_gate_resend);
        View codeCard = content.findViewById(R.id.passkey_gate_code_card);
        EditText codeInput = content.findViewById(R.id.passkey_gate_code);
        TextView verifyBtn = content.findViewById(R.id.passkey_gate_verify);

        titleView.setTypeface(AppFonts.bold(activity));
        subtitleView.setTypeface(AppFonts.regular(activity));
        emailInput.setTypeface(AppFonts.regular(activity));
        generateLabel.setTypeface(AppFonts.medium(activity));
        statusText.setTypeface(AppFonts.regular(activity));
        resendBtn.setTypeface(AppFonts.medium(activity));
        codeInput.setTypeface(AppFonts.bold(activity));
        verifyBtn.setTypeface(AppFonts.medium(activity));

        applyPrimaryPillBackground(activity, generateContainer);
        applyPrimaryPillBackground(activity, verifyBtn);

        SharedPreferences sp = prefs(activity);
        String existingEmail = sp.getString(KEY_PENDING_EMAIL, "");
        String existingCode = sp.getString(KEY_PENDING_PASSKEY, "");
        long existingExpires = sp.getLong(KEY_PENDING_EXPIRES_AT, 0L);
        if (!existingEmail.isEmpty() && !existingCode.isEmpty() && System.currentTimeMillis() < existingExpires) {
            emailInput.setText(existingEmail);
            statusRow.setVisibility(View.VISIBLE);
            statusText.setText("Passkey sent to " + existingEmail);
            codeCard.setVisibility(View.VISIBLE);
            verifyBtn.setVisibility(View.VISIBLE);
        }

        Runnable sendAction = () -> {
            String email = emailInput.getText() != null ? emailInput.getText().toString().trim() : "";
            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                MobileSnackbar.show(activity, "Please enter a valid email address");
                emailInput.requestFocus();
                return;
            }

            String code = String.valueOf(100000 + RANDOM.nextInt(900000));
            sp.edit()
                    .remove(KEY_UNLOCKED_UNTIL)
                    .remove(KEY_UNLOCKED)
                    .putString(KEY_PENDING_EMAIL, email)
                    .putString(KEY_PENDING_PASSKEY, code)
                    .putLong(KEY_PENDING_EXPIRES_AT, System.currentTimeMillis() + PASSKEY_TTL_MS)
                    .apply();

            TransitionManager.beginDelayedTransition(content);
            emailInput.setEnabled(false);
            generateContainer.setEnabled(false);
            generateContainer.setAlpha(0.85f);
            generateProgress.setVisibility(View.VISIBLE);
            generateLabel.setText("Sending passkey...");
            resendBtn.setEnabled(false);

            PasskeyEmailSender.sendPasskey(email, code, new PasskeyEmailSender.Callback() {
                @Override
                public void onSuccess() {
                    if (activity.isFinishing() || activity.isDestroyed() || !sheet.isShowing()) {
                        return;
                    }
                    TransitionManager.beginDelayedTransition(content);
                    emailInput.setEnabled(true);
                    generateContainer.setEnabled(true);
                    generateContainer.setAlpha(1f);
                    generateProgress.setVisibility(View.GONE);
                    generateLabel.setText("Generate your passkey");
                    resendBtn.setEnabled(true);
                    statusRow.setVisibility(View.VISIBLE);
                    statusText.setText("Passkey sent to " + email);
                    codeCard.setVisibility(View.VISIBLE);
                    verifyBtn.setVisibility(View.VISIBLE);
                    codeInput.requestFocus();
                }

                @Override
                public void onError(@NonNull String message) {
                    if (activity.isFinishing() || activity.isDestroyed() || !sheet.isShowing()) {
                        return;
                    }
                    TransitionManager.beginDelayedTransition(content);
                    emailInput.setEnabled(true);
                    generateContainer.setEnabled(true);
                    generateContainer.setAlpha(1f);
                    generateProgress.setVisibility(View.GONE);
                    generateLabel.setText("Generate your passkey");
                    resendBtn.setEnabled(true);
                    MobileSnackbar.show(activity, "Could not send passkey: " + message);
                }
            });
        };

        generateContainer.setOnClickListener(v -> {
            Haptics.tick(v);
            sendAction.run();
        });
        resendBtn.setOnClickListener(v -> {
            Haptics.tick(v);
            sendAction.run();
        });

        Runnable verifyAction = () -> {
            String entered = codeInput.getText() != null ? codeInput.getText().toString().trim() : "";
            String pendingCode = sp.getString(KEY_PENDING_PASSKEY, "");
            String pendingEmail = sp.getString(KEY_PENDING_EMAIL, "");
            long expiresAt = sp.getLong(KEY_PENDING_EXPIRES_AT, 0L);

            if (entered.length() < 6) {
                MobileSnackbar.show(activity, "Enter the 6-digit passkey");
                return;
            }
            if (System.currentTimeMillis() > expiresAt) {
                MobileSnackbar.show(activity, "Passkey expired. Tap Resend for a new code.");
                return;
            }
            if (!entered.equals(pendingCode)) {
                MobileSnackbar.show(activity, "Incorrect passkey");
                return;
            }

            sp.edit()
                    .putLong(KEY_UNLOCKED_UNTIL, System.currentTimeMillis() + SESSION_TTL_MS)
                    .putString(KEY_VERIFIED_EMAIL, pendingEmail)
                    .remove(KEY_UNLOCKED)
                    .remove(KEY_PENDING_PASSKEY)
                    .remove(KEY_PENDING_EXPIRES_AT)
                    .apply();

            Haptics.tick(verifyBtn);
            sheet.dismiss();
            onUnlocked.run();
        };

        verifyBtn.setOnClickListener(v -> verifyAction.run());

        codeInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (s != null && s.length() == 6) {
                    String pendingCode = sp.getString(KEY_PENDING_PASSKEY, "");
                    long expiresAt = sp.getLong(KEY_PENDING_EXPIRES_AT, 0L);
                    if (s.toString().equals(pendingCode) && System.currentTimeMillis() <= expiresAt) {
                        verifyAction.run();
                    }
                }
            }
        });

        sheet.setContentView(content);
        if (sheet.getWindow() != null) {
            sheet.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        sheet.setOnShowListener(dialogInterface -> {
            BottomSheetDialog d = (BottomSheetDialog) dialogInterface;
            SheetGlass.frost(d, true);
            FrameLayout bottomSheet = d.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                BottomSheetBehavior.from(bottomSheet).setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        });
        sheet.show();
    }

    private static void applyPrimaryPillBackground(@NonNull Context context, @NonNull View view) {
        float radius = 25f * context.getResources().getDisplayMetrics().density;
        int fillColor = ContextCompat.getColor(context, R.color.mobile_color_on_surface);
        int rippleColor = ContextCompat.getColor(context, R.color.gutube_glass_selected);

        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(radius);
        shape.setColor(fillColor);

        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(radius);
        mask.setColor(0xFFFFFFFF);

        view.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor), shape, mask));
    }
}
