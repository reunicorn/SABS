package com.layoutxml.sabs.fragments;

import android.annotation.SuppressLint;
import android.arch.lifecycle.LifecycleFragment;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.layoutxml.sabs.App;
import com.layoutxml.sabs.R;
import com.layoutxml.sabs.db.AppDatabase;
import com.layoutxml.sabs.db.entity.AppInfo;
import com.layoutxml.sabs.utils.AppsListDBInitializer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.List;

import javax.inject.Inject;

public class MobileRestricterFragment extends LifecycleFragment {
    private static final String TAG = MobileRestricterFragment.class.getCanonicalName();
    private final int SORTED_ALPHABETICALLY = 0;
    private final int SORTED_INSTALL_TIME = 1;
    private final int SORTED_RESTRICTED = 2;
    @Inject
    AppDatabase mDb;
    @Inject
    PackageManager packageManager;
    private ListView installedAppsView;
    private Context context;
    private List<AppInfo> packageList;
    private MobileRestrictedAppAdapter adapter;
    private EditText editText;
    private int sortState = SORTED_ALPHABETICALLY;
    private AppCompatActivity parentActivity;


    public MobileRestricterFragment() {
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        App.get().getAppComponent().inject(this);
        parentActivity = (AppCompatActivity) getActivity();
    }

    @SuppressLint("StaticFieldLeak")
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        getActivity().setTitle(getString(R.string.mobile_restricter_fragment_title));
        if (parentActivity.getSupportActionBar() != null) {
            parentActivity.getSupportActionBar().setDisplayHomeAsUpEnabled(false);
            parentActivity.getSupportActionBar().setHomeButtonEnabled(false);
        }
        setHasOptionsMenu(true);
        View view = inflater.inflate(R.layout.fragment_mobile_restricter, container, false);
        context = getActivity().getApplicationContext();
        editText = view.findViewById(R.id.disabledFilter);
        editText.setOnClickListener(v -> editText.setCursorVisible(true));

        installedAppsView = view.findViewById(R.id.enabled_apps_list);
        installedAppsView.setOnItemClickListener((AdapterView<?> adView, View v, int i, long l) -> {
            MobileRestrictedAppAdapter mobileRestrictedAppAdapter = (MobileRestrictedAppAdapter) adView.getAdapter();
            final String name = mobileRestrictedAppAdapter.getItem(i).packageName;
            new AsyncTask<Void, Void, Boolean>() {
                @Override
                protected Boolean doInBackground(Void... o) {
                    AppInfo appInfo = mDb.applicationInfoDao().getByPackageName(name);
                    appInfo.mobileRestricted = !appInfo.mobileRestricted;
                    mDb.applicationInfoDao().insert(appInfo);
                    mobileRestrictedAppAdapter.applicationInfoList.set(i, appInfo);
                    return appInfo.mobileRestricted;
                }

                @Override
                protected void onPostExecute(Boolean b) {
                    ((Switch) v.findViewById(R.id.switchDisable)).setChecked(!b);
                }
            }.execute();
        });

