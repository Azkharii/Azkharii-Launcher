package com.cinematic.tvlauncher;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Checks published public releases and hands validated APKs to Android's package installer. */
public final class UpdateManager {
    private static final String TAG="LauncherUpdater";
    private static final String PREFS="cinematic_launcher_prefs";
    private static final String LAST_SUCCESS="update_last_success_ms";
    private static final String LAST_ATTEMPT="update_last_attempt_ms";
    private static final String FAILURE_COUNT="update_check_failure_count";
    private static final String LAST_STATUS="update_last_status";
    private static final String PROMPT_CODE="update_last_prompted_code";
    private static final String PROMPT_TIME="update_last_prompted_at";
    private static final String PENDING_INSTALL="update_pending_install_path";
    private static final long REPROMPT=24L*60L*60L*1000L;
    private static final int MAX_JSON=2*1024*1024;
    private static final long MAX_APK=350L*1024L*1024L;
    private static final String RELEASE_API="https://api.github.com/repos/Azkharii/Azkharii-Launcher/releases/latest";
    private static final String APK_NAME="cinematic-tv-launcher-release.apk";

    private final Activity activity;
    private final SharedPreferences prefs;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private volatile boolean closed,resumed,checking;

    private final Runnable periodicCheck=new Runnable(){
        @Override public void run(){
            if(closed||!resumed)return;
            checkIfDue();
            scheduleNextCheck();
        }
    };

    public UpdateManager(Activity activity){
        this.activity=activity;
        prefs=activity.getSharedPreferences(PREFS,Activity.MODE_PRIVATE);
    }
    public void onResume(){
        if(closed)return;
        resumed=true;
        if(BuildConfig.DEBUG||BuildConfig.APPLICATION_ID.endsWith(".debug"))return;
        handler.removeCallbacks(periodicCheck);
        if(!tryInstallPendingUpdate())checkIfDue();
        scheduleNextCheck();
    }
    public void onPause(){resumed=false;handler.removeCallbacks(periodicCheck);}
    public void close(){closed=true;resumed=false;handler.removeCallbacksAndMessages(null);executor.shutdownNow();}

    private void checkIfDue(){
        if(closed||!resumed||checking)return;
        long now=System.currentTimeMillis();
        long delay=UpdateCheckPolicy.nextDelayMillis(now,
                prefs.getLong(LAST_SUCCESS,0L),
                prefs.getLong(LAST_ATTEMPT,0L),
                prefs.getInt(FAILURE_COUNT,0));
        if(delay>0L)return;
        prefs.edit().putLong(LAST_ATTEMPT,now).apply();
        checking=true;
        try{executor.execute(this::checkLatestRelease);}
        catch(java.util.concurrent.RejectedExecutionException ignored){
            checking=false;
            recordCheckFailure("CHECK_NOT_SCHEDULED");
            scheduleNextCheck();
        }
    }

    private void scheduleNextCheck(){
        handler.removeCallbacks(periodicCheck);
        if(closed||!resumed||checking)return;
        long now=System.currentTimeMillis();
        long delay=UpdateCheckPolicy.nextDelayMillis(now,
                prefs.getLong(LAST_SUCCESS,0L),
                prefs.getLong(LAST_ATTEMPT,0L),
                prefs.getInt(FAILURE_COUNT,0));
        handler.postDelayed(periodicCheck,Math.max(1000L,delay));
    }

    private void recordCheckSuccess(){
        prefs.edit().putLong(LAST_SUCCESS,System.currentTimeMillis())
                .putInt(FAILURE_COUNT,0).apply();
    }

    private void recordCheckFailure(String status){
        int failures=Math.min(16,prefs.getInt(FAILURE_COUNT,0)+1);
        prefs.edit().putString(LAST_STATUS,status).putInt(FAILURE_COUNT,failures).apply();
    }

