package ru.mastervolt.jarvislite;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.RecognitionListener;
import org.vosk.Recognizer;
import org.vosk.android.SpeechService;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;

public class MainActivity extends Activity implements RecognitionListener {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String modelFolder = "vosk-model-small-ru-0.22";
    private static final String[] THEMES = {"Master-Volt", "JARVIS Red", "JARVIS Blue", "JARVIS Dark", "JARVIS Light", "JARVIS Titanium"};
    private static final int[] BG = {0xff14120d,0xff170d10,0xff091725,0xff111111,0xfff0f1f3,0xff171e25};
    private static final int[] ACC = {0xffffce21,0xffff4954,0xff3ab7ff,0xffeeeeee,0xff1761a6,0xff46dbd4};
    private int themeIndex = 2;
    private LinearLayout root, buttons;
    private TextView status, header;
    private Model model;
    private SpeechService service;
    private TextToSpeech tts;
    private boolean speechReady = false, microphoneOn = false, awaitingCommand = false;
    private long wakeTime = 0;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        themeIndex = getPreferences(0).getInt("theme",2);
        buildScreen();
        tts = new TextToSpeech(this, result -> {
            if (result == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(new Locale("ru", "RU"));
                // Device may not have offline TTS voice data. Report this instead of claiming success.
                speechReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
                if (!speechReady) setStatus("Русский синтез речи не установлен. Текстовые ответы доступны.");
            } else setStatus("Синтезатор речи не доступен. Текстовые ответы доступны.");
        });
        setStatus("JARVIS Lite. Офлайн-модель будет загружена при первом запуске.");
        prepareModel();
    }
    private void buildScreen() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20,14,20,10); root.setBackgroundColor(BG[themeIndex]);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        header = new TextView(this); header.setGravity(Gravity.CENTER); header.setText("◉  JARVIS LITE  ◉");
        header.setTextSize(26); header.setTypeface(null, Typeface.BOLD); header.setTextColor(ACC[themeIndex]);
        content.addView(header);
        status = new TextView(this); status.setTextSize(16); status.setGravity(Gravity.CENTER);
        status.setTextColor(themeIndex==4 ? Color.BLACK : Color.WHITE);
        status.setPadding(8,16,8,16); content.addView(status);
        LinearLayout primary = new LinearLayout(this); primary.setOrientation(LinearLayout.HORIZONTAL);
        addButton(primary,"🎤 СЛУШАТЬ / СТОП",()->toggleListening());
        addButton(primary,"НАВИГАТОР",()->openNavigator());
        addButton(primary,"RUTUBE",()->openApp(new String[]{"ru.rutube.app","ru.rutube"}, "RUTUBE"));
        content.addView(primary);
        buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.VERTICAL);
        content.addView(buttons);
        LinearLayout opts = new LinearLayout(this); opts.setOrientation(LinearLayout.HORIZONTAL);
        addButton(opts,"ТЕМА ◀",()->changeTheme(-1));
        addButton(opts,THEMES[themeIndex],()->changeTheme(1));
        addButton(opts,"ТЕМА ▶",()->changeTheme(1));
        content.addView(opts);
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        addButton(row,"Wi-Fi",()->openIntent(new Intent(Settings.ACTION_WIFI_SETTINGS),"Настройки Wi-Fi"));
        addButton(row,"НАСТРОЙКИ",()->openIntent(new Intent(Settings.ACTION_SETTINGS),"Настройки Android"));
        addButton(row,"HUD SPEED",()->setStatus("HUD Speed: выбери установленное приложение в штатном меню. Пакет ещё не подтверждён."));
        content.addView(row);
        TextView info = new TextView(this);
        info.setText("АВТОМОБИЛЬ: климат, сиденья, датчики, TPMS, ESP и темы приборов пока без подтверждённого интерфейса. Никаких команд в ЭБУ не отправляется.\n\nОфлайн: Vosk (микрофон) + штатный Android TTS при наличии голоса. Для запуска распознавания нажмите кнопку.");
        info.setTextColor(themeIndex==4?Color.DKGRAY:0xffbbc4ce); info.setTextSize(12);
        info.setPadding(8,10,8,10); content.addView(info);
        scroll.addView(content); root.addView(scroll); setContentView(root);
    }
    private void addButton(LinearLayout where,String title,Runnable click) {
        Button b=new Button(this); b.setText(title); b.setTextSize(12); b.setAllCaps(false);
        b.setTextColor(Color.BLACK); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ACC[themeIndex]));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,-2,1); p.setMargins(2,2,2,2);
        where.addView(b,p); b.setOnClickListener(v->click.run());
    }
    private void changeTheme(int delta) {
        themeIndex=(themeIndex+delta+THEMES.length)%THEMES.length;
        getPreferences(0).edit().putInt("theme",themeIndex).apply();
        buildScreen(); setStatus("Тема: "+THEMES[themeIndex]+(microphoneOn?". Слушаю":""));
    }
    private void setStatus(String text) {
        handler.post(()->{ if (status!=null) status.setText(text); });
    }
    private void say(String text) {
        setStatus(text);
        // Stop recognition for the short spoken response to reduce self-recognition.
        if (speechReady && tts!=null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null,"jarvis");
    }
    private void prepareModel() {
        new Thread(()->{
            try {
                File dir = new File(getFilesDir(),modelFolder);
                File marker = new File(dir,".complete");
                if (!marker.exists()) {
                    setStatus("Подготавливаю русскую офлайн-модель. Подождите...");
                    copyAssets(modelFolder,dir);
                    if(!new File(dir,"am/final.mdl").exists()) throw new IllegalStateException("Не найден файл модели am/final.mdl");
                    if(!marker.createNewFile()) throw new IllegalStateException("Невозможно подтвердить копирование модели");
                }
                Model ready = new Model(dir.getAbsolutePath());
                handler.post(()->{ model=ready; setStatus("Готов. Нажмите СЛУШАТЬ и скажите «Джарвис»."); });
            } catch(Exception e) { setStatus("Не удалось загрузить офлайн-модель: "+e.getMessage()); }
        }).start();
    }
    private void copyAssets(String source,File dest) throws Exception {
        String[] entries = getAssets().list(source);
        if(entries == null) throw new IllegalStateException("Отсутствуют файлы модели");
        if(entries.length==0) {
            File parent=dest.getParentFile(); if(parent!=null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("Ошибка создания папки");
            try (InputStream in=getAssets().open(source); FileOutputStream out=new FileOutputStream(dest)) {
                byte[] bytes=new byte[8192]; int n; while((n=in.read(bytes))!=-1) out.write(bytes,0,n);
            }
        } else {
            if(!dest.exists() && !dest.mkdirs()) throw new IllegalStateException("Ошибка создания папки модели");
            for(String entry:entries) copyAssets(source+"/"+entry,new File(dest,entry));
        }
    }
    private void toggleListening() {
        if (JarvisListeningService.running) {
            stopService(new Intent(this, JarvisListeningService.class));
            setStatus("Фоновое прослушивание остановлено.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},42);
            return;
        }
        try {
            Intent intent = new Intent(this, JarvisListeningService.class);
            intent.setAction(JarvisListeningService.ACTION_START);
            startForegroundService(intent);
            setStatus("Фоновое прослушивание запускается. Скажите «Джарвис».");
        } catch (Exception e) { setStatus("Не удалось запустить микрофон: "+e.getMessage()); }
    }
    private void stopListening() {
        microphoneOn=false; awaitingCommand=false;
        if(service!=null) {service.stop();service.shutdown();service=null;}
    }
    @Override public void onRequestPermissionsResult(int req,String[] p,int[] grants) {
        super.onRequestPermissionsResult(req,p,grants);
        if(req==42 && grants.length>0 && grants[0]==PackageManager.PERMISSION_GRANTED) toggleListening();
        else setStatus("Разрешение микрофона не выдано.");
    }
    @Override public void onPartialResult(String hypothesis) {}
    @Override public void onResult(String hypothesis) {parseResult(hypothesis);}
    @Override public void onFinalResult(String hypothesis) {parseResult(hypothesis);}
    @Override public void onError(Exception e) {setStatus("Ошибка распознавания: "+e.getMessage());stopListening();}
    @Override public void onTimeout() {setStatus("Время ожидания истекло");stopListening();}
    private void parseResult(String json) {
        try {
            String words=new JSONObject(json).optString("text","").toLowerCase(new Locale("ru","RU")).trim();
            if(words.isEmpty()) return;
            handler.post(()->handle(words));
        } catch(Exception e) {setStatus("Ошибка разбора речи");}
    }
    private void handle(String words) {
        if(words.contains("джарвис")) {
            words=words.replace("джарвис","").trim(); awaitingCommand=true; wakeTime=System.currentTimeMillis();
            if(words.isEmpty()) {say("Слушаю, сэр");return;}
        }
        if(!awaitingCommand) {setStatus("Распознано: "+words+". Скажите «Джарвис».");return;}
        if(System.currentTimeMillis()-wakeTime>14000) {awaitingCommand=false;setStatus("Время команды истекло. Скажите «Джарвис».");return;}
        awaitingCommand=false;
        if(words.contains("навиг")||words.contains("навигац")) {say("Открываю навигацию");openNavigator();}
        else if(words.contains("рутуб")||words.contains("rutube")) {say("Открываю RUTUBE");openApp(new String[]{"ru.rutube.app","ru.rutube"},"RUTUBE");}
        else if(words.contains("вай фай")||words.contains("wi fi")||words.contains("вайфай")) {
            say("Открываю настройки Wi-Fi. Прямое переключение Android ограничивает.");openIntent(new Intent(Settings.ACTION_WIFI_SETTINGS),"Wi-Fi");
        }
        else if(words.contains("настрой")) {say("Открываю настройки");openIntent(new Intent(Settings.ACTION_SETTINGS),"Настройки");}
        else if(words.contains("тема")) {changeTheme(1);say("Тема изменена");}
        else if(words.contains("худ")||words.contains("speed")) {say("HUD Speed запустится после проверки имени пакета установленного приложения");}
        else if(words.contains("климат")||words.contains("печк")||words.contains("обогрев")||words.contains("топлив")||words.contains("давлен")||words.contains("температур")||words.contains("напряжен")||words.contains("спорт")||words.contains("прибор")) {
            say("Эта функция автомобиля пока недоступна без разрешённого интерфейса.");
        } else if(words.contains("радио")||words.contains("музык")) {
            say("Запуск штатного аудио пока не подключён.");
        } else {say("Команда не добавлена: "+words);}
    }
    private void openNavigator() {openApp(new String[]{"ru.yandex.yandexnavi"},"Яндекс Навигатор");}
    private void openApp(String[] packages,String label) {
        for(String pkg:packages) {
            try {Intent i=getPackageManager().getLaunchIntentForPackage(pkg);
                if(i!=null) {i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(i);setStatus("Открываю: "+label);return;}
            } catch(Exception ignored) {}
        }
        setStatus(label+" не установлен или недоступен на мультимедиа.");
    }
    private void openIntent(Intent i,String label) {
        try {startActivity(i);setStatus("Открыто: "+label);} catch(Exception e){setStatus(label+" недоступны через разрешённый Android-интерфейс.");}
    }
    @Override protected void onResume() {
        super.onResume();
        if (JarvisListeningService.running) setStatus("Фоновое прослушивание включено. Скажите «Джарвис».");
    }
    @Override protected void onDestroy() {
        stopListening(); if(tts!=null){tts.stop();tts.shutdown();} if(model!=null)model.close();
        super.onDestroy();
    }
}
