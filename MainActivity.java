package com.ravenrock235.glassgallery;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.view.ScaleGestureDetector;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    static class PhotoItem {
        String name;
        String path;
        String folderName;
        String folderPath;
        boolean trashed;
    }

    static class FolderItem {
        String name;
        String path;
        String coverPath;
        int count;
    }

    private List<PhotoItem> allPhotos = new ArrayList<PhotoItem>();
    private List<FolderItem> folders = new ArrayList<FolderItem>();
    private List<PhotoItem> trashPhotos = new ArrayList<PhotoItem>();

    private int currentTab = 0; // 0: Фотографии, 1: Папки, 2: Корзина
    private FolderItem openedFolder = null;
    private boolean isDark = false;
    private boolean selectionMode = false;
    private Set<String> selectedKeys = new HashSet<String>();

    private int screenWidth;
    private File trashDir;
    private File indexCacheFile;
    private SharedPreferences prefs;

    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // UI элементы
    private FrameLayout rootLayout;
    private LinearLayout mainContainer;
    private FrameLayout topBar;
    private LinearLayout normalTopRow;
    private LinearLayout inlineMenu;
    private VectorIconView themeBtn;
    private LinearLayout selectionBar;
    private TextView selCountView;
    private VectorIconView renameFolderSelBtn;
    private LinearLayout selMiddleArea;

    private LinearLayout tabsHeader;
    private TextView[] tabViews = new TextView[3];
    private TextView folderTitleHeader;
    private GridView gridView;
    private TextView emptyView;

    // Экран выбора папки
    private LinearLayout pickerOverlay;
    private FrameLayout inputOverlay;
    private TextView inputTitleView;
    private EditText inputEditView;
    private TextView inputOkBtn;
    private TextView inputCancelBtn;
    private GridView pickerGrid;
    private LinearLayout settingsOverlay;
    private GridView settingsGrid;
    private Set<String> excludedFolders = new HashSet<String>();
    private List<FolderItem> allKnownFolders = new ArrayList<FolderItem>();

    // Просмотрщик
    private FrameLayout viewerOverlay;
    private TextView viewerFileName;
    private ZoomRotateImageView viewerImage;
    private LinearLayout viewerActionsRow;
    private LinearLayout viewerBottomBar;
    private VectorIconView actRestoreViewer;
    private boolean viewerPureFullscreen = false;
    private List<PhotoItem> viewerList = new ArrayList<PhotoItem>();
    private int viewerIndex = 0;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        StrictMode.VmPolicy.Builder builder = new StrictMode.VmPolicy.Builder();
        StrictMode.setVmPolicy(builder.build());

        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenWidth = dm.widthPixels;

        prefs = getSharedPreferences("glass_gallery_prefs", MODE_PRIVATE);
        isDark = prefs.getBoolean("dark_mode", false);
        excludedFolders = new HashSet<String>(prefs.getStringSet("excluded_folders", new HashSet<String>()));

        trashDir = new File(Environment.getExternalStorageDirectory(), ".GlassGalleryTrash");
        if (!trashDir.exists()) trashDir.mkdirs();

        indexCacheFile = new File(getFilesDir(), "gallery_index.tsv");

        enableImmersiveFullscreen();
        buildUI();
        applyTheme();

        if (checkStoragePermission()) {
            loadFromCacheOnly();
        }
    }

    private boolean checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
									   Manifest.permission.READ_EXTERNAL_STORAGE,
									   Manifest.permission.WRITE_EXTERNAL_STORAGE
								   }, 101);
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == 101 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadFromCacheOnly();
        } else {
            Toast.makeText(this, "Нужен доступ к памяти для показа фото", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enableImmersiveFullscreen();
    }

    private void enableImmersiveFullscreen() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        View decor = getWindow().getDecorView();
        int flags = View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (Build.VERSION.SDK_INT >= 19) {
            flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }
        decor.setSystemUiVisibility(flags);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void buildUI() {
        rootLayout = new FrameLayout(this) {
            float sx, sy;
            boolean tracking = false;

            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                boolean canSwipeTabs = !selectionMode
                        && openedFolder == null
                        && viewerOverlay.getVisibility() != View.VISIBLE
                        && pickerOverlay.getVisibility() != View.VISIBLE
                        && inputOverlay.getVisibility() != View.VISIBLE
                    && (settingsOverlay == null || settingsOverlay.getVisibility() != View.VISIBLE);

                int act = ev.getActionMasked();
                if (act == MotionEvent.ACTION_DOWN) {
                    sx = ev.getX();
                    sy = ev.getY();
                    tracking = canSwipeTabs;
                } else if (act == MotionEvent.ACTION_UP && tracking && canSwipeTabs) {
                    tracking = false;
                    float dx = ev.getX() - sx;
                    float dy = ev.getY() - sy;
                    if (Math.abs(dx) > dp(45) && Math.abs(dx) > Math.abs(dy)) {
                        if (dx < 0) setTab(currentTab + 1);
                        else setTab(currentTab - 1);
                        return true;
                    }
                } else if (act == MotionEvent.ACTION_CANCEL) {
                    tracking = false;
                }
                boolean handled = super.dispatchTouchEvent(ev);
                return act == MotionEvent.ACTION_DOWN ? true : handled;
            }
        };
        rootLayout.setClickable(true);

        mainContainer = new LinearLayout(this);
        mainContainer.setOrientation(LinearLayout.VERTICAL);
        rootLayout.addView(mainContainer, new FrameLayout.LayoutParams(-1, -1));

        // 1. ВЕРХНЯЯ ПАНЕЛЬ (без рамок)
        topBar = new FrameLayout(this);
        topBar.setPadding(dp(12), dp(8), dp(12), dp(4));
        mainContainer.addView(topBar, new LinearLayout.LayoutParams(-1, dp(48)));

        normalTopRow = new LinearLayout(this);
        normalTopRow.setOrientation(LinearLayout.HORIZONTAL);
        normalTopRow.setGravity(Gravity.CENTER_VERTICAL);
        topBar.addView(normalTopRow, new FrameLayout.LayoutParams(-1, -1));

        VectorIconView dotsBtn = makeVectorBtn("dots", 36);
dotsBtn.setOnClickListener(new View.OnClickListener() {
@Override public void onClick(View v) {
boolean show = inlineMenu.getVisibility() != View.VISIBLE;
inlineMenu.setVisibility(show ? View.VISIBLE : View.GONE);
if (themeBtn != null) themeBtn.setVisibility(show ? View.GONE : View.VISIBLE);
}
});
        normalTopRow.addView(dotsBtn);

        inlineMenu = new LinearLayout(this);
        inlineMenu.setOrientation(LinearLayout.HORIZONTAL);
        inlineMenu.setVisibility(View.GONE);
                if (themeBtn != null) themeBtn.setVisibility(View.VISIBLE);

        TextView selectObjBtn = makeIconText("Выбрать", 15);
        selectObjBtn.setPadding(dp(6), dp(6), dp(10), dp(6));
        selectObjBtn.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					inlineMenu.setVisibility(View.GONE);
                if (themeBtn != null) themeBtn.setVisibility(View.VISIBLE);
					enterSelectionMode(null);
				}
			});
        inlineMenu.addView(selectObjBtn);

        TextView refreshBtn = makeIconText("Обновить", 15);
        refreshBtn.setPadding(dp(8), dp(6), dp(10), dp(6));
        refreshBtn.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					inlineMenu.setVisibility(View.GONE);
                if (themeBtn != null) themeBtn.setVisibility(View.VISIBLE);
					Toast.makeText(MainActivity.this, "Обновление...", Toast.LENGTH_SHORT).show();
					scanDeviceAsync();
				}
			});
        inlineMenu.addView(refreshBtn);

                TextView settingsBtn = makeIconText("Настройки", 15);
        settingsBtn.setPadding(dp(8), dp(6), dp(10), dp(6));
        settingsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                inlineMenu.setVisibility(View.GONE);
                if (themeBtn != null) themeBtn.setVisibility(View.VISIBLE);
                openSettings();
            }
        });
        inlineMenu.addView(settingsBtn);

        normalTopRow.addView(inlineMenu);

        View spacer = new View(this);
        normalTopRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        themeBtn = new VectorIconView(this, isDark ? "sun" : "moon");
        themeBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(38), dp(38)));
        themeBtn.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					isDark = !isDark;
					prefs.edit().putBoolean("dark_mode", isDark).apply();
					applyTheme();
				}
			});
        normalTopRow.addView(themeBtn);

        selectionBar = new LinearLayout(this);
        selectionBar.setOrientation(LinearLayout.HORIZONTAL);
        selectionBar.setGravity(Gravity.CENTER_VERTICAL);
        selectionBar.setVisibility(View.GONE);
        topBar.addView(selectionBar, new FrameLayout.LayoutParams(-1, -1));

        VectorIconView closeSelBtn = makeVectorBtn("close", 36);
        closeSelBtn.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					exitSelectionMode();
				}
			});
        selectionBar.addView(closeSelBtn);

        selCountView = makeIconText("0", 16);
        selCountView.setTypeface(null, Typeface.BOLD);
        selCountView.setMinimumWidth(dp(24));
        selectionBar.addView(selCountView);

        selMiddleArea = new LinearLayout(this);
        selMiddleArea.setOrientation(LinearLayout.HORIZONTAL);
        selMiddleArea.setGravity(Gravity.CENTER);
        selectionBar.addView(selMiddleArea, new LinearLayout.LayoutParams(0, -1, 1f));

        renameFolderSelBtn = makeVectorBtn("edit", 36);
        renameFolderSelBtn.setVisibility(View.GONE);
        renameFolderSelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                renameSelectedFolder();
            }
        });
        selectionBar.addView(renameFolderSelBtn);

        VectorIconView trashSelBtn = makeVectorBtn("trash", 36);
        trashSelBtn.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					trashOrDeleteSelected();
				}
			});
        selectionBar.addView(trashSelBtn);

        // 2. ИНДИКАТОР ВКЛАДОК
        tabsHeader = new LinearLayout(this);
        tabsHeader.setOrientation(LinearLayout.HORIZONTAL);
        tabsHeader.setPadding(dp(12), dp(4), dp(12), dp(10));
        mainContainer.addView(tabsHeader, new LinearLayout.LayoutParams(-1, -2));

        String[] names = {"Фотографии", "Папки", "Корзина"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView tv = new TextView(this);
            tv.setText(names[i]);
            tv.setTextSize(16);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dp(4), 0, dp(4));
            tv.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						setTab(idx);
					}
				});
            tabViews[i] = tv;
            tabsHeader.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));
        }

        folderTitleHeader = new TextView(this);
        folderTitleHeader.setTextSize(17);
        folderTitleHeader.setTypeface(null, Typeface.BOLD);
        folderTitleHeader.setPadding(dp(16), dp(4), dp(16), dp(10));
        folderTitleHeader.setVisibility(View.GONE);
        mainContainer.addView(folderTitleHeader, new LinearLayout.LayoutParams(-1, -2));

        // 3. РАБОЧАЯ ОБЛАСТЬ
        FrameLayout contentBox = new FrameLayout(this);
        mainContainer.addView(contentBox, new LinearLayout.LayoutParams(-1, 0, 1f));

        gridView = new GridView(this);
        gridView.setVerticalScrollBarEnabled(false);
        contentBox.addView(gridView, new FrameLayout.LayoutParams(-1, -1));

        emptyView = new TextView(this);
        emptyView.setTextSize(15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        emptyView.setClickable(true);
        contentBox.addView(emptyView, new FrameLayout.LayoutParams(-1, -1));

        buildFolderPicker();
        buildInputOverlay();
        buildViewer();
        buildSettingsOverlay();

        setContentView(rootLayout);
    }
    private void buildInputOverlay() {
        inputOverlay = new FrameLayout(this);
        inputOverlay.setVisibility(View.GONE);
        inputOverlay.setClickable(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(24), dp(24), dp(24));

        inputTitleView = new TextView(this);
        inputTitleView.setTextSize(18);
        inputTitleView.setTypeface(null, Typeface.BOLD);
        inputTitleView.setPadding(0, 0, 0, dp(16));
        box.addView(inputTitleView);

        inputEditView = new EditText(this);
        inputEditView.setTextSize(16);
        inputEditView.setSingleLine(true);
        inputEditView.setBackgroundColor(Color.TRANSPARENT);
        inputEditView.setPadding(0, dp(8), 0, dp(16));
        box.addView(inputEditView);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        btnRow.setPadding(0, dp(12), 0, 0);

        inputCancelBtn = makeIconText("Отмена", 15);
        inputCancelBtn.setPadding(dp(16), dp(10), dp(16), dp(10));
        inputCancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeInputOverlay();
            }
        });

        inputOkBtn = makeIconText("Готово", 15);
        inputOkBtn.setTypeface(null, Typeface.BOLD);
        inputOkBtn.setPadding(dp(16), dp(10), dp(8), dp(10));

        btnRow.addView(inputCancelBtn);
        btnRow.addView(inputOkBtn);
        box.addView(btnRow);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        inputOverlay.addView(box, lp);
        rootLayout.addView(inputOverlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private void showGlassInput(String title, String initialText, String hint, final Runnable onConfirm) {
        inputTitleView.setText(title);
        inputTitleView.setTextColor(fgColor());
        inputEditView.setText(initialText);
        inputEditView.setHint(hint);
        inputEditView.setTextColor(fgColor());
        inputEditView.setHintTextColor(mutedColor());
        inputCancelBtn.setTextColor(mutedColor());
        inputOkBtn.setTextColor(fgColor());
        inputOverlay.setBackgroundColor(isDark ? Color.parseColor("#F5000000") : Color.parseColor("#F5FFFFFF"));
        inputOkBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeInputOverlay();
                onConfirm.run();
            }
        });
        inputOverlay.setVisibility(View.VISIBLE);
        inputOverlay.bringToFront();
        inputEditView.requestFocus();
        inputEditView.setSelection(inputEditView.getText().length());
        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(inputEditView, android.view.inputmethod.InputMethodManager.SHOW_FORCED);
                }
            }
        }, 80);
    }

    private void closeInputOverlay() {
        inputOverlay.setVisibility(View.GONE);
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(inputEditView.getWindowToken(), 0);
    }

    private void buildSettingsOverlay() { settingsOverlay = new LinearLayout(this); settingsOverlay.setOrientation(LinearLayout.VERTICAL); settingsOverlay.setVisibility(View.GONE); settingsOverlay.setClickable(true);
        LinearLayout top = new LinearLayout(this); top.setPadding(dp(16), dp(14), dp(16), dp(12));
        TextView done = makeIconText("Готово", 16); done.setTypeface(null, Typeface.BOLD);
        done.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { settingsOverlay.setVisibility(View.GONE); prefs.edit().putStringSet("excluded_folders", new HashSet<String>(excludedFolders)).apply(); rebuildFoldersFromPhotos(); renderGrid(); } });
        top.addView(done); settingsOverlay.addView(top);
        settingsGrid = new GridView(this); settingsGrid.setNumColumns(2); settingsGrid.setHorizontalSpacing(dp(12)); settingsGrid.setVerticalSpacing(dp(12)); settingsGrid.setPadding(dp(14), dp(4), dp(14), dp(14));
        settingsOverlay.addView(settingsGrid, new LinearLayout.LayoutParams(-1, 0, 1f));
        rootLayout.addView(settingsOverlay, new FrameLayout.LayoutParams(-1, -1)); }
    private void buildFolderPicker() {
        pickerOverlay = new LinearLayout(this);
        pickerOverlay.setOrientation(LinearLayout.VERTICAL);
        pickerOverlay.setVisibility(View.GONE);
        pickerOverlay.setClickable(true);

        TextView title = new TextView(this);
        title.setText("Выберите папку");
        title.setTextSize(17);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(16), dp(14), dp(16), dp(12));
        pickerOverlay.addView(title);

        pickerGrid = new GridView(this);
        pickerGrid.setNumColumns(2);
        pickerGrid.setHorizontalSpacing(dp(12));
        pickerGrid.setVerticalSpacing(dp(12));
        pickerGrid.setPadding(dp(14), dp(4), dp(14), dp(14));
        pickerOverlay.addView(pickerGrid, new LinearLayout.LayoutParams(-1, 0, 1f));

        rootLayout.addView(pickerOverlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private void buildViewer() {
        viewerOverlay = new FrameLayout(this);
        viewerOverlay.setVisibility(View.GONE);
        viewerOverlay.setClickable(true);

        viewerImage = new ZoomRotateImageView(this);
        viewerOverlay.addView(viewerImage, new FrameLayout.LayoutParams(-1, -1));

        viewerFileName = new TextView(this);
        viewerFileName.setTextSize(14);
        viewerFileName.setPadding(dp(16), dp(12), dp(16), dp(12));
        viewerFileName.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("filename", viewerFileName.getText()));
                    Toast.makeText(MainActivity.this, "Имя файла скопировано", Toast.LENGTH_SHORT).show();
                }
            }
        });
        viewerOverlay.addView(viewerFileName, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        viewerBottomBar = new LinearLayout(this);
        viewerBottomBar.setOrientation(LinearLayout.HORIZONTAL);
        viewerBottomBar.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        viewerBottomBar.setPadding(dp(12), dp(8), dp(12), dp(12));
        viewerBottomBar.setClickable(true);

        viewerActionsRow = new LinearLayout(this);
        viewerActionsRow.setOrientation(LinearLayout.HORIZONTAL);
        viewerActionsRow.setGravity(Gravity.CENTER_VERTICAL);
        viewerActionsRow.setVisibility(View.GONE);

        actRestoreViewer = makeVectorBtn("restore", 42);
        actRestoreViewer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restoreCurrentPhotoFromViewer();
            }
        });

        VectorIconView actRename = makeVectorBtn("edit", 42);
        actRename.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                renameCurrentPhoto();
            }
        });

        VectorIconView actTrash = makeVectorBtn("trash", 42);
        actTrash.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                trashCurrentPhoto();
            }
        });

        VectorIconView actFull = makeVectorBtn("eye", 42);
        actFull.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setViewerPureFullscreen(true);
            }
        });

        viewerActionsRow.addView(actRestoreViewer);
        viewerActionsRow.addView(actRename);
        viewerActionsRow.addView(actTrash);
        viewerActionsRow.addView(actFull);

        VectorIconView vDots = makeVectorBtn("dots", 42);
        vDots.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                viewerActionsRow.setVisibility(viewerActionsRow.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });

        viewerBottomBar.addView(viewerActionsRow);
        viewerBottomBar.addView(vDots);

        viewerOverlay.addView(viewerBottomBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        rootLayout.addView(viewerOverlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private void setViewerPureFullscreen(boolean pure) {
        viewerPureFullscreen = pure;
        if (pure) {
            viewerActionsRow.setVisibility(View.GONE);
        }
        viewerFileName.setVisibility(pure ? View.GONE : View.VISIBLE);
        viewerBottomBar.setVisibility(pure ? View.GONE : View.VISIBLE);
    }

    private TextView makeIconText(String text, int sp) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private int fgColor() {
        return isDark ? Color.parseColor("#F5F5F7") : Color.parseColor("#111111");
    }

    private int mutedColor() {
        return isDark ? Color.parseColor("#6E6E73") : Color.parseColor("#8E8E93");
    }

    private int bgColor() {
        return isDark ? Color.BLACK : Color.WHITE;
    }

    private void applyTheme() {
        int bg = bgColor();
        int fg = fgColor();
        rootLayout.setBackgroundColor(bg);
        pickerOverlay.setBackgroundColor(bg);
        viewerOverlay.setBackgroundColor(bg);
        if (settingsOverlay != null) settingsOverlay.setBackgroundColor(bg);

        int idxTheme = normalTopRow.indexOfChild(themeBtn);
        if (idxTheme >= 0) {
            normalTopRow.removeViewAt(idxTheme);
            themeBtn = new VectorIconView(this, isDark ? "sun" : "moon");
            themeBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(38), dp(38)));
            themeBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    isDark = !isDark;
                    prefs.edit().putBoolean("dark_mode", isDark).apply();
                    applyTheme();
                }
            });
            normalTopRow.addView(themeBtn, idxTheme);
        }
        applyTextColorRecursive(rootLayout, fg);
        invalidateVectorsRecursive(rootLayout);
        updateTabStyles();

        if (Build.VERSION.SDK_INT >= 21) {
            Window w = getWindow();
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setStatusBarColor(bg);
            w.setNavigationBarColor(bg);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(isDark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        int barTint = isDark ? Color.parseColor("#CC000000") : Color.parseColor("#D9FFFFFF");
        viewerFileName.setBackgroundColor(barTint);
        viewerBottomBar.setBackgroundColor(barTint);
        renderGrid();
    }

    private void invalidateVectorsRecursive(View v) {
        if (v instanceof VectorIconView) {
            v.invalidate();
        } else if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                invalidateVectorsRecursive(vg.getChildAt(i));
            }
        }
    }

    private void applyTextColorRecursive(View v, int color) {
        if (v instanceof TextView) {
            ((TextView) v).setTextColor(color);
        } else if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                applyTextColorRecursive(vg.getChildAt(i), color);
            }
        }
    }

    private void setTab(int idx) {
        currentTab = ((idx % 3) + 3) % 3;
        openedFolder = null;
        inlineMenu.setVisibility(View.GONE);
                if (themeBtn != null) themeBtn.setVisibility(View.VISIBLE);
        exitSelectionMode();
        updateTabStyles();
        renderGrid();
    }

    private void updateTabStyles() {
        for (int i = 0; i < 3; i++) {
            tabViews[i].setTextColor(i == currentTab ? fgColor() : mutedColor());
            tabViews[i].setTypeface(null, i == currentTab ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private void loadFromCacheOnly() {
        if (!indexCacheFile.exists()) {
            scanDeviceAsync();
            return;
        }
        bgExecutor.execute(new Runnable() {
				@Override
				public void run() {
					final List<PhotoItem> loaded = new ArrayList<PhotoItem>();
					try {
						BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(indexCacheFile), "UTF-8"));
						String line;
						while ((line = br.readLine()) != null) {
							String[] parts = line.split("\t");
							if (parts.length >= 4) {
								PhotoItem p = new PhotoItem();
								java.util.List<String> listParts = java.util.Arrays.asList(parts);
								p.path = listParts.get(0);
								p.name = listParts.get(1);
								p.folderPath = listParts.get(2);
								p.folderName = listParts.get(3);
								p.trashed = false;
								loaded.add(p);
                        if (loaded.size() == 60) { final List<PhotoItem> first = new ArrayList<PhotoItem>(loaded); uiHandler.post(new Runnable() { @Override public void run() { allPhotos.clear(); allPhotos.addAll(first); rebuildFoldersFromPhotos(); renderGrid(); } }); }
							}
						}
						br.close();
					} catch (Exception ignored) {
					}

					final List<PhotoItem> trashLoaded = readTrashFolder();
					uiHandler.post(new Runnable() {
							@Override
							public void run() {
								allPhotos.clear();
								allPhotos.addAll(loaded);
								trashPhotos.clear();
								trashPhotos.addAll(trashLoaded);
								rebuildFoldersFromPhotos();
								renderGrid();
							}
						});
				}
			});
    }

    private void scanDeviceAsync() {
final Set<String> skip = new HashSet<String>(excludedFolders);
bgExecutor.execute(new Runnable() {
				@Override
				public void run() {
					final List<PhotoItem> scanned = new ArrayList<PhotoItem>();
					Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
					String[] projection = {
                        MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.DATA,
                        MediaStore.Images.Media.BUCKET_DISPLAY_NAME
					};

					Cursor cursor = null;
					try {
						cursor = getContentResolver().query(uri, projection, null, null, MediaStore.Images.Media.DATE_ADDED + " DESC");
						if (cursor != null) {
							int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
							int dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
							int bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);

							while (cursor.moveToNext()) {
								String path = cursor.getString(dataCol);
								if (path == null || path.contains("/.GlassGalleryTrash/")) continue;
								File f = new File(path);
String pDir = f.getParent();
if (pDir != null && skip.contains(pDir)) continue;
if (!f.exists()) continue;

								PhotoItem p = new PhotoItem();
								p.path = path;
								p.name = cursor.getString(nameCol);
								if (p.name == null) p.name = f.getName();
								p.folderPath = f.getParent();
								p.folderName = cursor.getString(bucketCol);
								if (p.folderName == null && f.getParentFile() != null) {
									p.folderName = f.getParentFile().getName();
								}
								p.trashed = false;
								scanned.add(p);
							}
						}
					} catch (Exception ignored) {
					} finally {
						if (cursor != null) cursor.close();
					}

					saveIndexCacheSync(scanned);
					final List<PhotoItem> trashLoaded = readTrashFolder();

					uiHandler.post(new Runnable() {
							@Override
							public void run() {
								allPhotos.clear();
								allPhotos.addAll(scanned);
								trashPhotos.clear();
								trashPhotos.addAll(trashLoaded);
								rebuildFoldersFromPhotos();
								renderGrid();
								Toast.makeText(MainActivity.this, "Готово (" + allPhotos.size() + ")", Toast.LENGTH_SHORT).show();
							}
						});
				}
			});
    }
    private void saveIndexCacheSync(List<PhotoItem> list) {
        try {
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(indexCacheFile), "UTF-8"));
            for (PhotoItem p : list) {
                bw.write(p.path + "\t" + p.name + "\t" + p.folderPath + "\t" + p.folderName);
                bw.newLine();
            }
            bw.close();
        } catch (Exception ignored) {
        }
    }

    private void persistCacheAsync() {
        final List<PhotoItem> snapshot = new ArrayList<PhotoItem>(allPhotos);
        bgExecutor.execute(new Runnable() {
				@Override
				public void run() {
					saveIndexCacheSync(snapshot);
				}
			});
    }

    private List<PhotoItem> readTrashFolder() {
        List<PhotoItem> list = new ArrayList<PhotoItem>();
        File[] trashFiles = trashDir.listFiles();
        if (trashFiles != null) {
            for (File tf : trashFiles) {
                if (tf.isFile()) {
                    PhotoItem tp = new PhotoItem();
                    tp.name = tf.getName();
                    tp.path = tf.getAbsolutePath();
                    tp.folderName = "Корзина";
                    tp.folderPath = trashDir.getAbsolutePath();
                    tp.trashed = true;
                    list.add(tp);
                }
            }
        }
        return list;
    }

    private void rebuildFoldersFromPhotos() {
        folders.clear();
        Map<String, FolderItem> map = new LinkedHashMap<String, FolderItem>();
        allKnownFolders.clear();
        for (PhotoItem p : allPhotos) {
            if (excludedFolders.contains(p.folderPath)) continue;
            FolderItem fi = map.get(p.folderPath);
            if (fi == null) {
                fi = new FolderItem();
                fi.name = p.folderName;
                fi.path = p.folderPath;
                fi.coverPath = p.path;
                fi.count = 1;
                map.put(p.folderPath, fi);
            } else {
                fi.count++;
            }
        }
        folders.addAll(map.values());
        Map<String, FolderItem> allMap = new LinkedHashMap<String, FolderItem>();
        for (PhotoItem p : allPhotos) {
            FolderItem fi = allMap.get(p.folderPath);
            if (fi == null) { fi = new FolderItem(); fi.name = p.folderName; fi.path = p.folderPath; fi.coverPath = p.path; allMap.put(p.folderPath, fi); }
        }
        Set<String> meta=new HashSet<String>(prefs.getStringSet("f_meta",new HashSet<String>()));for(String m:meta){String[] p=m.split("\\|");if(p.length>=3&&!allMap.containsKey(p[0])){FolderItem fi=new FolderItem();fi.path=p[0];fi.name=p[1];fi.coverPath=p[2];allMap.put(p[0],fi);}}Set<String> out=new HashSet<String>();for(FolderItem fi:allMap.values())out.add(fi.path+"|"+fi.name+"|"+fi.coverPath);prefs.edit().putStringSet("f_meta",out).apply();
        allKnownFolders.addAll(allMap.values());
    }

    private List<PhotoItem> getCurrentPhotoList() {
        if (openedFolder != null) {
            List<PhotoItem> list = new ArrayList<PhotoItem>();
            for (PhotoItem p : allPhotos) {
                if (p.folderPath.equals(openedFolder.path)) list.add(p);
            }
            return list;
        }
        if (currentTab == 0) { List<PhotoItem> vis = new ArrayList<PhotoItem>(); for (PhotoItem p : allPhotos) if (!excludedFolders.contains(p.folderPath)) vis.add(p); return vis; }
        if (currentTab == 2) return trashPhotos;
        return Collections.emptyList();
    }

    private void renderGrid() {
        if (openedFolder != null) {
            tabsHeader.setVisibility(View.GONE);
            folderTitleHeader.setVisibility(View.VISIBLE);
            folderTitleHeader.setText(openedFolder.name);
        } else {
            tabsHeader.setVisibility(View.VISIBLE);
            folderTitleHeader.setVisibility(View.GONE);
        }

        if (currentTab == 1 && openedFolder == null) {
            if (folders.isEmpty()) {
                gridView.setVisibility(View.GONE);
                emptyView.setVisibility(View.VISIBLE);
                emptyView.setText("Нет папок");
                return;
            }
            emptyView.setVisibility(View.GONE);
        emptyView.setClickable(true);
            gridView.setVisibility(View.VISIBLE);
            gridView.setNumColumns(2);
            gridView.setHorizontalSpacing(dp(12));
            gridView.setVerticalSpacing(dp(12));
            gridView.setPadding(dp(12), dp(4), dp(12), dp(12));
            gridView.setAdapter(new FolderAdapter(folders, false));
            return;
        }

        List<PhotoItem> list = getCurrentPhotoList();
        if (list.isEmpty()) {
            gridView.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
            emptyView.setText(currentTab == 2 ? "Корзина пуста" : "Нет фотографий");
            return;
        }

        emptyView.setVisibility(View.GONE);
        emptyView.setClickable(true);
        gridView.setVisibility(View.VISIBLE);
        gridView.setNumColumns(4);
        gridView.setHorizontalSpacing(dp(3));
        gridView.setVerticalSpacing(dp(3));
        gridView.setPadding(dp(3), dp(3), dp(3), dp(3));
        gridView.setAdapter(new PhotoAdapter(list));
    }

    private void enterSelectionMode(String initialKey) {
        selectionMode = true;
        selectedKeys.clear();
        if (initialKey != null) selectedKeys.add(initialKey);
        normalTopRow.setVisibility(View.GONE);
        selectionBar.setVisibility(View.VISIBLE);
        updateSelectionBar();
        renderGrid();
    }

    private void exitSelectionMode() {
        selectionMode = false;
        selectedKeys.clear();
        selectionBar.setVisibility(View.GONE);
        normalTopRow.setVisibility(View.VISIBLE);
        renderGrid();
    }

    private void updateSelectionBar() {
        final boolean isFolderTab = (currentTab == 1 && openedFolder == null);
        final int total = isFolderTab ? folders.size() : getCurrentPhotoList().size();
        int count = selectedKeys.size();
        selCountView.setText(String.valueOf(count));
        renameFolderSelBtn.setVisibility((isFolderTab && count == 1) ? View.VISIBLE : View.GONE);
        selMiddleArea.removeAllViews();

        if (count == 0) {
            TextView selAll = makeIconText("Выбрать все", 14);
            selAll.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						if (isFolderTab) {
							for (FolderItem f : folders) selectedKeys.add(f.path);
						} else {
							for (PhotoItem p : getCurrentPhotoList()) selectedKeys.add(p.path);
						}
						updateSelectionBar();
						renderGrid();
					}
				});
            selMiddleArea.addView(selAll);
            return;
        }

        boolean allSelected = (count >= total && total > 0);
        TextView checkBox = makeIconText(allSelected ? "✓" : "", 13);
        checkBox.setTypeface(null, Typeface.BOLD);
        GradientDrawable cbBg = new GradientDrawable();
        cbBg.setShape(GradientDrawable.RECTANGLE);
        cbBg.setCornerRadius(dp(3));
        cbBg.setStroke(dp(1), fgColor());
        cbBg.setColor(Color.TRANSPARENT);
        checkBox.setBackground(cbBg);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        cbLp.setMargins(dp(4), 0, dp(10), 0);
        checkBox.setLayoutParams(cbLp);
        checkBox.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if (selectedKeys.size() >= total) {
						selectedKeys.clear();
					} else if (isFolderTab) {
						for (FolderItem f : folders) selectedKeys.add(f.path);
					} else {
						for (PhotoItem p : getCurrentPhotoList()) selectedKeys.add(p.path);
					}
					updateSelectionBar();
					renderGrid();
				}
			});
        selMiddleArea.addView(checkBox);

        if (currentTab == 2) {
            TextView restoreBtn = makeIconText("Восстановить выбранные", 14);
            restoreBtn.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						restoreSelectedFromTrash();
					}
				});
            selMiddleArea.addView(restoreBtn);
        } else {
            TextView pickFolderBtn = makeIconText("Выберите папку", 14);
            pickFolderBtn.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						openFolderPicker();
					}
				});
            selMiddleArea.addView(pickFolderBtn);
        }
    }

    private void openSettings() { settingsGrid.setAdapter(new SettingsFolderAdapter(allKnownFolders)); settingsOverlay.setVisibility(View.VISIBLE); settingsOverlay.bringToFront(); }
    private void openFolderPicker() {
        List<FolderItem> pickerItems = new ArrayList<FolderItem>();
        FolderItem createNew = new FolderItem();
        createNew.name = "Создать папку";
        createNew.path = "__CREATE_NEW__";
        pickerItems.add(createNew);
        pickerItems.addAll(folders);

        pickerGrid.setAdapter(new FolderAdapter(pickerItems, true));
        pickerOverlay.setVisibility(View.VISIBLE);
    }

    private void renameSelectedFolder() {
        if (selectedKeys.size() != 1) return;
        final String oldFolderPath = selectedKeys.iterator().next();
        final File oldDir = new File(oldFolderPath);
        showGlassInput("Переименовать папку", oldDir.getName(), "Название папки", new Runnable() {
            @Override
            public void run() {
                String newName = inputEditView.getText().toString().trim();
                if (newName.isEmpty() || newName.equals(oldDir.getName())) return;
                File newDir = new File(oldDir.getParentFile(), newName);
                if (newDir.exists()) {
                    Toast.makeText(MainActivity.this, "Такая папка уже существует", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (oldDir.renameTo(newDir)) {
                    List<String> scanPaths = new ArrayList<String>();
                    for (PhotoItem p : allPhotos) {
                        if (p.folderPath.equals(oldFolderPath)) {
                            scanPaths.add(p.path);
                            p.path = new File(newDir, p.name).getAbsolutePath();
                            p.folderPath = newDir.getAbsolutePath();
                            p.folderName = newName;
                            scanPaths.add(p.path);
                        }
                    }
                    if (!scanPaths.isEmpty()) {
                        MediaScannerConnection.scanFile(MainActivity.this, scanPaths.toArray(new String[0]), null, null);
                    }
                    rebuildFoldersFromPhotos();
                    persistCacheAsync();
                    Toast.makeText(MainActivity.this, "Папка переименована", Toast.LENGTH_SHORT).show();
                    exitSelectionMode();
                } else {
                    Toast.makeText(MainActivity.this, "Не удалось переименовать папку", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void promptCreateFolder() {
        showGlassInput("Новая папка", "", "Название папки", new Runnable() {
            @Override
            public void run() {
                String name = inputEditView.getText().toString().trim();
                if (!name.isEmpty()) {
                    File targetDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), name);
                    if (!targetDir.exists()) targetDir.mkdirs();
                    moveSelectedToDirectory(targetDir);
                }
            }
        });
    }

    private void moveSelectedToDirectory(File targetDir) {
        if (!targetDir.exists()) targetDir.mkdirs();
        List<PhotoItem> itemsToMove = new ArrayList<PhotoItem>();

        if (currentTab == 1 && openedFolder == null) {
            for (PhotoItem p : allPhotos) {
                if (selectedKeys.contains(p.folderPath)) itemsToMove.add(p);
            }
        } else {
            for (PhotoItem p : allPhotos) {
                if (selectedKeys.contains(p.path)) itemsToMove.add(p);
            }
        }

        List<String> scanPaths = new ArrayList<String>();
        for (PhotoItem p : itemsToMove) {
            File src = new File(p.path);
            File dst = new File(targetDir, src.getName());
            if (src.getAbsolutePath().equals(dst.getAbsolutePath())) continue;
            if (moveFile(src, dst)) {
                scanPaths.add(src.getAbsolutePath());
                scanPaths.add(dst.getAbsolutePath());
                p.path = dst.getAbsolutePath();
                p.folderPath = targetDir.getAbsolutePath();
                p.folderName = targetDir.getName();
            }
        }

        if (!scanPaths.isEmpty()) {
            MediaScannerConnection.scanFile(this, scanPaths.toArray(new String[0]), null, null);
        }
        rebuildFoldersFromPhotos();
        persistCacheAsync();
        pickerOverlay.setVisibility(View.GONE);
        exitSelectionMode();
        Toast.makeText(this, "Перемещено в " + targetDir.getName(), Toast.LENGTH_SHORT).show();
    }

    private void trashOrDeleteSelected() {
        if (selectedKeys.isEmpty()) return;
        if (currentTab == 2) {
            List<PhotoItem> remaining = new ArrayList<PhotoItem>();
            for (PhotoItem p : trashPhotos) {
                if (selectedKeys.contains(p.path)) {
                    new File(p.path).delete();
                    prefs.edit().remove("orig_" + p.name).apply();
                } else {
                    remaining.add(p);
                }
            }
            trashPhotos = remaining;
            Toast.makeText(this, "Удалено навсегда", Toast.LENGTH_SHORT).show();
            exitSelectionMode();
            return;
        }

        List<PhotoItem> toTrash = new ArrayList<PhotoItem>();
        if (currentTab == 1 && openedFolder == null) {
            for (PhotoItem p : allPhotos) {
                if (selectedKeys.contains(p.folderPath)) toTrash.add(p);
            }
        } else {
            for (PhotoItem p : allPhotos) {
                if (selectedKeys.contains(p.path)) toTrash.add(p);
            }
        }

        List<String> scanPaths = new ArrayList<String>();
        for (PhotoItem p : toTrash) {
            File src = new File(p.path);
            File dst = new File(trashDir, src.getName());
            prefs.edit().putString("orig_" + src.getName(), src.getAbsolutePath()).apply();
            if (moveFile(src, dst)) {
                scanPaths.add(src.getAbsolutePath());
                allPhotos.remove(p);
                p.path = dst.getAbsolutePath();
                p.folderPath = trashDir.getAbsolutePath();
                p.folderName = "Корзина";
                p.trashed = true;
                trashPhotos.add(p);
            }
        }

        if (!scanPaths.isEmpty()) {
            MediaScannerConnection.scanFile(this, scanPaths.toArray(new String[0]), null, null);
        }
        rebuildFoldersFromPhotos();
        persistCacheAsync();
        Toast.makeText(this, "Перемещено в корзину", Toast.LENGTH_SHORT).show();
        exitSelectionMode();
    }

    private void restoreSelectedFromTrash() {
        List<String> scanPaths = new ArrayList<String>();
        List<PhotoItem> stillTrashed = new ArrayList<PhotoItem>();

        for (PhotoItem p : trashPhotos) {
            if (!selectedKeys.contains(p.path)) {
                stillTrashed.add(p);
                continue;
            }
            File src = new File(p.path);
            String origPath = prefs.getString("orig_" + src.getName(), null);
            File dst = (origPath != null)
				? new File(origPath)
				: new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), src.getName());
            if (dst.getParentFile() != null && !dst.getParentFile().exists()) dst.getParentFile().mkdirs();

            if (moveFile(src, dst)) {
                prefs.edit().remove("orig_" + src.getName()).apply();
                scanPaths.add(dst.getAbsolutePath());
                p.path = dst.getAbsolutePath();
                p.folderPath = dst.getParent();
                p.folderName = dst.getParentFile() != null ? dst.getParentFile().getName() : "Pictures";
                p.trashed = false;
                allPhotos.add(0, p);
            } else {
                stillTrashed.add(p);
            }
        }

        trashPhotos = stillTrashed;
        if (!scanPaths.isEmpty()) {
            MediaScannerConnection.scanFile(this, scanPaths.toArray(new String[0]), null, null);
        }
        rebuildFoldersFromPhotos();
        persistCacheAsync();
        Toast.makeText(this, "Восстановлено", Toast.LENGTH_SHORT).show();
        exitSelectionMode();
    }

    private void shareSelected() {
        if (selectedKeys.isEmpty()) return;
        ArrayList<Uri> uris = new ArrayList<Uri>();
        if (currentTab == 1 && openedFolder == null) {
            for (PhotoItem p : allPhotos) {
                if (selectedKeys.contains(p.folderPath)) uris.add(Uri.fromFile(new File(p.path)));
            }
        } else {
            for (String path : selectedKeys) uris.add(Uri.fromFile(new File(path)));
        }
        if (uris.isEmpty()) return;
        Intent intent = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
        intent.setType("image/*");
        if (uris.size() == 1) intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        startActivity(Intent.createChooser(intent, "Поделиться"));
    }

    private boolean moveFile(File src, File dst) {
        if (src.renameTo(dst)) return true;
        try {
            InputStream in = new FileInputStream(src);
            OutputStream out = new FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            in.close();
            out.close();
            return src.delete();
        } catch (Exception e) {
            return false;
        }
    }

    private void openViewer(PhotoItem target) {
        viewerList.clear();
        if (currentTab == 2) {
            viewerList.addAll(trashPhotos);
        } else {
            viewerList.addAll(allPhotos);
            Collections.sort(viewerList, new Comparator<PhotoItem>() {
					@Override
					public int compare(PhotoItem a, PhotoItem b) {
						return a.folderName.compareToIgnoreCase(b.folderName);
					}
				});
        }
        viewerIndex = 0;
        for (int i = 0; i < viewerList.size(); i++) {
            if (viewerList.get(i).path.equals(target.path)) {
                viewerIndex = i;
                break;
            }
        }
        viewerOverlay.setVisibility(View.VISIBLE);
        updateViewer();
    }

    private void closeViewer() {
        setViewerPureFullscreen(false);
        viewerOverlay.setVisibility(View.GONE);
        viewerActionsRow.setVisibility(View.GONE);
        renderGrid();
    }

    private void updateViewer() {
        if (viewerList.isEmpty()) {
            closeViewer();
            return;
        }
        int n = viewerList.size();
        viewerIndex = ((viewerIndex % n) + n) % n;
        PhotoItem item = viewerList.get(viewerIndex);
        viewerFileName.setText(item.name);
        actRestoreViewer.setVisibility(item.trashed ? View.VISIBLE : View.GONE);
        ThumbLoader.load(item.path, viewerImage, screenWidth);
    }
    private void useCurrentPhotoAs() {
        PhotoItem item = viewerList.get(viewerIndex);
        Intent intent = new Intent(Intent.ACTION_ATTACH_DATA);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setDataAndType(Uri.fromFile(new File(item.path)), "image/*");
        intent.putExtra("mimeType", "image/*");
        startActivity(Intent.createChooser(intent, "Использовать как"));
    }

    private void shareSinglePhoto(PhotoItem item) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(new File(item.path)));
        startActivity(Intent.createChooser(intent, "Поделиться"));
    }

    private void renameCurrentPhoto() {
        final PhotoItem item = viewerList.get(viewerIndex);
        showGlassInput("Переименовать", item.name, "Имя файла", new Runnable() {
            @Override
            public void run() {
                String newName = inputEditView.getText().toString().trim();
                if (newName.isEmpty() || newName.equals(item.name)) return;
                File src = new File(item.path);
                File dst = new File(src.getParentFile(), newName);
                if (src.renameTo(dst)) {
                    MediaScannerConnection.scanFile(MainActivity.this,
                            new String[]{src.getAbsolutePath(), dst.getAbsolutePath()}, null, null);
                    item.name = newName;
                    item.path = dst.getAbsolutePath();
                    rebuildFoldersFromPhotos();
                    persistCacheAsync();
                    updateViewer();
                }
            }
        });
    }

    private void restoreCurrentPhotoFromViewer() {
        PhotoItem item = viewerList.get(viewerIndex);
        File src = new File(item.path);
        String origPath = prefs.getString("orig_" + src.getName(), null);
        File dst = (origPath != null)
                ? new File(origPath)
                : new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), src.getName());
        if (dst.getParentFile() != null && !dst.getParentFile().exists()) dst.getParentFile().mkdirs();

        if (moveFile(src, dst)) {
            prefs.edit().remove("orig_" + src.getName()).apply();
            MediaScannerConnection.scanFile(this, new String[]{dst.getAbsolutePath()}, null, null);
            trashPhotos.remove(item);
            item.path = dst.getAbsolutePath();
            item.folderPath = dst.getParent();
            item.folderName = dst.getParentFile() != null ? dst.getParentFile().getName() : "Pictures";
            item.trashed = false;
            allPhotos.add(0, item);
            rebuildFoldersFromPhotos();
            persistCacheAsync();
            Toast.makeText(this, "Восстановлено", Toast.LENGTH_SHORT).show();
            viewerList.remove(viewerIndex);
            updateViewer();
        }
    }

    private void trashCurrentPhoto() {
        PhotoItem item = viewerList.get(viewerIndex);
        File src = new File(item.path);
        if (item.trashed) {
            src.delete();
            prefs.edit().remove("orig_" + src.getName()).apply();
            trashPhotos.remove(item);
            Toast.makeText(this, "Удалено навсегда", Toast.LENGTH_SHORT).show();
        } else {
            File dst = new File(trashDir, src.getName());
            prefs.edit().putString("orig_" + src.getName(), src.getAbsolutePath()).apply();
            if (moveFile(src, dst)) {
                MediaScannerConnection.scanFile(this, new String[]{src.getAbsolutePath()}, null, null);
                allPhotos.remove(item);
                item.path = dst.getAbsolutePath();
                item.folderPath = trashDir.getAbsolutePath();
                item.folderName = "Корзина";
                item.trashed = true;
                trashPhotos.add(item);
                rebuildFoldersFromPhotos();
                persistCacheAsync();
                Toast.makeText(this, "В корзину", Toast.LENGTH_SHORT).show();
            }
        }
        viewerList.remove(viewerIndex);
        updateViewer();
    }

    @Override
    public void onBackPressed() {
        if (inputOverlay.getVisibility() == View.VISIBLE) {
            closeInputOverlay();
            return;
        }
        if (viewerOverlay.getVisibility() == View.VISIBLE && viewerPureFullscreen) {
            setViewerPureFullscreen(false);
            return;
        }
        if (inlineMenu.getVisibility() == View.VISIBLE) {
            inlineMenu.setVisibility(View.GONE);
                if (themeBtn != null) themeBtn.setVisibility(View.VISIBLE);
            return;
        }
        if (viewerActionsRow.getVisibility() == View.VISIBLE) {
            viewerActionsRow.setVisibility(View.GONE);
            return;
        }
        if (settingsOverlay != null && settingsOverlay.getVisibility() == View.VISIBLE) { settingsOverlay.setVisibility(View.GONE); return; }
        if (pickerOverlay.getVisibility() == View.VISIBLE) {
            pickerOverlay.setVisibility(View.GONE);
            return;
        }
        if (viewerOverlay.getVisibility() == View.VISIBLE) {
            closeViewer();
            return;
        }
        if (selectionMode) {
            exitSelectionMode();
            return;
        }
        if (openedFolder != null) {
            openedFolder = null;
            renderGrid();
            return;
        }
        super.onBackPressed();
    }
    private class PhotoAdapter extends BaseAdapter {
        private final List<PhotoItem> items;
        private final int tileSize = screenWidth / 4;

        PhotoAdapter(List<PhotoItem> items) {
            this.items = items;
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final PhotoItem item = items.get(position);
            FrameLayout cell;
            ImageView img;
            TextView badge;

            if (convertView == null) {
                cell = new FrameLayout(MainActivity.this);
                cell.setLayoutParams(new GridView.LayoutParams(tileSize, tileSize));
                img = new ImageView(MainActivity.this);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cell.addView(img, new FrameLayout.LayoutParams(-1, -1));

                badge = new TextView(MainActivity.this);
                badge.setText("✓");
                badge.setTextColor(Color.WHITE);
                badge.setTextSize(13);
                badge.setGravity(Gravity.CENTER);
                GradientDrawable gd = new GradientDrawable();
                gd.setShape(GradientDrawable.OVAL);
                gd.setColor(Color.parseColor("#007AFF"));
                badge.setBackground(gd);
                FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP | Gravity.END);
                bp.setMargins(0, dp(5), dp(5), 0);
                cell.addView(badge, bp);
            } else {
                cell = (FrameLayout) convertView;
                img = (ImageView) cell.getChildAt(0);
                badge = (TextView) cell.getChildAt(1);
            }

            boolean isSel = selectedKeys.contains(item.path);
            badge.setVisibility(isSel ? View.VISIBLE : View.GONE);
            img.setScaleX(isSel ? 0.86f : 1f);
            img.setScaleY(isSel ? 0.86f : 1f);

            ThumbLoader.load(item.path, img, tileSize);
            for (int k = 1; k <= 16; k += 4) if (position + k < items.size()) ThumbLoader.preload(items.get(position + k).path, tileSize);

            cell.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						if (selectionMode) {
							if (selectedKeys.contains(item.path)) selectedKeys.remove(item.path);
							else selectedKeys.add(item.path);
							updateSelectionBar();
							notifyDataSetChanged();
						} else {
							openViewer(item);
						}
					}
				});

            cell.setOnLongClickListener(new View.OnLongClickListener() {
					@Override
					public boolean onLongClick(View v) {
						if (!selectionMode) {
							enterSelectionMode(item.path);
							return true;
						}
						return false;
					}
				});

            return cell;
        }
    }

    private class SettingsFolderAdapter extends BaseAdapter {
        private final List<FolderItem> items;
        private final int coverSize = (screenWidth - dp(36)) / 2;
        SettingsFolderAdapter(List<FolderItem> items) { this.items = items; }
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int pos) { return items.get(pos); }
        @Override public long getItemId(int pos) { return pos; }
        @Override public View getView(int pos, View cv, ViewGroup par) { final FolderItem it = items.get(pos); final boolean ex = excludedFolders.contains(it.path); LinearLayout card = new LinearLayout(MainActivity.this); card.setOrientation(LinearLayout.VERTICAL); ImageView img = new ImageView(MainActivity.this); img.setScaleType(ImageView.ScaleType.CENTER_CROP); img.setAlpha(ex ? 0.35f : 1f); card.addView(img, new LinearLayout.LayoutParams(coverSize, coverSize)); ThumbLoader.load(it.coverPath, img, coverSize); TextView lbl = new TextView(MainActivity.this); lbl.setText((ex ? "✕ " : "✓ ") + it.name); lbl.setTextColor(ex ? mutedColor() : fgColor()); lbl.setPadding(0, dp(6), 0, dp(4)); card.addView(lbl); card.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (excludedFolders.contains(it.path)) excludedFolders.remove(it.path); else excludedFolders.add(it.path); notifyDataSetChanged(); } }); return card; }
    }
    private class FolderAdapter extends BaseAdapter {
        private final List<FolderItem> items;
        private final boolean isPickerMode;
        private final int coverSize = (screenWidth - dp(36)) / 2;

        FolderAdapter(List<FolderItem> items, boolean isPickerMode) {
            this.items = items;
            this.isPickerMode = isPickerMode;
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final FolderItem item = items.get(position);
            LinearLayout card = new LinearLayout(MainActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);

            FrameLayout thumbBox = new FrameLayout(MainActivity.this);
            thumbBox.setLayoutParams(new LinearLayout.LayoutParams(coverSize, coverSize));

            if ("__CREATE_NEW__".equals(item.path)) {
                VectorIconView plus = new VectorIconView(MainActivity.this, "plus");
thumbBox.addView(plus, new FrameLayout.LayoutParams(-1, -1));
            } else {
                ImageView img = new ImageView(MainActivity.this);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumbBox.addView(img, new FrameLayout.LayoutParams(-1, -1));
                ThumbLoader.load(item.coverPath, img, coverSize);

                if (!isPickerMode && selectedKeys.contains(item.path)) {
                    img.setScaleX(0.88f);
                    img.setScaleY(0.88f);
                    TextView badge = new TextView(MainActivity.this);
                    badge.setText("✓");
                    badge.setTextColor(Color.WHITE);
                    badge.setGravity(Gravity.CENTER);
                    GradientDrawable gd = new GradientDrawable();
                    gd.setShape(GradientDrawable.OVAL);
                    gd.setColor(Color.parseColor("#007AFF"));
                    badge.setBackground(gd);
                    FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP | Gravity.END);
                    bp.setMargins(0, dp(6), dp(6), 0);
                    thumbBox.addView(badge, bp);
                }
            }

            TextView label = new TextView(MainActivity.this);
            label.setText(item.name);
            label.setTextSize(14);
            label.setTextColor(fgColor());
            label.setPadding(0, dp(6), 0, dp(4));

            card.addView(thumbBox);
            card.addView(label);

            card.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						if (isPickerMode) {
							if ("__CREATE_NEW__".equals(item.path)) promptCreateFolder();
							else moveSelectedToDirectory(new File(item.path));
							return;
						}
						if (selectionMode) {
							if (selectedKeys.contains(item.path)) selectedKeys.remove(item.path);
							else selectedKeys.add(item.path);
							updateSelectionBar();
							notifyDataSetChanged();
						} else {
							openedFolder = item;
							renderGrid();
						}
					}
				});

            card.setOnLongClickListener(new View.OnLongClickListener() {
					@Override
					public boolean onLongClick(View v) {
						if (!isPickerMode && !selectionMode) {
							enterSelectionMode(item.path);
							return true;
						}
						return false;
					}
				});

            return card;
        }
    }

    private class ZoomRotateImageView extends ImageView {
        private final Matrix matrix = new Matrix();
        private ScaleGestureDetector scaleDetector;

        private float baseScale = 1f;
        private float currentZoom = 1f;
        private float rotationDeg = 0f;

        private float lastX, lastY;
        private float downX, downY;
        private long lastTapTime = 0;
        private boolean isPanningOrZooming = false;

        private boolean isRotating = false;
        private float startAngle = 0f;
        private float startRotation = 0f;

        public ZoomRotateImageView(Context context) {
            super(context);
            setScaleType(ScaleType.MATRIX);
            scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    float factor = detector.getScaleFactor();
                    float targetZoom = currentZoom * factor;
                    if (targetZoom < 1f) targetZoom = 1f;
                    if (targetZoom > 6f) targetZoom = 6f;
                    float applied = targetZoom / currentZoom;
                    currentZoom = targetZoom;
                    matrix.postScale(applied, applied, detector.getFocusX(), detector.getFocusY());
                    fixBounds();
                    setImageMatrix(matrix);
                    isPanningOrZooming = true;
                    return true;
                }
            });
        }

        @Override
        public void setImageBitmap(Bitmap bm) {
            super.setImageBitmap(bm);
            resetTransform();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            resetTransform();
        }

        public void resetTransform() {
            currentZoom = 1f;
            rotationDeg = 0f;
            fitToScreen();
        }

        private void fitToScreen() {
            if (getDrawable() == null || getWidth() == 0 || getHeight() == 0) return;
            float vw = getWidth();
            float vh = getHeight();
            float dw = getDrawable().getIntrinsicWidth();
            float dh = getDrawable().getIntrinsicHeight();
            if (dw <= 0 || dh <= 0) return;

            boolean swapped = (((int) Math.abs(rotationDeg)) % 180) != 0;
            float effW = swapped ? dh : dw;
            float effH = swapped ? dw : dh;

            baseScale = Math.min(vw / effW, vh / effH);
            float scale = baseScale * currentZoom;

            matrix.reset();
            matrix.postTranslate(-dw / 2f, -dh / 2f);
            matrix.postRotate(rotationDeg);
            matrix.postScale(scale, scale);
            matrix.postTranslate(vw / 2f, vh / 2f);
            setImageMatrix(matrix);
        }

        private void fixBounds() {
            if (getDrawable() == null) return;
            RectF rect = new RectF(0, 0, getDrawable().getIntrinsicWidth(), getDrawable().getIntrinsicHeight());
            matrix.mapRect(rect);

            float vw = getWidth();
            float vh = getHeight();
            float dx = 0f, dy = 0f;

            if (rect.width() <= vw) {
                dx = vw / 2f - rect.centerX();
            } else if (rect.left > 0) {
                dx = -rect.left;
            } else if (rect.right < vw) {
                dx = vw - rect.right;
            }

            if (rect.height() <= vh) {
                dy = vh / 2f - rect.centerY();
            } else if (rect.top > 0) {
                dy = -rect.top;
            } else if (rect.bottom < vh) {
                dy = vh - rect.bottom;
            }

            matrix.postTranslate(dx, dy);
        }

        private float getAngle(MotionEvent e) {
            double dx = e.getX(1) - e.getX(0);
            double dy = e.getY(1) - e.getY(0);
            return (float) Math.toDegrees(Math.atan2(dy, dx));
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            scaleDetector.onTouchEvent(e);

            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downX = lastX = e.getX();
                downY = lastY = e.getY();
                isPanningOrZooming = false;
                isRotating = false;
                return true;
            } else if (action == MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount() == 2) {
                isRotating = true;
                isPanningOrZooming = true;
                startAngle = getAngle(e);
                startRotation = rotationDeg;
                return true;
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (e.getPointerCount() >= 2 && isRotating) {
                    float curAngle = getAngle(e);
                    float delta = curAngle - startAngle;
                    float midX = (e.getX(0) + e.getX(1)) / 2f;
                    float midY = (e.getY(0) + e.getY(1)) / 2f;
                    float targetRot = startRotation + delta;
                    matrix.postRotate(targetRot - rotationDeg, midX, midY);
                    rotationDeg = targetRot;
                    setImageMatrix(matrix);
                    return true;
                } else if (e.getPointerCount() == 1 && !scaleDetector.isInProgress()) {
                    float dx = e.getX() - lastX;
                    float dy = e.getY() - lastY;
                    lastX = e.getX();
                    lastY = e.getY();

                    if (currentZoom > 1.02f) {
                        matrix.postTranslate(dx, dy);
                        fixBounds();
                        setImageMatrix(matrix);
                        isPanningOrZooming = true;
                        return true;
                    }
                }
            } else if (action == MotionEvent.ACTION_POINTER_UP) {
                if (isRotating) {
                    isRotating = false;
                    rotationDeg = Math.round(rotationDeg / 90f) * 90f;
                    fitToScreen();
                }
            } else if (action == MotionEvent.ACTION_UP) {
                if (isRotating) {
                    isRotating = false;
                    rotationDeg = Math.round(rotationDeg / 90f) * 90f;
                    fitToScreen();
                    return true;
                }

                float totalDx = e.getX() - downX;
                float totalDy = e.getY() - downY;

                if (Math.abs(totalDx) < dp(12) && Math.abs(totalDy) < dp(12) && !isPanningOrZooming) {
                    long now = System.currentTimeMillis();
                    if (now - lastTapTime < 280) {
                        if (currentZoom > 1.1f) {
                            currentZoom = 1f;
                            fitToScreen();
                        } else {
                            currentZoom = 2.8f;
                            matrix.postScale(2.8f, 2.8f, e.getX(), e.getY());
                            fixBounds();
                            setImageMatrix(matrix);
                        }
                        lastTapTime = 0;
                    } else {
                        lastTapTime = now;
                        if (viewerPureFullscreen) {
                            setViewerPureFullscreen(false);
                        }
                    }
                    return true;
                }

                if (currentZoom <= 1.02f && !isPanningOrZooming) {
                    if (totalDy > dp(90) && Math.abs(totalDy) > Math.abs(totalDx) * 1.3f) {
                        closeViewer();
                        return true;
                    }
                    if (Math.abs(totalDx) > dp(45) && Math.abs(totalDx) > Math.abs(totalDy)) {
                        if (totalDx < 0) viewerIndex++;
                        else viewerIndex--;
                        updateViewer();
                        return true;
                    }
                }
            }
            return true;
        }
    }

    private class VectorIconView extends View {
        private final String type;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        public VectorIconView(Context context, String type) { super(context); this.type = type; }
        @Override protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight(), s = Math.min(w, h) * 0.52f, cx = w / 2f, cy = h / 2f;
            float l = cx - s / 2f, t = cy - s / 2f, r = cx + s / 2f, b = cy + s / 2f;
            paint.reset(); paint.setAntiAlias(true); paint.setColor(fgColor());
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND); paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            paint.setStrokeWidth(dp(2)); path.reset();
            if ("dots".equals(type)) {
                paint.setStyle(android.graphics.Paint.Style.FILL);
                canvas.drawCircle(cx, cy - s * 0.32f, dp(2), paint); canvas.drawCircle(cx, cy, dp(2), paint); canvas.drawCircle(cx, cy + s * 0.32f, dp(2), paint);
            } else if ("moon".equals(type)) {
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                android.graphics.Path p1 = new android.graphics.Path(), p2 = new android.graphics.Path();
                p1.addCircle(cx, cy, s * 0.46f, android.graphics.Path.Direction.CW);
                p2.addCircle(cx + s * 0.25f, cy - s * 0.2f, s * 0.38f, android.graphics.Path.Direction.CW);
                p1.op(p2, android.graphics.Path.Op.DIFFERENCE);
                canvas.drawPath(p1, paint);
            } else if ("sun".equals(type)) {
                paint.setStyle(android.graphics.Paint.Style.STROKE); float rad = s * 0.24f; canvas.drawCircle(cx, cy, rad, paint);
                for (int i = 0; i < 8; i++) {
                    double a = Math.toRadians(i * 45);
                    canvas.drawLine(cx + (float)Math.cos(a)*(rad+dp(3)), cy + (float)Math.sin(a)*(rad+dp(3)), cx + (float)Math.cos(a)*(s*0.48f), cy + (float)Math.sin(a)*(s*0.48f), paint);
                }
            } else if ("close".equals(type)) {
                paint.setStyle(android.graphics.Paint.Style.STROKE); float p = s * 0.12f;
                canvas.drawLine(l+p, t+p, r-p, b-p, paint); canvas.drawLine(r-p, t+p, l+p, b-p, paint);
            } else if ("edit".equals(type)) {
                paint.setStyle(android.graphics.Paint.Style.STROKE); path.moveTo(l+s*0.15f, b-s*0.15f);
                path.lineTo(l+s*0.22f, b-s*0.42f); path.lineTo(r-s*0.35f, t+s*0.15f); path.lineTo(r-s*0.15f, t+s*0.35f);
                path.lineTo(l+s*0.42f, b-s*0.22f); path.close(); canvas.drawPath(path, paint);
            } else if ("trash".equals(type)) {
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                canvas.drawLine(l+s*0.12f, t+s*0.24f, r-s*0.12f, t+s*0.24f, paint); canvas.drawLine(cx-s*0.15f, t+s*0.12f, cx+s*0.15f, t+s*0.12f, paint);
                path.moveTo(l+s*0.22f, t+s*0.24f); path.lineTo(l+s*0.28f, b-s*0.12f); path.lineTo(r-s*0.28f, b-s*0.12f); path.lineTo(r-s*0.22f, t+s*0.24f); canvas.drawPath(path, paint);
            } else if ("restore".equals(type)) {
                paint.setStyle(android.graphics.Paint.Style.STROKE); path.addArc(l+s*0.1f, t+s*0.1f, r-s*0.1f, b-s*0.1f, 30, 280); canvas.drawPath(path, paint);
                canvas.drawLine(r-s*0.15f, t+s*0.15f, r-s*0.15f, t+s*0.38f, paint); canvas.drawLine(r-s*0.15f, t+s*0.38f, r-s*0.38f, t+s*0.38f, paint);
            } else if ("eye".equals(type)) {
                paint.setStyle(android.graphics.Paint.Style.STROKE); path.moveTo(l, cy); path.quadTo(cx, t+s*0.12f, r, cy);
                path.quadTo(cx, b-s*0.12f, l, cy); canvas.drawPath(path, paint); canvas.drawCircle(cx, cy, s*0.15f, paint);
            } else if ("plus".equals(type)) {
                paint.setStyle(android.graphics.Paint.Style.STROKE); canvas.drawLine(cx, t+s*0.1f, cx, b-s*0.1f, paint); canvas.drawLine(l+s*0.1f, cy, r-s*0.1f, cy, paint);
            }
        }
    }
    private VectorIconView makeVectorBtn(String type, int sizeDp) {
        VectorIconView v = new VectorIconView(this, type);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }
}