    private void checkLatestRelease(){
        HttpURLConnection connection=null;
        try{
            connection=(HttpURLConnection)new URL(RELEASE_API).openConnection();
            connection.setRequestMethod("GET");connection.setConnectTimeout(8000);connection.setReadTimeout(10000);
            connection.setRequestProperty("Accept","application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version","2026-03-10");
            connection.setRequestProperty("User-Agent","CinematicTVLauncher/"+BuildConfig.VERSION_NAME);
            int status=connection.getResponseCode();
            if(status==404){
                recordCheckFailure("PRIVATE_OR_NO_PUBLIC_RELEASE");
                Log.w(TAG,"Latest release is not anonymously readable; repository may be private.");return;
            }
            if(status==403||status==429){
                recordCheckFailure("RATE_LIMITED");Log.w(TAG,"GitHub rate limit, HTTP "+status);return;
            }
            if(status<200||status>=300){
                recordCheckFailure("HTTP_"+status);Log.w(TAG,"Release HTTP status "+status);return;
            }
            JSONObject release=new JSONObject(readLimited(connection.getInputStream(),MAX_JSON));
            if(release.optBoolean("draft",false)||release.optBoolean("prerelease",false)){
                recordCheckSuccess();return;
            }
            long remote=UpdateVersionPolicy.parseVersionCodeFromTag(release.optString("tag_name",""));
            if(!UpdateVersionPolicy.isNewer(remote,BuildConfig.VERSION_CODE)){
                prefs.edit().putString(LAST_STATUS,"CURRENT").apply();
                recordCheckSuccess();return;
            }
            JSONArray assets=release.optJSONArray("assets");
            String url=null,digest=null;long size=-1L;
            if(assets!=null)for(int i=0;i<assets.length();i++){
                JSONObject asset=assets.optJSONObject(i);
                if(asset==null||!APK_NAME.equals(asset.optString("name")))continue;
                url=asset.optString("browser_download_url",null);
                String raw=asset.optString("digest",null);
                if(raw!=null&&raw.startsWith("sha256:"))digest=raw.substring(7).trim();
                size=asset.optLong("size",-1L);break;
            }
            if(url==null||digest==null||digest.length()!=64||size<=0L||size>MAX_APK){
                recordCheckFailure("MISSING_ASSET_OR_DIGEST");
                Log.w(TAG,"Latest release lacks expected APK asset, size, or SHA-256 digest.");return;
            }
            UpdateInfo info=new UpdateInfo(release.optString("name",release.optString("tag_name","")),
                    release.optString("body",""),remote,url,digest.toLowerCase(Locale.ROOT),size);
            prefs.edit().putString(LAST_STATUS,"UPDATE_AVAILABLE").apply();
            recordCheckSuccess();
            handler.post(()->showUpdateDialog(info));
        }catch(Exception e){
            recordCheckFailure("CHECK_FAILED");
            Log.w(TAG,"Update check failed; installed launcher remains usable.",e);
        }finally{
            if(connection!=null)connection.disconnect();
            checking=false;
            handler.post(this::scheduleNextCheck);
        }
    }

    private void showUpdateDialog(UpdateInfo info){
        if(closed||!resumed||activity.isFinishing()||activity.isDestroyed())return;
        if(prefs.getLong(PROMPT_CODE,0L)==info.version
                &&System.currentTimeMillis()-prefs.getLong(PROMPT_TIME,0L)<REPROMPT)return;
        prefs.edit().putLong(PROMPT_CODE,info.version).putLong(PROMPT_TIME,System.currentTimeMillis()).apply();
        String notes=info.notes==null?"":info.notes.trim();
        if(notes.length()>500)notes=notes.substring(0,500)+"…";
        String text="Instalada: "+BuildConfig.VERSION_NAME+" ("+BuildConfig.VERSION_CODE+")"
                +"\nDisponible: "+info.name+" ("+info.version+")"
                +(notes.isEmpty()?"":"\n\n"+notes)
                +"\n\nSe verificará el APK antes de abrir el instalador de Android. La instalación requiere tu aprobación.";
        new AlertDialog.Builder(activity).setTitle("Actualización de Cinematic TV").setMessage(text)
                .setPositiveButton("Descargar e instalar",(dialog,which)->download(info))
                .setNegativeButton("Después",null).show();
    }

