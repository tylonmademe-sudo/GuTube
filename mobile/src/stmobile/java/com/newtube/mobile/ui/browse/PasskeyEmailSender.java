
package com.newtube.mobile.ui.browse;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * GuTube portal authentication.
 *
 * Sends unique 6-digit passkey emails through Gmail SMTP (SSL port 465)
 * or an optional n8n webhook when configured.
 */
public final class PasskeyEmailSender {

    public static final String N8N_WEBHOOK_URL = "";

    public static final String SENDER_EMAIL =
            com.liskovsoft.smartyoutubetv2.tv.BuildConfig.GUTUBE_SENDER_EMAIL;

    public static final String SENDER_APP_PASSWORD =
            com.liskovsoft.smartyoutubetv2.tv.BuildConfig.GUTUBE_SENDER_APP_PASSWORD;

    private static final String SMTP_HOST = "smtp.gmail.com";
    private static final int SMTP_PORT = 465;
    private static final int TIMEOUT_MS = 15_000;

    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor();

    private static final Handler MAIN =
            new Handler(Looper.getMainLooper());

    public interface Callback {
        void onSuccess();

        void onError(@NonNull String message);
    }

    private PasskeyEmailSender() {
    }

    public static void sendPasskey(
            @NonNull String recipientEmail,
            @NonNull String passkey,
            @NonNull Callback callback
    ) {
        EXECUTOR.execute(() -> {
            try {
                if (N8N_WEBHOOK_URL != null
                        && !N8N_WEBHOOK_URL.trim().isEmpty()) {

                    sendViaWebhook(
                            N8N_WEBHOOK_URL.trim(),
                            recipientEmail,
                            passkey
                    );

                } else {
                    sendViaGmailSmtp(recipientEmail, passkey);
                }

                MAIN.post(callback::onSuccess);

            } catch (Throwable t) {
                String msg = t.getMessage() != null
                        ? t.getMessage()
                        : t.getClass().getSimpleName();

                MAIN.post(() -> callback.onError(msg));
            }
        });
    }

    private static void sendViaWebhook(
            @NonNull String webhookUrl,
            @NonNull String recipientEmail,
            @NonNull String passkey
    ) throws IOException {

        HttpURLConnection conn =
                (HttpURLConnection) new URL(webhookUrl).openConnection();

        try {
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);

            conn.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=UTF-8"
            );

            String payload =
                    "{\"email\":\"" + escapeJson(recipientEmail)
                            + "\",\"passkey\":\"" + escapeJson(passkey)
                            + "\"}";

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();

