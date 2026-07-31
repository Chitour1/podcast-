package com.acrps.podcastforge;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.widget.*;
import com.acrps.podcastforge.audio.PodcastProcessor;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private Uri inputUri;
    private Button processButton;
    private ProgressBar progress;
    private TextView status,fileName;
    private Spinner preset;
    private CheckBox denoise,deesser,leveler,master;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle b){
        super.onCreate(b);setContentView(R.layout.activity_main);
        processButton=findViewById(R.id.processButton);progress=findViewById(R.id.progress);status=findViewById(R.id.status);fileName=findViewById(R.id.fileName);preset=findViewById(R.id.presetSpinner);
        denoise=findViewById(R.id.denoise);deesser=findViewById(R.id.deesser);leveler=findViewById(R.id.leveler);master=findViewById(R.id.master);
        String[] presets={"بودكاست متوازن","محاضرة وقاعة","صوت دافئ وعميق","وضوح وبروز أعلى"};preset.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,presets));
        findViewById(R.id.pickButton).setOnClickListener(v->pickAudio());processButton.setOnClickListener(v->process());
    }
    private void pickAudio(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("audio/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,42);}
    @Override protected void onActivityResult(int r,int c,Intent d){super.onActivityResult(r,c,d);if(r==42&&c==RESULT_OK&&d!=null){inputUri=d.getData();try{getContentResolver().takePersistableUriPermission(inputUri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}fileName.setText("تم اختيار التسجيل");processButton.setEnabled(true);status.setText("جاهز للتنقية والهندسة المتدفقة");}}
    private void process(){
        processButton.setEnabled(false);progress.setProgress(0);status.setText("تحليل التسجيل...");
        PodcastProcessor.Options o=new PodcastProcessor.Options();o.preset=preset.getSelectedItemPosition();o.denoise=denoise.isChecked();o.deesser=deesser.isChecked();o.leveler=leveler.isChecked();o.master=master.isChecked();
        executor.submit(()->{
            PowerManager.WakeLock lock=null;Uri outputUri=null;
            try{
                PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);lock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"PodcastForge:Processing");lock.acquire(12*60*60*1000L);
                String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date());ContentValues v=new ContentValues();v.put(MediaStore.Audio.Media.DISPLAY_NAME,"PodcastForge4_"+stamp+".wav");v.put(MediaStore.Audio.Media.MIME_TYPE,"audio/wav");v.put(MediaStore.Audio.Media.RELATIVE_PATH,"Music/PodcastForge");outputUri=getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,v);if(outputUri==null)throw new Exception("تعذر إنشاء ملف الإخراج");
                try(OutputStream os=getContentResolver().openOutputStream(outputUri,"w")){PodcastProcessor.process(this,inputUri,os,o,(p,msg)->runOnUiThread(()->{progress.setProgress(p);status.setText(msg);}));}
                runOnUiThread(()->{progress.setProgress(100);status.setText("اكتملت التنقية والهندسة وحُفظ الملف");processButton.setEnabled(true);Toast.makeText(this,"تم إنشاء النسخة البودكاستية",Toast.LENGTH_LONG).show();});
            }catch(Exception e){if(outputUri!=null)try{getContentResolver().delete(outputUri,null,null);}catch(Exception ignored){}String msg=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();runOnUiThread(()->{status.setText("تعذر إكمال المعالجة: "+msg);processButton.setEnabled(true);});}
            finally{if(lock!=null&&lock.isHeld())lock.release();}
        });
    }
    @Override protected void onDestroy(){executor.shutdownNow();super.onDestroy();}
}