        loadApplicationsList(false);
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                loadApplicationsList(false);
            }
        });
        Intent intent = parentActivity.getIntent();
        boolean bxIntegration = intent.getBooleanExtra("bxIntegration", false);
        if (bxIntegration) {
            intent.removeExtra("bxIntegration");
            editText.setText("com.samsung.android.app.spage");
            editText.requestFocus();
            editText.setCursorVisible(false);
        }
        return view;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.mobile_restricter_menu, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_pack_dis_sort:
                break;
            case R.id.sort_alphabetically_item:
                if (sortState == SORTED_ALPHABETICALLY) break;
                sortState = SORTED_ALPHABETICALLY;
                Toast.makeText(context, getString(R.string.app_list_sorted_by_alphabet), Toast.LENGTH_SHORT).show();
                loadApplicationsList(false);
                break;
            case R.id.sort_by_time_item:
                if (sortState == SORTED_INSTALL_TIME) break;
                sortState = SORTED_INSTALL_TIME;
                Toast.makeText(context, getString(R.string.app_list_sorted_by_date), Toast.LENGTH_SHORT).show();
                loadApplicationsList(false);
                break;
            case R.id.sort_restricted_item:
                sortState = SORTED_RESTRICTED;
                Toast.makeText(context, getString(R.string.app_list_sorted_by_restricted), Toast.LENGTH_SHORT).show();
                loadApplicationsList(false);
                break;
            case R.id.action_pack_dis_reload:
                editText.setText("");
                loadApplicationsList(true);
                break;
            case R.id.restricter_import_storage:
                Toast.makeText(context, getString(R.string.imported_restricted_from_storage), Toast.LENGTH_SHORT).show();
                importList();
                break;
            case R.id.restricter_export_storage:
                Toast.makeText(context, getString(R.string.exported_restricted_to_storage), Toast.LENGTH_SHORT).show();
                exportList();
                break;
            case R.id.restricter_enable_all:
                Toast.makeText(context, getString(R.string.enabled_all_restricted), Toast.LENGTH_SHORT).show();
                enableAllPackages();
        }
        return super.onOptionsItemSelected(item);
    }

    @SuppressLint("StaticFieldLeak")
    private void importList() {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... o) {
                File file = new File(Environment.getExternalStorageDirectory(), "mobile_restricted_packages.txt");

                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    String line;

                    while ((line = reader.readLine()) != null) {
                        try {
                            AppInfo appInfo = mDb.applicationInfoDao().getByPackageName(line);
                            appInfo.mobileRestricted = true;
                            mDb.applicationInfoDao().insert(appInfo);
                        }
                        catch (Exception e) {
                            // Ignore any potential errors
                        }
                    }
                }
                catch (IOException e) {
                    Log.e("Exception", "File write failed: " + e.toString());
                }

                return null;
            }

            @Override
            protected void onPostExecute(Void o) {
                super.onPostExecute(o);
                loadApplicationsList(true);
            }
        }.execute();
    }

    @SuppressLint("StaticFieldLeak")
    private void exportList() {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... o) {
                File file = new File(Environment.getExternalStorageDirectory(), "mobile_restricted_packages.txt");
                List<AppInfo> restrictedAppList = mDb.applicationInfoDao().getMobileRestrictedApps();

                try {
                    FileOutputStream stream = new FileOutputStream(file);
                    OutputStreamWriter writer = new OutputStreamWriter(stream);

                    writer.write("");

                    for (AppInfo app : restrictedAppList) {
                        writer.append(app.packageName + "\n");
                    }

                    writer.close();
                    stream.flush();
                    stream.close();
                }
                catch (IOException e) {
                    Log.e("Exception", "File write failed: " + e.toString());
                }

                return null;
            }
        }.execute();
    }

    @SuppressLint("StaticFieldLeak")
    private void enableAllPackages() {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... o) {
                List<AppInfo> restrictedAppList = mDb.applicationInfoDao().getMobileRestrictedApps();

                for (AppInfo app : restrictedAppList) {
                    app.mobileRestricted = false;
                    mDb.applicationInfoDao().insert(app);
                }

                return null;
            }

            @Override
            protected void onPostExecute(Void o) {
                super.onPostExecute(o);
                loadApplicationsList(true);
            }
        }.execute();
    }

    @SuppressLint("StaticFieldLeak")
    private void loadApplicationsList(boolean clear) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... o) {
                if (clear) mDb.applicationInfoDao().deleteAll();
                else {
                    packageList = getListFromDb();
                    if (packageList.size() != 0) return null;
                }
                AppsListDBInitializer.getInstance().fillPackageDb(packageManager);
                packageList = getListFromDb();
                return null;
            }

            @Override
            protected void onPostExecute(Void o) {
                super.onPostExecute(o);
                adapter = new MobileRestrictedAppAdapter(packageList);
                installedAppsView.setAdapter(adapter);
                installedAppsView.invalidateViews();
            }
        }.execute();
    }

    private List<AppInfo> getListFromDb() {
        String filterText = '%' + editText.getText().toString() + '%';
        switch (sortState) {
            case SORTED_ALPHABETICALLY:
                if (filterText.length() == 0) return mDb.applicationInfoDao().getEnabledApps();
                return mDb.applicationInfoDao().getEnabledAppsAlphabetically(filterText);
            case SORTED_INSTALL_TIME:
                if (filterText.length() == 0) return mDb.applicationInfoDao().getAllRecentSort();
                return mDb.applicationInfoDao().getEnabledAppsInTimeOrder(filterText);
            case SORTED_RESTRICTED:
                if (filterText.length() == 0)
                    return mDb.applicationInfoDao().getSortedMobileRestrictedApps();
                return mDb.applicationInfoDao().getSortedMobileRestrictedAppsWithStrInName(filterText);
        }
        return null;
    }

    public static class ViewHolder {
        TextView nameH;
        TextView packageH;
        Switch switchH;
        ImageView imageH;
    }

    private class MobileRestrictedAppAdapter extends BaseAdapter {
        public List<AppInfo> applicationInfoList;

        public MobileRestrictedAppAdapter(List<AppInfo> appInfoList) {
            applicationInfoList = appInfoList;
        }

        @Override
        public int getCount() {
            return this.applicationInfoList.size();
        }

        @Override
        public AppInfo getItem(int position) {
            return this.applicationInfoList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(R.layout.item_disable_app_list_view, parent, false);
                holder = new ViewHolder();
                holder.nameH = convertView.findViewById(R.id.appName);
                holder.packageH = convertView.findViewById(R.id.packName);
                holder.switchH = convertView.findViewById(R.id.switchDisable);
                holder.imageH = convertView.findViewById(R.id.appIcon);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }
            AppInfo appInfo = applicationInfoList.get(position);
            holder.nameH.setText(appInfo.appName);
            holder.packageH.setText(appInfo.packageName);
            holder.switchH.setChecked(!appInfo.mobileRestricted);
            if (appInfo.system) {
                convertView.findViewById(R.id.systemOrNot).setVisibility(View.VISIBLE);
            } else {
                convertView.findViewById(R.id.systemOrNot).setVisibility(View.GONE);
            }
            try {
                holder.imageH.setImageDrawable(packageManager.getApplicationIcon(appInfo.packageName));
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Failed to get ImageDrawable", e);
            }
            return convertView;
        }
    }
}