            if (code < 200 || code >= 300) {
                throw new IOException("Webhook HTTP " + code);
            }

        } finally {
            conn.disconnect();
        }
    }

    private static void sendViaGmailSmtp(
            @NonNull String recipientEmail,
            @NonNull String passkey
    ) throws IOException {

        String cleanPassword =
                SENDER_APP_PASSWORD.replace(" ", "").trim();
        if (SENDER_EMAIL.trim().isEmpty() || cleanPassword.isEmpty()) {
            throw new IOException("Gmail SMTP credentials not configured in local.properties");
        }

        SSLSocket socket = null;

        try {
            SSLSocketFactory factory =
                    (SSLSocketFactory) SSLSocketFactory.getDefault();

            socket = (SSLSocket) factory.createSocket();

            socket.connect(
                    new InetSocketAddress(SMTP_HOST, SMTP_PORT),
                    TIMEOUT_MS
            );

            socket.setSoTimeout(TIMEOUT_MS);
            socket.startHandshake();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            socket.getInputStream(),
                            StandardCharsets.UTF_8
                    )
            );

            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(
                            socket.getOutputStream(),
                            StandardCharsets.UTF_8
                    )
            );

            expectCode(reader, 220);

            writeLine(writer, "EHLO localhost");
            consumeMultiline(reader, 250);

            writeLine(writer, "AUTH LOGIN");
            expectCode(reader, 334);

            writeLine(
                    writer,
                    Base64.encodeToString(
                            SENDER_EMAIL.getBytes(StandardCharsets.UTF_8),
                            Base64.NO_WRAP
                    )
            );

            expectCode(reader, 334);

            writeLine(
                    writer,
                    Base64.encodeToString(
                            cleanPassword.getBytes(StandardCharsets.UTF_8),
                            Base64.NO_WRAP
                    )
            );

            expectCode(reader, 235);

            writeLine(writer, "MAIL FROM:<" + SENDER_EMAIL + ">");
            expectCode(reader, 250);

            writeLine(writer, "RCPT TO:<" + recipientEmail + ">");
            expectCode(reader, 250);

            writeLine(writer, "DATA");
            expectCode(reader, 354);

            // Modern GuTube verification email (Base64 wrapped at 76 chars for SMTP 998-char line limit)
            String htmlBody = buildEmailHtml(passkey);
            String encodedHtml = Base64.encodeToString(
                            htmlBody.getBytes(StandardCharsets.UTF_8), Base64.DEFAULT)
                    .replace("\n", "\r\n");

            StringBuilder data = new StringBuilder();
            data.append("From: GuTube <").append(SENDER_EMAIL).append(">\r\n");
            data.append("To: <").append(recipientEmail).append(">\r\n");
            // Do not expose the verification code in the subject.
            data.append("Subject: Verify your GuTube access\r\n");
            data.append("MIME-Version: 1.0\r\n");
            data.append("Content-Type: text/html; charset=UTF-8\r\n");
            data.append("Content-Transfer-Encoding: base64\r\n");
            data.append("\r\n");
            data.append(encodedHtml).append("\r\n.");

            writeLine(writer, data.toString());

            expectCode(reader, 250);

            writeLine(writer, "QUIT");

        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Builds the modern GuTube verification email.
     */
    @NonNull
    private static String buildEmailHtml(@NonNull String passkey) {

        String safePasskey = escapeHtml(passkey);

        return "<!DOCTYPE html>"
                + "<html lang=\"en\">"
                + "<head>"
                + "<meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" "
                + "content=\"width=device-width, initial-scale=1.0\">"
                + "<meta name=\"color-scheme\" content=\"dark\">"
                + "<meta name=\"supported-color-schemes\" content=\"dark\">"
                + "<title>GuTube Verification</title>"
                + "</head>"

                + "<body style=\""
                + "margin:0;"
                + "padding:0;"
                + "background-color:#09090b;"
                + "font-family:Arial,Helvetica,sans-serif;"
                + "color:#f4f4f5;"
                + "\">"

                + "<table role=\"presentation\" "
                + "width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "border=\"0\" style=\"background:#09090b;\">"

                + "<tr>"
                + "<td align=\"center\" style=\"padding:40px 16px;\">"

                // Main container
                + "<table role=\"presentation\" "
                + "width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "border=\"0\" style=\""
                + "max-width:460px;"
                + "background:#111114;"
                + "border:1px solid #27272a;"
                + "border-radius:18px;"
                + "\">"

                // Header
                + "<tr>"
                + "<td style=\"padding:30px 28px 24px;\">"

                + "<table role=\"presentation\" "
                + "cellpadding=\"0\" cellspacing=\"0\" border=\"0\">"

                + "<tr>"

                // Brand icon
                + "<td valign=\"middle\" style=\""
                + "width:38px;"
                + "height:38px;"
                + "background:#27272a;"
                + "border-radius:11px;"
                + "text-align:center;"
                + "\">"

                + "<span style=\""
                + "font-size:18px;"
                + "font-weight:700;"
                + "color:#ffffff;"
                + "\">G</span>"

                + "</td>"

                // Brand name
                + "<td valign=\"middle\" style=\"padding-left:12px;\">"

                + "<span style=\""
                + "font-size:19px;"
                + "font-weight:700;"
                + "letter-spacing:-0.5px;"
                + "color:#ffffff;"
                + "\">GuTube</span>"

                + "</td>"

                + "</tr>"
                + "</table>"

                + "</td>"
                + "</tr>"

                // Divider
                + "<tr>"
                + "<td style=\"padding:0 28px;\">"
                + "<div style=\"height:1px;background:#27272a;\"></div>"
                + "</td>"
                + "</tr>"

                // Main content
                + "<tr>"
                + "<td style=\"padding:30px 28px 8px;\">"

                + "<p style=\""
                + "margin:0 0 12px;"
                + "font-size:11px;"
                + "font-weight:600;"
                + "letter-spacing:1.5px;"
                + "text-transform:uppercase;"
                + "color:#a1a1aa;"
                + "\">ACCOUNT VERIFICATION</p>"

                + "<h1 style=\""
                + "margin:0 0 14px;"
                + "font-size:25px;"
                + "line-height:1.3;"
                + "font-weight:700;"
                + "letter-spacing:-0.6px;"
                + "color:#fafafa;"
                + "\">Your verification code</h1>"

                + "<p style=\""
                + "margin:0;"
                + "font-size:14px;"
                + "line-height:1.7;"
                + "color:#a1a1aa;"
                + "\">"
                + "Use the code below to verify your access "
                + "to GuTube Browse."
                + "</p>"

                + "</td>"
                + "</tr>"

                // Verification code box
                + "<tr>"
                + "<td style=\"padding:24px 28px 20px;\">"

                + "<table role=\"presentation\" "
                + "width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "border=\"0\" style=\""
                + "background:#18181b;"
                + "border:1px solid #303036;"
                + "border-radius:12px;"
                + "\">"

                + "<tr>"
                + "<td align=\"center\" style=\"padding:22px 12px;\">"

                + "<div style=\""
                + "font-family:'Courier New',monospace;"
                + "font-size:32px;"
                + "font-weight:700;"
                + "letter-spacing:8px;"
                + "color:#ffffff;"
                + "\">"

                + safePasskey

                + "</div>"

                + "</td>"
                + "</tr>"
                + "</table>"

                + "</td>"
                + "</tr>"

                // Expiry information
                + "<tr>"
                + "<td style=\"padding:0 28px 26px;\">"

                + "<table role=\"presentation\" "
                + "width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "border=\"0\">"

                + "<tr>"

                + "<td valign=\"top\" style=\"padding-right:12px;\">"

                + "<div style=\""
                + "width:6px;"
                + "height:6px;"
                + "margin-top:7px;"
                + "background:#a1a1aa;"
                + "border-radius:50%;"
                + "\"></div>"

                + "</td>"

                + "<td style=\""
                + "font-size:12px;"
                + "line-height:1.7;"
                + "color:#a1a1aa;"
                + "\">"

                + "This code expires in <strong style=\"color:#e4e4e7;\">"
                + "15 minutes</strong>. "
                + "Your verified Browse session lasts "
                + "<strong style=\"color:#e4e4e7;\">30 minutes</strong>."

                + "</td>"

                + "</tr>"
                + "</table>"

                + "</td>"
                + "</tr>"

                // Security notice
                + "<tr>"
                + "<td style=\"padding:0 28px 28px;\">"

                + "<div style=\""
                + "padding:14px 16px;"
                + "background:#18181b;"
                + "border-radius:10px;"
                + "\">"

                + "<p style=\""
                + "margin:0;"
                + "font-size:12px;"
                + "line-height:1.7;"
                + "color:#a1a1aa;"
                + "\">"

                + "<strong style=\"color:#d4d4d8;\">"
                + "Security reminder</strong><br>"

                + "Never share this code with anyone. "
                + "If you didn't request this verification, "
                + "you can safely ignore this email."

                + "</p>"

                + "</div>"

                + "</td>"
                + "</tr>"

                + "</table>"

                // Footer
                + "<table role=\"presentation\" "
                + "width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "border=\"0\" style=\"max-width:460px;\">"

                + "<tr>"
                + "<td align=\"center\" style=\"padding:22px 12px 0;\">"

                + "<p style=\""
                + "margin:0;"
                + "font-size:11px;"
                + "line-height:1.7;"
                + "color:#52525b;"
                + "\">"

                + "Automated message from GuTube.<br>"
                + "Please do not reply to this email."

                + "</p>"

                + "</td>"
                + "</tr>"
                + "</table>"

                + "</td>"
                + "</tr>"
                + "</table>"

                + "</body>"
                + "</html>";
    }

    @NonNull
    private static String escapeHtml(@NonNull String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static void writeLine(
            @NonNull BufferedWriter writer,
            @NonNull String line
    ) throws IOException {

        writer.write(line);
        writer.write("\r\n");
        writer.flush();
    }

    private static void expectCode(
            @NonNull BufferedReader reader,
            int expected
    ) throws IOException {

        String line = consumeMultiline(reader, expected);

        if (line == null) {
            throw new IOException(
                    "SMTP closed unexpectedly (expected " + expected + ")"
            );
        }
    }

    @NonNull
    private static String consumeMultiline(
            @NonNull BufferedReader reader,
            int expected
    ) throws IOException {

        String prefix = expected + "-";
        String finalPrefix = expected + " ";

        String line;

        while ((line = reader.readLine()) != null) {

            if (line.startsWith(prefix)) {
                continue;
            }

            if (line.startsWith(finalPrefix)
                    || line.equals(String.valueOf(expected))) {
                return line;
            }

            throw new IOException(
                    "SMTP error (expected " + expected + "): " + line
            );
        }

        throw new IOException("SMTP EOF (expected " + expected + ")");
    }

    @NonNull
    private static String escapeJson(@NonNull String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
