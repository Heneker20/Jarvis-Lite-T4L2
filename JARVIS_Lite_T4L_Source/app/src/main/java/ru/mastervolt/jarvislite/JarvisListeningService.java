package ru.mastervolt.jarvislite;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.os.*;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import org.json.JSONObject;
import org.vosk.*;
import org.vosk.android.SpeechService;
import org.vosk.RecognitionListener;
import java.io.*;
import java.util.Locale;

/** User-initiated foreground microphone service. No boot receiver or hidden recording. */
public class JarvisListeningService extends Service implements RecognitionListener {
    public static final String ACTION_START = "ru.mastervolt.jarvislite.START";
    public static final String ACTION_STOP = "ru.mastervolt.jarvislite.STOP";
    public static volatile boolean running = false;
    private static final String CHANNEL = "jarvis_mic";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled = false;
    private Model model;
    private SpeechService speech;
    private TextToSpeech tts;
    private boolean awaitingCommand = false;
    private long wakeTime = 0;
    private boolean ttsReady = false;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "JARVIS: микрофон", NotificationManager.IMPORTANCE_LOW));
        tts = new TextToSpeech(this, result -> {
            if (result == TextToSpeech.SUCCESS && tts != null) {
                int value = tts.setLanguage(new Locale("ru", "RU"));
                ttsReady = value != TextToSpeech.LANG_NOT_SUPPORTED && value != TextToSpeech.LANG_MISSING_DATA;
            }
        });
    }
    private Notification notification(String content) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent opening = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, JarvisListeningService.class).setAction(ACTION_STOP);
        PendingIntent stopping = PendingIntent.getService(this, 2, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("JARVIS Lite — микрофон включён")
                .setContentText(content).setContentIntent(opening)
                .addAction(android.R.drawable.ic_media_pause, "ОСТАНОВИТЬ", stopping)
                .setOngoing(true).build();
    }
    private void update(String message) {
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(7, notification(message));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent != null && ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if(running) return START_NOT_STICKY;
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return START_NOT_STICKY;
        }
        startForeground(7, notification("Подготавливаю офлайн-модель…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        running=true; cancelled=false;
        new Thread(this::initRecognition,"jarvis-voskrun").start();
        return START_NOT_STICKY;
    }
    private void initRecognition() {
        try {
            String folder="vosk-model-small-ru-0.22";
            File dir=new File(getFilesDir(),folder);
            File marker=new File(dir,".complete");
            if(!marker.exists()) {
                copyAssets(folder,dir);
                if(!new File(dir,"am/final.mdl").exists()) throw new IOException("Нет модели Vosk");
                if(!marker.exists()&&!marker.createNewFile()) throw new IOException("Ошибка модели");
            }
            Model m=new Model(dir.getAbsolutePath());
            if(cancelled) {m.close();return;}
            model=m;
            Recognizer rec=new Recognizer(m,16000.0f);
            SpeechService listener=new SpeechService(rec,16000.0f);
            if(cancelled) {listener.shutdown();return;}
            speech=listener;
            handler.post(()->{
                if(cancelled) return;
                try { speech.startListening(this); update("Слушаю слово «Джарвис»"); }
                catch(Exception e) { fail("Микрофон недоступен: "+e.getMessage()); }
            });
        } catch(Exception e) { fail("Ошибка запуска: "+e.getMessage()); }
    }
    private void copyAssets(String path,File dest) throws IOException {
        String[] entries=getAssets().list(path);
        if(entries==null) throw new IOException("Нет офлайн-модели: "+path);
        if(entries.length==0) {
            File parent=dest.getParentFile(); if(parent!=null&&!parent.exists()&&!parent.mkdirs())throw new IOException("Не удалось создать папку");
            try(InputStream in=getAssets().open(path);OutputStream out=new FileOutputStream(dest)){
                byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(cancelled)return;out.write(buffer,0,n);}
            }
        } else {
            if(!dest.exists()&&!dest.mkdirs())throw new IOException("Не удалось создать папку модели");
            for(String entry:entries){if(cancelled)return;copyAssets(path+"/"+entry,new File(dest,entry));}
        }
    }
    private void fail(String message) {
        handler.post(()->{if(!cancelled){update(message);stopSelf();}});
    }
    @Override public void onPartialResult(String result) {}
    @Override public void onResult(String result) { parse(result); }
    @Override public void onFinalResult(String result) { parse(result); }
    @Override public void onError(Exception e) { fail("Ошибка распознавания: "+e.getMessage()); }
    @Override public void onTimeout() { fail("Время ожидания истекло"); }
    private void parse(String json) {
        if(cancelled) return;
        try {
            String words=new JSONObject(json).optString("text", "").toLowerCase(new Locale("ru","RU")).trim();
            if(!words.isEmpty()) handler.post(()->handle(words));
        }catch(Exception ignored) {}
    }
    private void say(String phrase) {
        update(phrase);
        if(ttsReady&&tts!=null)tts.speak(phrase,TextToSpeech.QUEUE_FLUSH,null,"jarvis-bg");
    }
    private void handle(String words) {
        if(cancelled)return;
        if(words.contains("джарвис")) {
            words=words.replace("джарвис","").trim();awaitingCommand=true;wakeTime=System.currentTimeMillis();
            if(words.isEmpty()){say("Слушаю, сэр");return;}
        }
        if(!awaitingCommand)return;
        if(System.currentTimeMillis()-wakeTime>14000){awaitingCommand=false;return;}
        awaitingCommand=false;
        if(words.contains("навиг")||words.contains("навигац")){
            say("Открываю навигацию");openApp(new String[]{"ru.yandex.yandexnavi"},"Навигатор");
        }else if(words.contains("рутуб")||words.contains("rutube")){
            say("Открываю RUTUBE");openApp(new String[]{"ru.rutube.app","ru.rutube"},"RUTUBE");
        }else if(words.contains("вай фай")||words.contains("вайфай")||words.contains("wi fi")){
            say("Настройки Wi-Fi");openIntent(new Intent(Settings.ACTION_WIFI_SETTINGS),"Wi-Fi");
        }else if(words.contains("настрой")){
            say("Настройки");openIntent(new Intent(Settings.ACTION_SETTINGS),"Настройки");
        }else if(words.contains("тема")){
            android.content.SharedPreferences prefs = getSharedPreferences("MainActivity", MODE_PRIVATE);
            int next = (prefs.getInt("theme",2)+1)%6;
            prefs.edit().putInt("theme",next).apply();
            say("Тема изменена");
        }else if(words.contains("худ")||words.contains("speed")) {
            say("HUD Speed запустится после проверки имени пакета установленного приложения");
        }else if(words.contains("климат")||words.contains("печк")||words.contains("обогрев")||words.contains("топлив")||words.contains("давлен")||words.contains("температур")||words.contains("напряжен")||words.contains("спорт")||words.contains("прибор")){
            say("Эта функция автомобиля пока недоступна без разрешённого интерфейса.");
        }else if(words.contains("радио")||words.contains("музык")){
            say("Запуск штатного аудио пока не подключён.");
        }else{say("Команда не добавлена");}
    }
    private void openApp(String[] packages,String label) {
        for(String pkg:packages) {
            Intent intent=getPackageManager().getLaunchIntentForPackage(pkg);
            if(intent!=null){openIntent(intent,label);return;}
        }
        update(label+" не найден");
    }
    private void openIntent(Intent intent,String label) {
        // Android 10+ can block launching an Activity from background. Keep service alive and expose a tappable notification.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {startActivity(intent);}catch(Exception e){update(label+": откройте приложение вручную");}
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy() {
        cancelled=true;running=false;
        if(speech!=null){speech.stop();speech.shutdown();speech=null;}
        if(model!=null){model.close();model=null;}
        if(tts!=null){tts.stop();tts.shutdown();tts=null;}
        super.onDestroy();
    }
}
