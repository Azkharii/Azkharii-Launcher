package com.cinematic.tvlauncher;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.BaseColumns;
import android.util.Log;

import androidx.tvprovider.media.tv.TvContractCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Read-only consumer of Android TV's system Watch Next ContentProvider. */
public final class WatchNextRepository {
    private static final String TAG = "WatchNextRepository";
    private static final Uri WATCH_NEXT_URI = TvContractCompat.WatchNextPrograms.CONTENT_URI;
    private static final String[] PROJECTION = {
            BaseColumns._ID,
            TvContractCompat.WatchNextPrograms.COLUMN_TITLE,
            TvContractCompat.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContractCompat.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContractCompat.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
            TvContractCompat.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContractCompat.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContractCompat.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            TvContractCompat.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContractCompat.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE
    };

    public interface OnWatchNextChangedListener {
        void onWatchNextChanged();
    }

    private final ContentResolver resolver;
    private final PackageManager packageManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ContentObserver contentObserver;
    private OnWatchNextChangedListener listener;
    private boolean observerRegistered;

    public WatchNextRepository(Context context) {
        Context appContext = context.getApplicationContext();
        resolver = appContext.getContentResolver();
        packageManager = appContext.getPackageManager();
    }

    public synchronized void registerObserver(OnWatchNextChangedListener callback) {
        listener = callback;
        if (observerRegistered) return;

        contentObserver = new ContentObserver(mainHandler) {
            @Override public void onChange(boolean selfChange) {
                WatchNextRepository.this.dispatchChange();
            }
            @Override public void onChange(boolean selfChange, Uri changedUri) {
                WatchNextRepository.this.dispatchChange();
            }
        };

        try {
            resolver.registerContentObserver(WATCH_NEXT_URI, true, contentObserver);
            observerRegistered = true;
        } catch (SecurityException e) {
            contentObserver = null;
            Log.w(TAG, "System Watch Next observer registration denied by this TV firmware.", e);
        } catch (RuntimeException e) {
            contentObserver = null;
            Log.w(TAG, "System Watch Next observer could not be registered.", e);
        }
    }

    private void dispatchChange() {
        mainHandler.post(() -> {
            OnWatchNextChangedListener callback;
            synchronized (WatchNextRepository.this) {
                callback = listener;
            }
            if (callback != null) callback.onWatchNextChanged();
        });
    }

    public synchronized void unregisterObserver() {
        listener = null;
        ContentObserver observer = contentObserver;
        contentObserver = null;
        observerRegistered = false;
        if (observer != null) {
            try {
                resolver.unregisterContentObserver(observer);
            } catch (RuntimeException e) {
                Log.d(TAG, "Watch Next observer was already unregistered.", e);
            }
        }
    }

