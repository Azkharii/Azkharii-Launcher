package com.cinematic.tvlauncher.navigation;

import android.view.View;
import com.cinematic.tvlauncher.carousel.AppCarouselView;

/** Keeps D-pad focus on the home controls and recent-app carousel. */
public class FocusNavigationController {
    private final AppCarouselView carouselView;
    private final View btnInputs;
    private final View btnWifi;
    private final View btnSettings;
    private final View btnPhotos;
    private final View btnSearch;
    private int lastSelectedAppIndex = 0;

    public FocusNavigationController(AppCarouselView carouselView, View btnInputs, View btnWifi,
            View btnSettings, View btnPhotos, View btnSearch) {
        this.carouselView = carouselView;
        this.btnInputs = btnInputs;
        this.btnWifi = btnWifi;
        this.btnSettings = btnSettings;
        this.btnPhotos = btnPhotos;
        this.btnSearch = btnSearch;
        setupNavigationRules();
    }

    private void setupNavigationRules() {
        carouselView.setNextFocusUpId(btnInputs.getId());
        carouselView.setNextFocusDownId(carouselView.getId());
        btnInputs.setNextFocusLeftId(btnInputs.getId());
        btnInputs.setNextFocusRightId(btnWifi.getId());
        btnInputs.setNextFocusDownId(carouselView.getId());
        btnWifi.setNextFocusLeftId(btnInputs.getId());
        btnWifi.setNextFocusRightId(btnSettings.getId());
        btnWifi.setNextFocusDownId(carouselView.getId());
        btnSettings.setNextFocusLeftId(btnWifi.getId());
        btnSettings.setNextFocusRightId(btnPhotos.getId());
        btnSettings.setNextFocusDownId(carouselView.getId());
        btnPhotos.setNextFocusLeftId(btnSettings.getId());
        btnPhotos.setNextFocusRightId(btnSearch.getId());
        btnPhotos.setNextFocusDownId(carouselView.getId());
        btnSearch.setNextFocusLeftId(btnPhotos.getId());
        btnSearch.setNextFocusRightId(btnSearch.getId());
        btnSearch.setNextFocusDownId(carouselView.getId());
        carouselView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) carouselView.setSelectedIndex(lastSelectedAppIndex);
        });
    }

    public void recordSelectedApp(int index) { lastSelectedAppIndex = Math.max(0, index); }
    public int getLastSelectedAppIndex() { return lastSelectedAppIndex; }

    public boolean handleBackPressed(View currentFocus) {
        if (currentFocus != null && (currentFocus == btnInputs || currentFocus == btnWifi
                || currentFocus == btnSettings || currentFocus == btnPhotos || currentFocus == btnSearch)) {
            carouselView.requestFocus();
            carouselView.setSelectedIndex(lastSelectedAppIndex);
            return true;
        }
        return true;
    }
}
