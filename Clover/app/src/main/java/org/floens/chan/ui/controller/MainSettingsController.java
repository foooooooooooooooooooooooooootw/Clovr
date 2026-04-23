/*
 * Clover - 4chan browser https://github.com/Floens/Clover/
 * Copyright (C) 2014  Floens
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.floens.chan.ui.controller;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import org.floens.chan.BuildConfig;
import org.floens.chan.R;
import org.floens.chan.core.presenter.SettingsPresenter;
import org.floens.chan.core.settings.ChanSettings;
import org.floens.chan.ui.settings.LinkSettingView;
import org.floens.chan.ui.settings.SettingView;
import org.floens.chan.ui.settings.SettingsController;
import org.floens.chan.ui.settings.SettingsGroup;
import org.floens.chan.utils.AndroidUtils;

import javax.inject.Inject;

import static org.floens.chan.Chan.inject;
import static org.floens.chan.utils.AndroidUtils.getString;

public class MainSettingsController extends SettingsController implements SettingsPresenter.Callback {
    @Inject
    private SettingsPresenter presenter;

    private LinkSettingView watchLink;
    private SettingView developerView;
    private LinkSettingView sitesSetting;
    private LinkSettingView filtersSetting;

    public MainSettingsController(Context context) {
        super(context);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        inject(this);

        navigation.setTitle(R.string.settings_screen);

        setupLayout();

        populatePreferences();

        buildPreferences();

        if (!ChanSettings.developer.get()) {
            developerView.view.setVisibility(View.GONE);
        }

        presenter.create(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        presenter.destroy();
    }

    @Override
    public void onShow() {
        super.onShow();

        presenter.show();
    }

    @Override
    public void setFiltersCount(int count) {
        String filters = context.getResources().getQuantityString(R.plurals.filter, count, count);
        filtersSetting.setDescription(filters);
    }

    @Override
    public void setSiteCount(int count) {
        String sites = context.getResources().getQuantityString(R.plurals.site, count, count);
        sitesSetting.setDescription(sites);
    }

    @Override
    public void setWatchEnabled(boolean enabled) {
        watchLink.setDescription(enabled ?
                R.string.setting_watch_summary_enabled : R.string.setting_watch_summary_disabled);
    }

    @Override
    public void onPreferenceChange(SettingView item) {
        super.onPreferenceChange(item);
    }

    private void populatePreferences() {
        // General group
        {
            SettingsGroup general = new SettingsGroup(R.string.settings_group_settings);

            watchLink = (LinkSettingView) general.add(new LinkSettingView(this,
                    R.string.settings_watch, 0,
                    v -> navigationController.pushController(
                            new WatchSettingsController(context))));

            sitesSetting = (LinkSettingView) general.add(new LinkSettingView(this,
                    R.string.settings_sites, 0,
                    v -> navigationController.pushController(
                            new SitesSetupController(context))));

            general.add(new LinkSettingView(this,
                    R.string.settings_appearance, R.string.settings_appearance_description,
                    v -> navigationController.pushController(
                            new AppearanceSettingsController(context))));

            general.add(new LinkSettingView(this,
                    R.string.settings_behavior, R.string.settings_behavior_description,
                    v -> navigationController.pushController(
                            new BehaviourSettingsController(context))));

            general.add(new LinkSettingView(this,
                    R.string.settings_media, R.string.settings_media_description,
                    v -> navigationController.pushController(
                            new MediaSettingsController(context))));

            filtersSetting = (LinkSettingView) general.add(new LinkSettingView(this,
                    R.string.settings_filters, 0,
                    v -> navigationController.pushController(new FiltersController(context))));

            groups.add(general);
        }

        setupAboutGroup();
    }

    private void setupAboutGroup() {
        SettingsGroup about = new SettingsGroup(R.string.settings_group_about);

        setupVersionSetting(about);

        // Source code link
        about.add(new LinkSettingView(this,
                R.string.settings_about_source, R.string.settings_about_source_description,
                v -> AndroidUtils.openLink(getString(R.string.settings_about_source_url))));

        // Report a bug
        about.add(new LinkSettingView(this,
                R.string.settings_about_report, R.string.settings_about_report_description,
                v -> AndroidUtils.openLink(getString(R.string.settings_about_report_url))));

        about.add(new LinkSettingView(this,
                R.string.settings_about_license, R.string.settings_about_license_description,
                v -> navigationController.pushController(
                        new LicensesController(context,
                                getString(R.string.settings_about_license),
                                "file:///android_asset/html/license.html"))));

        about.add(new LinkSettingView(this,
                R.string.settings_about_licenses, R.string.settings_about_licenses_description,
                v -> navigationController.pushController(
                        new LicensesController(context,
                                getString(R.string.settings_about_licenses),
                                "file:///android_asset/html/licenses.html"))));

        developerView = about.add(new LinkSettingView(this,
                R.string.settings_developer, 0,
                v -> navigationController.pushController(
                        new DeveloperSettingsController(context))));

        groups.add(about);
    }

    private void setupExtraAboutSettings(SettingsGroup about, String version) {
        int extraAbouts = context.getResources()
                .getIdentifier("extra_abouts", "array", context.getPackageName());

        if (extraAbouts != 0) {
            String[] abouts = context.getResources().getStringArray(extraAbouts);
            if (abouts.length % 3 == 0) {
                for (int i = 0, aboutsLength = abouts.length; i < aboutsLength; i += 3) {
                    String aboutName = abouts[i];
                    String aboutDescription = abouts[i + 1];
                    if (TextUtils.isEmpty(aboutDescription)) {
                        aboutDescription = null;
                    }
                    String aboutLink = abouts[i + 2];
                    if (TextUtils.isEmpty(aboutLink)) {
                        aboutLink = null;
                    }

                    final String finalAboutLink = aboutLink;
                    View.OnClickListener clickListener = new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (finalAboutLink != null) {
                                if (finalAboutLink.contains("__EMAIL__")) {
                                    String[] email = finalAboutLink.split("__EMAIL__");
                                    Intent intent = new Intent(Intent.ACTION_SENDTO);
                                    intent.setData(Uri.parse("mailto:"));
                                    intent.putExtra(Intent.EXTRA_EMAIL, new String[]{email[0]});
                                    String subject = email[1];
                                    subject = subject.replace("__VERSION__", version);
                                    intent.putExtra(Intent.EXTRA_SUBJECT, subject);
                                    AndroidUtils.openIntent(intent);
                                } else {
                                    AndroidUtils.openLink(finalAboutLink);
                                }
                            }
                        }
                    };

                    about.add(new LinkSettingView(this,
                            aboutName, aboutDescription,
                            clickListener));
                }
            }
        }
    }

    private void setupVersionSetting(SettingsGroup about) {
        String version = "";
        try {
            version = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        String buildHash = BuildConfig.BUILD_HASH;
        String flavor = getString(R.string.app_flavor_name);
        String displayVersion = version
                + (buildHash.equals("unknown") ? "" : " (" + buildHash + ")")
                + (TextUtils.isEmpty(flavor) ? "" : " " + flavor);

        // Tapping the version row 5 times unlocks the developer menu (logs, DB reset).
        // To bump the version shown here, change versionName in app/build.gradle.
        about.add(new LinkSettingView(this,
                getString(R.string.app_name), displayVersion,
                v -> {
                    if (developerView != null) {
                        int count = (Integer) (v.getTag() != null ? v.getTag() : 0);
                        v.setTag(count + 1);
                        if ((count + 1) % 5 == 0) {
                            boolean developer = !ChanSettings.developer.get();
                            ChanSettings.developer.set(developer);
                            Toast.makeText(context,
                                    (developer ? "Developer options enabled" : "Developer options disabled"),
                                    Toast.LENGTH_SHORT).show();
                            developerView.view.setVisibility(developer ? View.VISIBLE : View.GONE);
                        }
                    }
                }));
    }

    // Update checking and crash reporting removed - configure your own endpoints
    // in build.gradle before re-enabling these.
}
