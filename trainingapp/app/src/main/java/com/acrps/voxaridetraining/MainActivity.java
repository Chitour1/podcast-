package com.acrps.voxaridetraining;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int VOICE_REQUEST = 71;
    private LinearLayout root, historyList;
    private EditText pickup, destination;
    private TextView voiceText;
    private final int navy = Color.rgb(14, 31, 52);
    private final int orange = Color.rgb(237, 146, 35);
    private final int pale = Color.rgb(246, 247, 249);

    private final String[][] records = {
        {"Orange Fleet", "7GV8+R7R, Doha, Qatar", "2026-08-06 06:28:49", "QAR 4.00"},
        {"Orange Fleet", "7GV8+R7R, Doha, Qatar", "2026-08-06 06:19:30", "QAR 0.00"},
        {"Orange Fleet", "7GQ9+493, Doha, Qatar", "2026-08-06 06:09:31", "QAR 0.00"},
        {"Orange Fleet", "7GQ9+493, Doha, Qatar", "2026-08-06 06:08:47", "QAR 0.00"},
        {"Orange Fleet", "7GQ9+493, Doha, Qatar", "2026-08-06 06:07:21", "QAR 0.00"},
        {"Orange Fleet", "7GQ9+493, Doha, Qatar", "2026-08-06 06:06:48", "QAR 0.00"},
        {"Orange Fleet", "7GQ9+493, Doha, Qatar", "2026-08-06 06:06:31", "QAR 0.00"},
        {"Orange Fleet", "7GQ9+493, Doha, Qatar", "2026-08-06 06:06:20", "QAR 0.00"},
        {"Orange Fleet", "7GQ9+493, Doha, Qatar", "2026-08-06 06:05:57", "QAR 0.00"},
        {"Orange Fleet", "7GVC+8M, JMOI Park, Doha", "2026-08-06 05:37:47", "QAR 0.00"},
        {"Orange Fleet", "7GVC+8M, JMOI Park, Doha", "2026-08-06 05:17:00", "QAR 0.00"},
        {"Orange Fleet", "7GVC+8M, JMOI Park, Doha", "2026-08-06 05:13:32", "QAR 0.00"},
        {"Orange Fleet", "7GVC+8M, JMOI Park, Doha", "2026-08-06 05:13:14", "QAR 0.00"},
        {"Orange Fleet", "7GVC+8M, JMOI Park, Doha", "2026-08-06 05:12:53", "QAR 0.00"},
        {"Orange Fleet", "7GVC+8M, JMOI Park, Doha", "2026-08-06 05:09:38", "QAR 0.00"},
        {"Orange Fleet", "7GVC+8M, JMOI Park, Doha", "2026-08-06 05:08:03", "QAR 0.00"},
        {"City Cab", "Msheireb, Doha Downtown", "2026-08-06 02:47", "Cancelled"},
        {"City Cab", "7GPH+3X7, Doha", "2026-08-06 02:34", "Cancelled"}
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        showGate();
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s); v.setTextSize(sp); v.setTextColor(color);
        v.setPadding(dp(4), dp(5), dp(4), dp(5));
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private Button button(String label) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false);
        b.setTextColor(Color.WHITE); b.setTextSize(16); b.setBackgroundColor(navy);
        b.setPadding(dp(12), dp(12), dp(12), dp(12)); return b;
    }

    private void showGate() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL); root.setPadding(dp(24), dp(54), dp(24), dp(24)); root.setBackgroundColor(Color.WHITE);
        root.addView(text("VoxaRide", 34, navy, true), fullWrap());
        root.addView(text("VOICE BOOKING CONSOLE", 13, orange, true), fullWrap());
        TextView sim = text("TRAINING SIMULATION — NO REAL BOOKINGS", 12, Color.DKGRAY, true);
        sim.setGravity(Gravity.CENTER); sim.setBackgroundColor(Color.rgb(255,240,205)); root.addView(sim, margin(fullWrap(), 0, 18, 0, 26));
        root.addView(text("Enter access key", 18, navy, true), fullWrap());
        EditText key = new EditText(this); key.setHint("Access key"); key.setSingleLine(true); root.addView(key, margin(fullWrap(),0,10,0,10));
        TextView error = text("", 14, Color.rgb(190,44,52), true); root.addView(error, fullWrap());
        Button enter = button("Continue"); root.addView(enter, margin(fullWrap(),0,12,0,8));
        enter.setOnClickListener(v -> {
            if ("2026ai".equalsIgnoreCase(key.getText().toString().trim())) error.setText("This key is outdated. Request a current access key.");
            else error.setText("Access denied. Check the key and try again.");
        });
        Button preview = button("Open training preview"); preview.setBackgroundColor(orange); root.addView(preview, margin(fullWrap(),0,10,0,0));
        preview.setOnClickListener(v -> showApp());
        setContentView(root);
    }

    private void showApp() {
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(pale);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(16), dp(20), dp(16), dp(30)); scroll.addView(root);
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.VERTICAL); bar.setPadding(dp(14),dp(12),dp(14),dp(12)); bar.setBackgroundColor(navy);
        bar.addView(text("VoxaRide", 28, Color.WHITE, true)); bar.addView(text("TRAINING SIMULATION — LOCAL DATA ONLY", 11, Color.rgb(255,214,130), true)); root.addView(bar, fullWrap());
        root.addView(text("Voice booking", 24, navy, true), margin(fullWrap(),0,20,0,8));
        pickup = new EditText(this); pickup.setHint("Pickup location"); pickup.setText("Msheireb, Doha Downtown"); root.addView(pickup, card());
        destination = new EditText(this); destination.setHint("Destination"); destination.setText("JMOI Park, Doha"); root.addView(destination, margin(card(),0,10,0,0));
        Button voice = button("Speak booking request"); voice.setBackgroundColor(orange); root.addView(voice, margin(fullWrap(),0,12,0,0));
        voiceText = text("Try: Book a car from Msheireb to JMOI Park", 13, Color.DKGRAY, false); root.addView(voiceText, fullWrap());
        voice.setOnClickListener(v -> startVoice());
        Button book = button("Book automatically"); root.addView(book, margin(fullWrap(),0,12,0,4));
        book.setOnClickListener(v -> showSuccess());
        root.addView(text("Recent activity", 24, navy, true), margin(fullWrap(),0,26,0,10));
        historyList = new LinearLayout(this); historyList.setOrientation(LinearLayout.VERTICAL); root.addView(historyList, fullWrap());
        for (String[] r : records) addRecord(r);
        setContentView(scroll);
    }

    private void startVoice() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.ENGLISH.toLanguageTag());
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say pickup and destination");
        try { startActivityForResult(i, VOICE_REQUEST); }
        catch (Exception e) { voiceText.setText("Voice recognition is not available on this device."); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == VOICE_REQUEST && result == RESULT_OK && data != null) {
            ArrayList<String> list = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (list != null && !list.isEmpty()) {
                String heard = list.get(0); voiceText.setText("Heard: " + heard);
                String lower = heard.toLowerCase(Locale.US);
                int from = lower.indexOf("from "), to = lower.indexOf(" to ");
                if (from >= 0 && to > from) { pickup.setText(heard.substring(from + 5, to)); destination.setText(heard.substring(to + 4)); }
            }
        }
    }

    private void showSuccess() {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Request completed successfully")
            .setMessage("Provider: Orange Fleet\nPickup: " + pickup.getText() + "\nDestination: " + destination.getText() + "\nEstimated fare: QAR 4.00\n\nTraining simulation only. No real booking was sent.")
            .setPositiveButton("Done", null).show();
    }

    private void addRecord(String[] r) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(15),dp(13),dp(15),dp(13)); card.setBackgroundColor(Color.WHITE);
        LinearLayout first = new LinearLayout(this); first.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = text(r[0].equals("Orange Fleet") ? "🚕" : "●", 25, orange, true); first.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout info = new LinearLayout(this); info.setOrientation(LinearLayout.VERTICAL); info.addView(text(r[1],16,navy,true)); info.addView(text(r[0],12,Color.GRAY,false)); first.addView(info, new LinearLayout.LayoutParams(0,-2,1));
        TextView status = text("Cancelled",12,Color.rgb(200,55,62),true); status.setBackgroundColor(Color.rgb(255,228,231)); first.addView(status);
        card.addView(first); card.addView(text(r[2],13,Color.DKGRAY,false)); card.addView(text(r[3],14,navy,true));
        historyList.addView(card, margin(fullWrap(),0,0,0,10));
    }

    @Override public void onBackPressed() { showGate(); }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }
    private LinearLayout.LayoutParams fullWrap() { return new LinearLayout.LayoutParams(-1,-2); }
    private LinearLayout.LayoutParams card() { LinearLayout.LayoutParams p=fullWrap(); p.setMargins(0,dp(8),0,0); return p; }
    private LinearLayout.LayoutParams margin(LinearLayout.LayoutParams p,int l,int t,int r,int b){p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
}
