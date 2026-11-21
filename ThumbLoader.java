package com.ravenrock235.glassgallery;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ThumbLoader {
    private static final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(maxMemory / 6) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount() / 1024;
        }
    };

    private static final ExecutorService pool = Executors.newFixedThreadPool(4);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static void load(final String path, final ImageView view, final int reqSize) {
        view.setTag(path);
        final String key = path + "_" + reqSize;
        Bitmap cached = cache.get(key);
        if (cached != null && !cached.isRecycled()) {
            view.setImageBitmap(cached);
            return;
        }

        view.setImageBitmap(null);
        pool.execute(new Runnable() {
				@Override
				public void run() {
					if (!path.equals(view.getTag())) return;
					final Bitmap bmp = decodeSampled(path, reqSize, reqSize);
					if (bmp != null) {
						cache.put(key, bmp);
						mainHandler.post(new Runnable() {
								@Override
								public void run() {
									if (path.equals(view.getTag())) {
										view.setImageBitmap(bmp);
									}
								}
							});
					}
				}
			});
    }

    public static void preload(final String path, final int reqSize) {
        final String key = path + "_" + reqSize;
        if (cache.get(key) != null) return;
        pool.execute(new Runnable() { @Override public void run() { if (cache.get(key) == null) { Bitmap b = decodeSampled(path, reqSize, reqSize); if (b != null) cache.put(key, b); } } });
    }
    public static void clearCache() {
        cache.evictAll();
    }

    private static Bitmap decodeSampled(String path, int reqWidth, int reqHeight) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);

            int inSampleSize = 1;
            int height = options.outHeight;
            int width = options.outWidth;
            if (height > reqHeight || width > reqWidth) {
                int halfHeight = height / 2;
                int halfWidth = width / 2;
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2;
                }
            }
            options.inSampleSize = inSampleSize;
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, options);
        } catch (Throwable e) {
            return null;
        }
    }
}