    public List<Entry> queryContinueWatching() {
        List<Entry> result = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = resolver.query(WATCH_NEXT_URI, PROJECTION, null, null, null);
            if (cursor == null) {
                Log.w(TAG, "System Watch Next provider returned a null cursor.");
                return result;
            }

            int idColumn = cursor.getColumnIndex(BaseColumns._ID);
            int titleColumn = cursor.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_TITLE);
            int packageColumn = cursor.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_PACKAGE_NAME);
            int posterColumn = cursor.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_POSTER_ART_URI);
            int thumbnailColumn = cursor.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_THUMBNAIL_URI);
            int intentColumn = cursor.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_INTENT_URI);
            int positionColumn = cursor.getColumnIndex(
                    TvContractCompat.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS);
            int durationColumn = cursor.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_DURATION_MILLIS);
            int engagementColumn = cursor.getColumnIndex(
                    TvContractCompat.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS);
            int typeColumn = cursor.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE);

            if (idColumn < 0 || titleColumn < 0 || typeColumn < 0) {
                Log.w(TAG, "Watch Next provider omitted a required column.");
                return result;
            }

            int rowsRead = 0;
            int skippedTypes = 0;
            int skippedWithoutTitle = 0;
            int rowsWithoutRoute = 0;
            int rowsWithoutArtwork = 0;
            java.util.Map<String, Integer> publisherRowCounts = new java.util.HashMap<>();
            java.util.Map<String, Integer> publisherTypeCounts = new java.util.HashMap<>();
            while (cursor.moveToNext()) {
                rowsRead++;
                int type = cursor.getInt(typeColumn);
                String packageName = packageColumn >= 0 ? cursor.getString(packageColumn) : null;
                String publisherKey = packageName == null || packageName.trim().isEmpty()
                        ? "(unknown)" : packageName.trim();
                Integer publisherCount = publisherRowCounts.get(publisherKey);
                publisherRowCounts.put(publisherKey, publisherCount == null ? 1 : publisherCount + 1);
                String publisherTypeKey = publisherKey + ":" + type;
                Integer publisherTypeCount = publisherTypeCounts.get(publisherTypeKey);
                publisherTypeCounts.put(publisherTypeKey,
                        publisherTypeCount == null ? 1 : publisherTypeCount + 1);
                if (type != TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
                        && type != TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT) {
                    skippedTypes++;
                    continue;
                }

                String title = cursor.getString(titleColumn);
                if (!WatchNextEntryPolicy.hasDisplayableTitle(title)) {
                    skippedWithoutTitle++;
                    continue;
                }

                String intentUri = intentColumn >= 0 ? cursor.getString(intentColumn) : null;
                boolean supportedResumeIntent = isSupportedResumeIntent(intentUri);
                boolean launchablePublisher = hasLaunchablePublisher(packageName);
                // Keep genuine, titled Watch Next rows visible even if this TV's provider
                // omits an actionable URI and its publisher package is not launchable. The
                // click handler will explain when neither safe route is available; silently
                // dropping every such row made the complete section disappear.
                if (!WatchNextEntryPolicy.hasDisplayableData(
                        title,
                        supportedResumeIntent ? intentUri : null,
                        launchablePublisher ? packageName : null)) {
                    rowsWithoutRoute++;
                }
                if (!supportedResumeIntent) intentUri = null;

                String posterValue = posterColumn >= 0 ? cursor.getString(posterColumn) : null;
                String thumbnailValue = thumbnailColumn >= 0 ? cursor.getString(thumbnailColumn) : null;
                String artworkValue = WatchNextEntryPolicy.preferredArtworkUri(posterValue, thumbnailValue);
                Uri posterUri = null;
                if (artworkValue != null) {
                    try {
                        posterUri = Uri.parse(artworkValue);
                        String scheme = posterUri.getScheme();
                        boolean supportedScheme = "content".equalsIgnoreCase(scheme)
                                || "android.resource".equalsIgnoreCase(scheme)
                                || "file".equalsIgnoreCase(scheme)
                                || "http".equalsIgnoreCase(scheme)
                                || "https".equalsIgnoreCase(scheme);
                        if (!supportedScheme) posterUri = null;
                    } catch (RuntimeException ignored) {
                        posterUri = null;
                    }
                }
                if (posterUri == null) rowsWithoutArtwork++;

                long position = positionColumn >= 0 ? cursor.getLong(positionColumn) : -1L;
                long duration = durationColumn >= 0 ? cursor.getLong(durationColumn) : -1L;
                if (position < 0L || duration <= 0L) {
                    position = -1L;
                    duration = -1L;
                }
                long engagement = engagementColumn >= 0 ? cursor.getLong(engagementColumn) : 0L;
                result.add(new Entry(cursor.getLong(idColumn), title.trim(), posterUri,
                        intentUri == null ? null : intentUri.trim(),
                        packageName == null ? null : packageName.trim(),
                        position, duration, engagement, type));
            }
            Log.d(TAG, "Watch Next query: rowsRead=" + rowsRead
                    + ", accepted=" + result.size()
                    + ", skippedType=" + skippedTypes
                    + ", skippedWithoutTitle=" + skippedWithoutTitle
                    + ", rowsWithoutRoute=" + rowsWithoutRoute
                    + ", rowsWithoutArtwork=" + rowsWithoutArtwork
                    + ", publishers=" + publisherRowCounts
                    + ", publisherTypes=" + publisherTypeCounts);
        } catch (SecurityException e) {
            Log.w(TAG, "Read access to system Watch Next is not granted on this TV.", e);
        } catch (RuntimeException e) {
            Log.w(TAG, "System Watch Next query failed.", e);
        } finally {
            if (cursor != null) {
                try { cursor.close(); } catch (RuntimeException ignored) {}
            }
        }

        Collections.sort(result, Comparator
                .comparingLong(Entry::getLastEngagementTimeUtcMillis).reversed());
        return result;
    }

    private boolean isSupportedResumeIntent(String encodedIntent) {
        if (encodedIntent == null || encodedIntent.trim().isEmpty()) return false;
        try {
            Intent intent = Intent.parseUri(encodedIntent.trim(), Intent.URI_INTENT_SCHEME);
            String action = intent.getAction();
            if (Intent.ACTION_VIEW.equals(action) || Intent.ACTION_MAIN.equals(action)) return true;
            Uri data = intent.getData();
            String scheme = data == null ? null : data.getScheme();
            return action == null && ("http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme));
        } catch (Exception malformed) {
            return false;
        }
    }

    private boolean hasLaunchablePublisher(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        try {
            return packageManager.getLaunchIntentForPackage(packageName.trim()) != null;
        } catch (RuntimeException e) {
            Log.d(TAG, "Publisher package is not launchable: " + packageName, e);
            return false;
        }
    }

    public static final class Entry {
        private final long id;
        private final String title;
        private final Uri posterArtUri;
        private final String intentUri;
        private final String packageName;
        private final long lastPlaybackPositionMillis;
        private final long durationMillis;
        private final long lastEngagementTimeUtcMillis;
        private final int watchNextType;

        public Entry(long id, String title, Uri posterArtUri, String intentUri, String packageName,
                long lastPlaybackPositionMillis, long durationMillis,
                long lastEngagementTimeUtcMillis, int watchNextType) {
            this.id = id;
            this.title = title;
            this.posterArtUri = posterArtUri;
            this.intentUri = intentUri;
            this.packageName = packageName;
            this.lastPlaybackPositionMillis = lastPlaybackPositionMillis;
            this.durationMillis = durationMillis;
            this.lastEngagementTimeUtcMillis = lastEngagementTimeUtcMillis;
            this.watchNextType = watchNextType;
        }

        public long getId() { return id; }
        public String getTitle() { return title; }
        public Uri getPosterArtUri() { return posterArtUri; }
        public String getIntentUri() { return intentUri; }
        public String getPackageName() { return packageName; }
        public long getLastPlaybackPositionMillis() { return lastPlaybackPositionMillis; }
        public long getDurationMillis() { return durationMillis; }
        public long getLastEngagementTimeUtcMillis() { return lastEngagementTimeUtcMillis; }
        public int getWatchNextType() { return watchNextType; }
    }
}
