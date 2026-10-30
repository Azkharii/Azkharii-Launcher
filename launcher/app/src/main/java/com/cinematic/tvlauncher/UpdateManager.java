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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Checks published public releases and hands validated APKs to Android's package installer. */
public final class UpdateManager {
    private static final String TAG="LauncherUpdater";
    private static final String PREFS="cinematic_launcher_prefs";
    private static final String LAST_STATUS="update_last_status";
    private static final String PENDING_INSTALL="update_pending_install_path";
    private static final int MAX_JSON=2*1024*1024;
    private static final long MAX_APK=350L*1024L*1024L;
    private static final String RELEASE_API="https://api.github.com/repos/Azkharii/Azkharii-Launcher/releases/latest";
    private static final String APK_NAME="cinematic-tv-launcher-release.apk";

    private final Activity activity;
    private final SharedPreferences prefs;
    private final ExecutorService executor =
            new ThreadPoolExecutor(0, 1, 15L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Set<HttpURLConnection> activeConnections = ConcurrentHashMap.newKeySet();
    private volatile Future<?> activeTask;
    private volatile File activePartialFile;
    private volatile boolean closed,resumed,checking;

    public UpdateManager(Activity activity){
        this.activity=activity;
        prefs=activity.getSharedPreferences(PREFS,Activity.MODE_PRIVATE);
    }

    /** Updates are checked only on explicit user action, never on a timer or launcher resume. */
    public void onResume(){
        if(closed)return;
        resumed=true;
        tryInstallPendingUpdate();
    }

    public void onPause(){
        resumed=false;
        Future<?> task=activeTask;
        activeTask=null;
        if(task!=null){
            task.cancel(true);
            if(task instanceof Runnable && executor instanceof ThreadPoolExecutor){
                ((ThreadPoolExecutor)executor).remove((Runnable)task);
            }
        }
        checking=false;
        for(HttpURLConnection connection:activeConnections){
            try{connection.disconnect();}catch(RuntimeException ignored){}
        }
        activeConnections.clear();
        File partial=activePartialFile;
        activePartialFile=null;
        if(partial!=null&&partial.exists())partial.delete();
    }
    public void close(){
        closed=true;
        onPause();
        executor.shutdownNow();
    }

    private boolean shouldStop(){
        return closed || !resumed || Thread.currentThread().isInterrupted();
    }

    public void checkNow(){
        if(closed||!resumed||activity.isFinishing()||activity.isDestroyed())return;
        if(checking){
            Toast.makeText(activity,"Ya se está comprobando si hay actualizaciones…",Toast.LENGTH_SHORT).show();
            return;
        }
        checking=true;
        Toast.makeText(activity,"Comprobando actualizaciones…",Toast.LENGTH_SHORT).show();
        try{
            activeTask=executor.submit(this::checkLatestRelease);
        }catch(java.util.concurrent.RejectedExecutionException e){
            checking=false;
            recordCheckFailure("CHECK_NOT_SCHEDULED");
            showManualStatus("No se pudo iniciar la comprobación de actualizaciones.");
        }
    }

    private void recordCheckFailure(String status){
        prefs.edit().putString(LAST_STATUS,status).apply();
    }

    private void showManualStatus(String message){
        handler.post(()->{
            if(closed||!resumed||activity.isFinishing()||activity.isDestroyed())return;
            Toast.makeText(activity,message,Toast.LENGTH_LONG).show();
        });
    }

    private void checkLatestRelease(){
        HttpURLConnection connection=null;
        try{
            if(shouldStop())return;
            connection=(HttpURLConnection)new URL(RELEASE_API).openConnection();
            activeConnections.add(connection);
            if(shouldStop())return;
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Accept","application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version","2022-11-28");
            connection.setRequestProperty("User-Agent","CinematicTVLauncher/"+BuildConfig.VERSION_NAME);

            int status=connection.getResponseCode();
            if(status==404){
                recordCheckFailure("NO_PUBLIC_RELEASE");
                showManualStatus("GitHub no encontró una versión pública publicada.");
                return;
            }
            if(status==403||status==429){
                recordCheckFailure("RATE_LIMITED");
                showManualStatus("GitHub limitó temporalmente las comprobaciones. Inténtalo más tarde.");
                return;
            }
            if(status<200||status>=300){
                recordCheckFailure("HTTP_"+status);
                showManualStatus("No se pudo consultar GitHub (HTTP "+status+").");
                return;
            }

            JSONObject release=new JSONObject(readLimited(connection.getInputStream(),MAX_JSON));
            if(release.optBoolean("draft",false)||release.optBoolean("prerelease",false)){
                prefs.edit().putString(LAST_STATUS,"NO_STABLE_RELEASE").apply();
                showManualStatus("No hay una versión estable pública disponible.");
                return;
            }

            String tag=release.optString("tag_name","");
            long remote=UpdateVersionPolicy.parseVersionCodeFromTag(tag);
            String releaseUrl=release.optString("html_url","https://github.com/Azkharii/Azkharii-Launcher/releases");
            if(remote<=0L){
                recordCheckFailure("INVALID_RELEASE_TAG");
                showManualStatus("La publicación existe, pero su etiqueta de versión no se pudo interpretar.");
                return;
            }

            if(!UpdateVersionPolicy.isNewer(remote,BuildConfig.VERSION_CODE)){
                prefs.edit().putString(LAST_STATUS,"CURRENT").apply();
                showManualStatus("Cinematic TV ya está actualizado ("+
                        UpdateVersionPolicy.formatDisplayVersion(BuildConfig.VERSION_NAME)+").");
                return;
            }

            String apkUrl=null;
            String checksumUrl=null;
            String digest=null;
            long size=-1L;
            JSONArray assets=release.optJSONArray("assets");
            if(assets!=null){
                for(int i=0;i<assets.length();i++){
                    JSONObject asset=assets.optJSONObject(i);
                    if(asset==null)continue;
                    String name=asset.optString("name","");
                    if(APK_NAME.equals(name)){
                        apkUrl=asset.optString("browser_download_url",null);
                        size=asset.optLong("size",-1L);
                        String raw=asset.optString("digest",null);
                        if(raw!=null&&raw.startsWith("sha256:"))digest=raw.substring(7).trim();
                    }else if((APK_NAME+".sha256").equals(name)){
                        checksumUrl=asset.optString("browser_download_url",null);
                    }
                }
            }

            String displayVersion=UpdateVersionPolicy.formatDisplayVersion(tag);
            if(apkUrl==null||size<=0L||size>MAX_APK){
                recordCheckFailure("MISSING_APK_ASSET");
                handler.post(()->showUnverifiedReleaseDialog(displayVersion,releaseUrl,
                        "La versión nueva está publicada, pero falta el APK esperado o su tamaño no es válido. No se instalará nada."));
                return;
            }

            // Older release uploads may not expose the REST asset digest. The release workflow
            // also publishes an APK.sha256 sidecar; use it as a fallback instead of silently
            // discarding an otherwise valid update notification.
            if(digest==null||!digest.matches("(?i)[0-9a-f]{64}")){
                digest=fetchChecksum(checksumUrl);
            }
            if(digest==null||!digest.matches("(?i)[0-9a-f]{64}")){
                recordCheckFailure("MISSING_APK_SHA256");
                handler.post(()->showUnverifiedReleaseDialog(displayVersion,releaseUrl,
                        "La versión nueva está publicada, pero no se pudo verificar su SHA-256. Por seguridad, no se descargará ni instalará el APK."));
                return;
            }

            UpdateInfo info=new UpdateInfo(displayVersion,remote,apkUrl,
                    digest.toLowerCase(Locale.ROOT),size,releaseUrl);
            prefs.edit().putString(LAST_STATUS,"UPDATE_AVAILABLE").apply();
            handler.post(()->showUpdateDialog(info));
        }catch(Exception e){
            if(!shouldStop()){
                recordCheckFailure("CHECK_FAILED");
                Log.w(TAG,"Manual update check failed; the installed launcher remains usable.",e);
                showManualStatus("Falló la comprobación. Revisa la conexión y vuelve a intentarlo.");
            }
        }finally{
            if(connection!=null){
                activeConnections.remove(connection);
                connection.disconnect();
            }
            checking=false;
        }
    }

    private String fetchChecksum(String checksumUrl){
        if(checksumUrl==null||checksumUrl.trim().isEmpty())return null;
        HttpURLConnection connection=null;
        try{
            if(shouldStop())return null;
            connection=(HttpURLConnection)new URL(checksumUrl).openConnection();
            activeConnections.add(connection);
            if(shouldStop())return null;
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            connection.setRequestProperty("User-Agent","CinematicTVLauncher/"+BuildConfig.VERSION_NAME);
            int status=connection.getResponseCode();
            if(status<200||status>=300)return null;
            String content=readLimited(connection.getInputStream(),4096);
            java.util.regex.Matcher matcher=java.util.regex.Pattern
                    .compile("(?i)(?:^|\\s)([0-9a-f]{64})(?:\\s|$)")
                    .matcher(content);
            return matcher.find()?matcher.group(1):null;
        }catch(Exception e){
            Log.w(TAG,"Could not read release SHA-256 sidecar.",e);
            return null;
        }finally{
            if(connection!=null){
                activeConnections.remove(connection);
                connection.disconnect();
            }
        }
    }

    private void showUnverifiedReleaseDialog(String version,String releaseUrl,String reason){
        if(closed||!resumed||activity.isFinishing()||activity.isDestroyed())return;
        AlertDialog dialog=new AlertDialog.Builder(activity)
                .setTitle("Actualización publicada")
                .setMessage("Nueva versión: "+version+"\n\n"+reason)
                .setPositiveButton("Ver publicación",(d,which)->{
                    try{
                        Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse(releaseUrl));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(intent);
                    }catch(Exception e){
                        Toast.makeText(activity,"No se pudo abrir la publicación de GitHub.",Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cerrar",null)
                .create();
        dialog.show();
    }

    private void showUpdateDialog(UpdateInfo info){
        if(closed||!resumed||activity.isFinishing()||activity.isDestroyed())return;
        String installedVersion=UpdateVersionPolicy.formatDisplayVersion(BuildConfig.VERSION_NAME);
        if(BuildConfig.DEBUG||BuildConfig.APPLICATION_ID.endsWith(".debug")){
            showUnverifiedReleaseDialog(info.displayVersion,info.releaseUrl,
                    "Esta compilación de pruebas no puede actualizarse directamente con el APK estable. Instala la versión estable desde la publicación.");
            return;
        }
        String text=installedVersion+" → "+info.displayVersion
                +"\n\nSe verificará el APK antes de abrir el instalador.";
        new AlertDialog.Builder(activity).setTitle("Actualizar Cinematic TV").setMessage(text)
                .setPositiveButton("Actualizar",(dialog,which)->download(info))
                .setNegativeButton("Después",null).show();
    }

    private void download(UpdateInfo info){
        if(closed||!resumed)return;
        Toast.makeText(activity,"Descargando actualización…",Toast.LENGTH_SHORT).show();
        try{activeTask=executor.submit(()->{
            File partial=null;HttpURLConnection connection=null;
            try{
                if(shouldStop())return;
                connection=(HttpURLConnection)new URL(info.url).openConnection();
                activeConnections.add(connection);
                if(shouldStop())return;
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
                activePartialFile=partial;
                if(shouldStop())throw new java.io.InterruptedIOException("Launcher left foreground");
                try(InputStream input=new BufferedInputStream(connection.getInputStream());
                    FileOutputStream output=new FileOutputStream(partial)){
                    byte[] buffer=new byte[16384];long total=0;int count;
                    while((count=input.read(buffer))!=-1){
                        if(shouldStop())throw new java.io.InterruptedIOException("Launcher left foreground");
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
                partial=null;
                activePartialFile=null;
                String path=validated.getAbsolutePath();
                handler.post(()->{
                    if(closed)return;
                    if(!resumed){
                        prefs.edit().putString(PENDING_INSTALL,path).apply();
                        return;
                    }
                    installOrRequestPermission(path);
                });
            }catch(Exception e){
                if(partial!=null&&partial.exists())partial.delete();
                if(activePartialFile==partial)activePartialFile=null;
                if(!shouldStop()){
                    Log.w(TAG,"APK download/validation failed; installer was not opened.",e);
                    handler.post(()->{if(!closed&&resumed&&!activity.isFinishing())Toast.makeText(activity,
                        "No se pudo verificar la actualización; no se instaló nada.",Toast.LENGTH_LONG).show();});
                }
            }finally{
                if(connection!=null){
                    activeConnections.remove(connection);
                    connection.disconnect();
                }
            }
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
        if(closed)return;
        if(!resumed){
            prefs.edit().putString(PENDING_INSTALL,path).apply();
            return;
        }
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
        final String displayVersion,url,sha256,releaseUrl;final long version,size;
        UpdateInfo(String displayVersion,long version,String url,String sha256,long size,String releaseUrl){
            this.displayVersion=displayVersion;this.version=version;this.url=url;this.sha256=sha256;this.size=size;
            this.releaseUrl=releaseUrl;
        }
    }
}