    private void download(UpdateInfo info){
        if(closed)return;
        Toast.makeText(activity,"Descargando actualización…",Toast.LENGTH_SHORT).show();
        try{executor.execute(()->{
            File partial=null;HttpURLConnection connection=null;
            try{
                connection=(HttpURLConnection)new URL(info.url).openConnection();
                connection.setRequestMethod("GET");connection.setConnectTimeout(15000);connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent","CinematicTVLauncher/"+BuildConfig.VERSION_NAME);
                connection.setRequestProperty("Accept","application/octet-stream");
                int status=connection.getResponseCode();
                if(status<200||status>=300)throw new IllegalStateException("APK HTTP status "+status);
                long contentLength=connection.getContentLengthLong();
                if(contentLength>MAX_APK||(contentLength>0&&contentLength!=info.size))throw new IllegalStateException("APK size mismatch");
                File directory=new File(activity.getCacheDir(),"updates");
                if(!directory.exists()&&!directory.mkdirs())throw new IllegalStateException("Cannot create update cache");
                partial=new File(directory,APK_NAME+".partial");
                try(InputStream input=new BufferedInputStream(connection.getInputStream());
                    FileOutputStream output=new FileOutputStream(partial)){
                    byte[] buffer=new byte[16384];long total=0;int count;
                    while((count=input.read(buffer))!=-1){
                        total+=count;if(total>MAX_APK||total>info.size)throw new IllegalStateException("APK larger than expected");
                        output.write(buffer,0,count);
                    }
                    output.getFD().sync();
                    if(total!=info.size)throw new IllegalStateException("Incomplete APK");
                }
                if(!sha256(partial).equalsIgnoreCase(info.sha256))throw new SecurityException("APK SHA-256 mismatch");
                PackageInfo archive=activity.getPackageManager().getPackageArchiveInfo(partial.getAbsolutePath(),0);
                if(archive==null||!BuildConfig.APPLICATION_ID.equals(archive.packageName))throw new SecurityException("APK package ID mismatch");
                long archiveVersion=Build.VERSION.SDK_INT>=28?archive.getLongVersionCode():archive.versionCode;
                if(archiveVersion!=info.version||!UpdateVersionPolicy.isNewer(archiveVersion,BuildConfig.VERSION_CODE))
                    throw new SecurityException("APK version does not match release tag");
                File validated=new File(directory,APK_NAME);
                if(validated.exists()&&!validated.delete())throw new IllegalStateException("Cannot replace cached APK");
                if(!partial.renameTo(validated))throw new IllegalStateException("Cannot finalize validated APK");
                partial=null;String path=validated.getAbsolutePath();
                handler.post(()->installOrRequestPermission(path));
            }catch(Exception e){
                if(partial!=null&&partial.exists())partial.delete();
                Log.w(TAG,"APK download/validation failed; installer was not opened.",e);
                handler.post(()->{if(!closed&&!activity.isFinishing())Toast.makeText(activity,
                    "No se pudo verificar la actualización; no se instaló nada.",Toast.LENGTH_LONG).show();});
            }finally{if(connection!=null)connection.disconnect();}
        });}catch(java.util.concurrent.RejectedExecutionException ignored){}
    }

    private boolean tryInstallPendingUpdate(){
        String path=prefs.getString(PENDING_INSTALL,null);
        if(path==null||path.trim().isEmpty())return false;
        File apk=new File(path);
        if(!apk.isFile()){prefs.edit().remove(PENDING_INSTALL).apply();return false;}
        if(Build.VERSION.SDK_INT>=26&&!activity.getPackageManager().canRequestPackageInstalls())return false;
        prefs.edit().remove(PENDING_INSTALL).apply();launchInstaller(apk);return true;
    }
    private void installOrRequestPermission(String path){
        File apk=new File(path);
        if(!apk.isFile()||closed||activity.isFinishing())return;
        if(Build.VERSION.SDK_INT>=26&&!activity.getPackageManager().canRequestPackageInstalls()){
            prefs.edit().putString(PENDING_INSTALL,path).apply();
            new AlertDialog.Builder(activity).setTitle("Permitir actualizaciones")
                .setMessage("Android requiere autorización para iniciar instalaciones. Activa el permiso para Cinematic TV; al volver se abrirá el instalador del APK verificado.")
                .setPositiveButton("Abrir permiso",(dialog,which)->{
                    try{activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:"+activity.getPackageName())));}
                    catch(Exception e){Log.w(TAG,"Cannot open install-source settings",e);}
                }).setNegativeButton("Ahora no",(dialog,which)->prefs.edit().remove(PENDING_INSTALL).apply()).show();
            return;
        }
        launchInstaller(apk);
    }
    private void launchInstaller(File apk){
        try{
            Uri uri=FileProvider.getUriForFile(activity,activity.getPackageName()+".fileprovider",apk);
            Intent intent=new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri,"application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);prefs.edit().putString(LAST_STATUS,"INSTALLER_OPENED").apply();
        }catch(Exception e){
            Log.w(TAG,"Could not open Android Package Installer",e);
            Toast.makeText(activity,"Android no pudo abrir el instalador del APK.",Toast.LENGTH_LONG).show();
        }
    }
    private static String readLimited(InputStream input,int max) throws Exception{
        try(InputStream stream=input;java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int count,total=0;
            while((count=stream.read(buffer))!=-1){total+=count;if(total>max)throw new IllegalStateException("Release JSON too large");out.write(buffer,0,count);}
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private static String sha256(File file)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=new FileInputStream(file)){byte[] buffer=new byte[16384];int count;
            while((count=input.read(buffer))!=-1)digest.update(buffer,0,count);}
        StringBuilder out=new StringBuilder(64);
        for(byte value:digest.digest())out.append(String.format(Locale.ROOT,"%02x",value&0xff));
        return out.toString();
    }
    private static final class UpdateInfo{
        final String name,notes,url,sha256;final long version,size;
        UpdateInfo(String name,String notes,long version,String url,String sha256,long size){
            this.name=name;this.notes=notes;this.version=version;this.url=url;this.sha256=sha256;this.size=size;
        }
    }
}
