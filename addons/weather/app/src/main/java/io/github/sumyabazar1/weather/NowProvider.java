package io.github.sumyabazar1.weather;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The weather now as one line ("72° · Sunny"), for the Clean Your TV launcher's weather key, read only. The
 * reading is kept from the last fetch (this one's or the page's). A query that finds it older than
 * Forecast.REFRESH_MS starts one fetch in the background and answers with what it has; the fetch then notifies
 * the launcher, which reads again. Nothing runs unless Home asks, and no place set means no row.
 */
public final class NowProvider extends ContentProvider {
    static final String AUTHORITY = "io.github.sumyabazar1.weather.now";
    static final Uri URI = Uri.parse("content://" + AUTHORITY + "/now");
    private static final String PREFS = "now";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean fetching = new AtomicBoolean();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        Context ctx = getContext();
        SharedPreferences now = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long updated = now.getLong("updated_ms", 0);
        if (Forecast.stale(updated, now.getLong("tried_ms", 0), System.currentTimeMillis())) fetch(ctx);
        MatrixCursor c = new MatrixCursor(new String[] {"line", "updated_ms"});
        if (updated > 0) {
            double tempC = Double.longBitsToDouble(now.getLong("temp_c", 0));
            String line = Forecast.degrees(tempC, fahrenheit(ctx)) + "° · "
                    + ctx.getString(Forecast.sky(now.getInt("code", 3), now.getBoolean("day", true)));
            c.addRow(new Object[] {line, updated});
        }
        return c;
    }

    /** Keeps a reading for Home's line and tells the launcher; the page calls it after each fetch too. */
    static void save(Context ctx, Forecast.Report r) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("temp_c", Double.doubleToRawLongBits(r.tempC))
                .putInt("code", r.code)
                .putBoolean("day", r.day)
                .putLong("updated_ms", System.currentTimeMillis())
                .apply();
        ctx.getContentResolver().notifyChange(URI, null);
    }

    /** The unit the page shows: the saved choice, else options.xml. */
    static boolean fahrenheit(Context ctx) {
        return ctx.getSharedPreferences("options", Context.MODE_PRIVATE).getBoolean("fahrenheit",
                "f".equalsIgnoreCase(ctx.getString(R.string.temperature_unit).trim()));
    }

    private void fetch(Context ctx) {
        SharedPreferences place = ctx.getSharedPreferences("place", Context.MODE_PRIVATE);
        if (!place.contains("name") || !fetching.compareAndSet(false, true)) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("tried_ms", System.currentTimeMillis()).apply();
        double lat = Double.longBitsToDouble(place.getLong("lat", 0));
        double lon = Double.longBitsToDouble(place.getLong("lon", 0));
        io.execute(() -> {
            try {
                save(ctx, Forecast.fetch(lat, lon, Forecast.MIN_DAYS));
            } catch (Exception offline) {
                // Keep the last reading; the next Home after RETRY_MS tries again.
            } finally {
                fetching.set(false);
            }
        });
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("read only");
    }
}
